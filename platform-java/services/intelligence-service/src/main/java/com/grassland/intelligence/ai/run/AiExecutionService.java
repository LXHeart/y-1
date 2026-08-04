package com.grassland.intelligence.ai.run;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.InsufficientCreditsException;
import com.grassland.intelligence.event.EventEnvelope;
import com.grassland.intelligence.event.OutboxRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * AI 执行编排（GL-P3-AI-001 控制面闭环）。统一入口：预算检查 → provider 解析 →（平台）credits 预留 →
 * （BYOK）密钥解密 → Run 落库；成功结算 / 失败退款 / 取消不退。
 *
 * <p>计费口径（D-11 + GL-P0-BILL-002）：
 * <ul>
 *   <li><b>平台模型</b>：Run 开始 {@link CreditsClient#consume} 预留 1 积分（其 operationId 即 Run operationId，
 *       保证退款幂等）；成功按 {@link PriceTableService} 算实际成本（首期不退差额——真实 credits↔cents 结算属 GL-P2-FIN-001）；
 *       provider 失败 {@link CreditsClient#refund} 全额退回。</li>
 *   <li><b>BYOK</b>：不收平台 AI 费（D-11），不 consume / 不 refund；用量仍入预算统计。</li>
 *   <li>用户主动 abort 不退（内容已流出）。</li>
 * </ul>
 *
 * <p>明文 BYOK 密钥只活在 {@link ExecutionContext} 进程内，**绝不入日志 / 响应 / outbox payload**（D-10 §PII）。
 * Run 状态变更与 outbox 事件经 {@code TransactionalOperator} 同事务；credits refund 在事务提交后 best-effort 执行
 * （不在 DB 事务内做 HTTP；refund 幂等，失败只记日志不掩盖原始错误，同 {@code CreditsClient.refund} 契约）。
 */
@Service
public class AiExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(AiExecutionService.class);
    private static final int PRICE_TABLE_VERSION_V1 = 1;

    private final ModelBudgetService budgetService;
    private final ByokRoutingService routingService;
    private final PriceTableService priceTableService;
    private final IntelligenceCallerResolver callers;
    private final CreditsClient credits;
    private final ObjectProvider<EnvelopeEncryption> encryptionProvider;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public AiExecutionService(
            ModelBudgetService budgetService,
            ByokRoutingService routingService,
            PriceTableService priceTableService,
            IntelligenceCallerResolver callers,
            CreditsClient credits,
            ObjectProvider<EnvelopeEncryption> encryptionProvider,
            OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.budgetService = budgetService;
        this.routingService = routingService;
        this.priceTableService = priceTableService;
        this.callers = callers;
        this.credits = credits;
        this.encryptionProvider = encryptionProvider;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    /**
     * Run 开始前的统一编排：解析调用者 → 预算检查 → provider 解析 → credits 预留（平台）→ 解密（BYOK）→ 落库。
     *
     * @param feature 平台 run 扣减的积分功能键（BYOK 不扣）
     */
    public Mono<ExecutionResult> prepareExecution(
            ServerWebExchange exchange,
            String capability,
            CreditFeature feature,
            int estimatedTokens,
            int estimatedCents,
            boolean allowFallback) {

        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> {
                    String orgId = caller.organizationId();
                    String accountId = caller.accountId();
                    UUID budgetOpId = UUID.randomUUID();
                    return budgetService.checkAndReserve(orgId, capability, estimatedTokens, estimatedCents, budgetOpId)
                            .flatMap(budget -> budget.allowed()
                                    ? resolveAndCreate(budget, orgId, accountId, capability, feature, allowFallback, budgetOpId)
                                    : Mono.just(ExecutionResult.denied(budget.denialReason())));
                })
                .onErrorResume(InsufficientCreditsException.class,
                        e -> Mono.just(ExecutionResult.denied("insufficient_credits")));
    }

    private Mono<ExecutionResult> resolveAndCreate(
            ModelBudgetService.BudgetCheckResult budget,
            String orgId, String accountId, String capability, CreditFeature feature,
            boolean allowFallback, UUID budgetOpId) {

        return routingService.resolveProvider(orgId, accountId, capability, allowFallback)
                .flatMap(provider -> {
                    if (provider.isDenied()) {
                        return Mono.just(ExecutionResult.denied(provider.denialReason()));
                    }
                    // 平台 → consume 拿 canonical operationId；BYOK → 无 charge（Optional 承载，避免 Mono.just(null)）
                    Mono<Optional<CreditCharge>> chargeMono = provider.isPlatform()
                            ? credits.consume(accountId, feature).map(Optional::of)
                            : Mono.just(Optional.<CreditCharge>empty());
                    return chargeMono.flatMap(optCharge -> {
                        CreditCharge charge = optCharge.orElse(null);
                        UUID operationId = charge != null ? UUID.fromString(charge.operationId()) : budgetOpId;
                        // 同步解密：AES-GCM 解密是轻量 CPU 操作（非 Argon2 那种内存重操作），可在事件循环上
                        // 直接做（同 BYOK controller 的 encrypt 调用）。平台 run 返回 null；无 KEK 抛 503。
                        String decryptedKey = decryptIfNeeded(provider);
                        AiRun run = AiRun.forCreate(orgId, accountId, capability,
                                provider.provider(), provider.model(), "sync",
                                budget.reservedCents(), operationId, PRICE_TABLE_VERSION_V1,
                                provider.platformModelVersion() > 0 ? provider.platformModelVersion() : null,
                                allowFallback);
                        return budgetService.createRun(run)
                                .map(runId -> ExecutionResult.allowed(new ExecutionContext(
                                        runId, orgId, accountId, capability, provider,
                                        budget.reservedCents(), operationId, charge, decryptedKey,
                                        run.priceTableVersion())));
                    });
                });
    }

    /** Run 成功结算：落用量计量 + 实际成本 + 累加预算用量 + 同事务 append AiRunCompleted。 */
    public Mono<Boolean> settleSuccess(
            ExecutionContext ctx, Integer inputTokens, Integer outputTokens,
            int imagesGenerated, int videoSeconds) {

        int actualCents = ctx.provider().isPlatform()
                ? safeCost(ctx.provider().model(), inputTokens, outputTokens, imagesGenerated, videoSeconds)
                : 0;

        Mono<Boolean> chain = budgetService.completeRun(ctx.runId(), actualCents, inputTokens, outputTokens,
                imagesGenerated, videoSeconds)
                .flatMap(ok -> {
                    if (!ok) {
                        return Mono.just(false);
                    }
                    if (ctx.provider().isPlatform()) {
                        return budgetService.accumulateUsage(ctx.organizationId(), ctx.capability(),
                                tokens(inputTokens, outputTokens), actualCents).thenReturn(true);
                    }
                    return Mono.just(true);
                })
                .flatMap(ok -> outbox
                        .append(aiRunEvent("AiRunCompleted", ctx, actualCents, inputTokens, outputTokens))
                        .thenReturn(ok));

        return transactions.transactional(chain)
                .doOnError(e -> logger.warn("settleSuccess failed for run {}", ctx.runId(), e));
    }

    /** provider 失败：标记 failed + 同事务 append AiRunFailed；提交后 best-effort 退预留（平台 run）。 */
    public Mono<Boolean> handleFailure(ExecutionContext ctx, String reason) {
        Mono<Boolean> markFailed = transactions.transactional(
                budgetService.failRun(ctx.runId(), reason)
                        .flatMap(ok -> ok
                                ? outbox.append(aiRunEvent("AiRunFailed", ctx, null, null, null)).thenReturn(true)
                                : Mono.just(false)));

        return markFailed.flatMap(ok -> {
            if (!ok) {
                return Mono.just(false);
            }
            if (ctx.charge() == null) {
                return Mono.just(true);  // BYOK：无预留可退
            }
            return credits.refund(ctx.charge(), "AI run failed: " + reason)
                    .thenReturn(true)
                    .onErrorResume(e -> {
                        logger.warn("credits refund failed for run {} (best-effort; idempotent retry 由 AiRunFailed 事件兜底)",
                                ctx.runId(), e);
                        return Mono.just(true);
                    });
        });
    }

    /** 用户主动 abort：标记 cancelled，不退（内容已流出）。 */
    public Mono<Boolean> handleCancellation(ExecutionContext ctx) {
        return budgetService.cancelRun(ctx.runId())
                .doOnNext(ok -> {
                    if (ok) {
                        logger.info("Run cancelled by user, no refund: {}", ctx.runId());
                    }
                });
    }

    /** BYOK 密钥解密（同步）。平台 run 返回 null；无 KEK 抛 503（fail-closed，平台 run 不受影响）。 */
    private String decryptIfNeeded(ProviderResolution provider) {
        if (!provider.needsKeyDecryption()) {
            return null;
        }
        EnvelopeEncryption crypto = encryptionProvider.getIfAvailable();
        if (crypto == null) {
            throw new IntelligenceException(503, "BYOK 解密不可用：未配置 CRYPTO_KEK_BASE64");
        }
        return crypto.decrypt(provider.encryptedKey());
    }

    /** 价目表无该模型时不崩结算（记日志、按 0 计）——价目表覆盖度是已知缺口。 */
    private int safeCost(String model, Integer inputTokens, Integer outputTokens, int images, int seconds) {
        try {
            return priceTableService.calculateCost(model,
                    inputTokens == null ? 0 : inputTokens,
                    outputTokens == null ? 0 : outputTokens,
                    images, seconds);
        } catch (IllegalArgumentException e) {
            logger.warn("No price table entry for model {}; actualCents recorded as 0", model);
            return 0;
        }
    }

    private static long tokens(Integer inputTokens, Integer outputTokens) {
        return (inputTokens == null ? 0 : inputTokens) + (outputTokens == null ? 0 : outputTokens);
    }

    private static EventEnvelope aiRunEvent(String eventType, ExecutionContext ctx, Integer actualCents,
                                            Integer inputTokens, Integer outputTokens) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", ctx.runId().toString());
        payload.put("organizationId", ctx.organizationId());
        payload.put("accountId", ctx.accountId());
        payload.put("capability", ctx.capability());
        payload.put("provider", ctx.provider().provider());
        payload.put("model", ctx.provider().model());
        payload.put("resolutionType", ctx.provider().type().name());
        if (actualCents != null) {
            payload.put("actualCents", actualCents);
        }
        if (inputTokens != null) {
            payload.put("inputTokens", inputTokens);
        }
        if (outputTokens != null) {
            payload.put("outputTokens", outputTokens);
        }
        // 刻意不含 decryptedKey / 任何明文密钥（D-10 §PII）
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                eventType,
                "ai_run",
                ctx.runId().toString(),
                1,
                Instant.now(),
                ctx.operationId().toString(),
                payload);
    }

    /** 执行上下文（Run 已落库）。明文密钥只存活于此进程对象，不序列化进响应/事件。 */
    public record ExecutionContext(
            UUID runId,
            String organizationId,
            String accountId,
            String capability,
            ProviderResolution provider,
            int reservedCents,
            UUID operationId,
            CreditCharge charge,        // 平台 run 的扣减凭据；BYOK 为 null
            String decryptedKey,        // BYOK 解密明文；平台为 null
            String priceTableVersion) {
    }

    /** prepare 结果。{@code allowed=false} 时 {@code denialReason} 决定 HTTP 状态。 */
    public record ExecutionResult(
            boolean allowed,
            String denialReason,
            ExecutionContext context) {
        public static ExecutionResult denied(String reason) {
            return new ExecutionResult(false, reason, null);
        }

        public static ExecutionResult allowed(ExecutionContext context) {
            return new ExecutionResult(true, null, context);
        }
    }
}
