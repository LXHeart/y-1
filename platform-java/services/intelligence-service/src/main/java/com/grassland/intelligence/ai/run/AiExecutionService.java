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
 * Run 状态、预算释放、outbox 和 credit compensation intent 经 {@code TransactionalOperator} 同事务；
 * finance 补偿在事务提交后执行，失败由持久化 worker 幂等重试。
 */
@Service
public class AiExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(AiExecutionService.class);

    private final ModelBudgetService budgetService;
    private final ByokRoutingService routingService;
    private final PriceTableService priceTableService;
    private final IntelligenceCallerResolver callers;
    private final CreditsClient credits;
    private final ObjectProvider<EnvelopeEncryption> encryptionProvider;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final CreditCompensationRepository compensationRepository;
    private final CreditCompensationDispatcher compensationDispatcher;

    public AiExecutionService(
            ModelBudgetService budgetService,
            ByokRoutingService routingService,
            PriceTableService priceTableService,
            IntelligenceCallerResolver callers,
            CreditsClient credits,
            ObjectProvider<EnvelopeEncryption> encryptionProvider,
            OutboxRepository outbox,
            TransactionalOperator transactions,
            CreditCompensationRepository compensationRepository,
            CreditCompensationDispatcher compensationDispatcher) {
        this.budgetService = budgetService;
        this.routingService = routingService;
        this.priceTableService = priceTableService;
        this.callers = callers;
        this.credits = credits;
        this.encryptionProvider = encryptionProvider;
        this.outbox = outbox;
        this.transactions = transactions;
        this.compensationRepository = compensationRepository;
        this.compensationDispatcher = compensationDispatcher;
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
            int estimatedInputTokens,
            int estimatedOutputTokens,
            boolean allowFallback) {

        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> {
                    String orgId = caller.organizationId();
                    String accountId = caller.accountId();
                    UUID budgetOpId = UUID.randomUUID();
                    return routingService.resolveProvider(orgId, accountId, capability, allowFallback)
                            .flatMap(provider -> {
                                if (provider.isDenied()) {
                                    return Mono.just(ExecutionResult.denied(provider.denialReason()));
                                }
                                String decryptedKey = decryptIfNeeded(provider);
                                int estimatedTokens = Math.addExact(estimatedInputTokens, estimatedOutputTokens);
                                Integer estimatedCents = estimateForProvider(
                                        provider, estimatedInputTokens, estimatedOutputTokens);
                                if (estimatedCents == null) {
                                    return Mono.just(ExecutionResult.denied("unpriced_model"));
                                }
                                return reserveCreateAndCharge(
                                        provider, orgId, accountId, capability, feature,
                                        allowFallback, budgetOpId, decryptedKey,
                                        estimatedInputTokens, estimatedOutputTokens,
                                        estimatedTokens, estimatedCents, "sync", "v1");
                            });
                })
                .onErrorResume(InsufficientCreditsException.class,
                        e -> Mono.just(ExecutionResult.denied("insufficient_credits")));
    }

    /** 平台异步媒体任务入口：provider 与冻结价格由专用 adapter 配置提供。 */
    public Mono<ExecutionResult> preparePlatformAsyncExecution(
            String accountId, String organizationId, String capability, CreditFeature feature,
            ProviderResolution provider, UUID operationId, int estimatedCents,
            String priceTableVersion) {
        if (!provider.isPlatform() || estimatedCents < 0) {
            return Mono.error(new IllegalArgumentException("异步媒体任务必须使用合法的平台 provider"));
        }
        return reserveCreateAndCharge(
                        provider, organizationId, accountId, capability, feature,
                        true, operationId, null, 0, 0, 0, estimatedCents,
                        "async", priceTableVersion)
                .onErrorResume(InsufficientCreditsException.class,
                        e -> Mono.just(ExecutionResult.denied("insufficient_credits")));
    }

    private Mono<ExecutionResult> reserveCreateAndCharge(
            ProviderResolution provider,
            String orgId, String accountId, String capability, CreditFeature feature,
            boolean allowFallback, UUID budgetOpId, String decryptedKey,
            int estimatedInputTokens, int estimatedOutputTokens,
            int estimatedTokens, int estimatedCents, String runType,
            String priceTableVersion) {

        Mono<RunPreparation> preparation = reserveAndCreateRun(
                provider, orgId, accountId, capability, allowFallback, budgetOpId,
                estimatedTokens, estimatedCents, runType, priceTableVersion);
        return transactions.execute(ignored -> preparation)
                .single()
                .flatMap(prepared -> prepared.allowed()
                        ? chargeAfterRunCreated(
                                prepared.runId(), prepared.budget(), provider,
                                orgId, accountId, capability, feature, budgetOpId,
                                decryptedKey, prepared.priceTableVersion(),
                                estimatedInputTokens, estimatedOutputTokens)
                        : Mono.just(ExecutionResult.denied(prepared.denialReason())));
    }

    private Mono<RunPreparation> reserveAndCreateRun(
            ProviderResolution provider,
            String orgId, String accountId, String capability,
            boolean allowFallback, UUID budgetOpId,
            int estimatedTokens, int estimatedCents, String runType,
            String priceTableVersion) {

        return budgetService.checkAndReserve(
                        orgId, capability,
                        provider.isPlatform() ? "platform" : provider.provider(),
                        estimatedTokens, estimatedCents)
                .flatMap(budget -> budget.allowed()
                        ? createRunRecord(
                                budget, provider, orgId, accountId, capability,
                                allowFallback, budgetOpId, runType, priceTableVersion)
                        : Mono.just(RunPreparation.denied(budget.denialReason())));
    }

    private Mono<RunPreparation> createRunRecord(
            ModelBudgetService.BudgetCheckResult budget,
            ProviderResolution provider,
            String orgId, String accountId, String capability,
            boolean allowFallback, UUID budgetOpId, String runType,
            String priceTableVersion) {

        AiRun run = AiRun.forCreate(orgId, accountId, capability,
                provider.provider(), provider.model(), runType,
                budget.reservedCents(), budgetOpId, priceTableVersion,
                provider.platformModelVersion() > 0 ? provider.platformModelVersion() : null,
                allowFallback);
        return budgetService.createRun(run)
                .map(runId -> RunPreparation.allowed(
                        runId, budget, run.priceTableVersion()));
    }

    private Integer estimateForProvider(
            ProviderResolution provider, int estimatedInputTokens, int estimatedOutputTokens) {
        if (provider.isByok()) {
            return 0;
        }
        try {
            return priceTableService.calculateCost(
                    provider.model(), estimatedInputTokens, estimatedOutputTokens, 0, 0);
        } catch (IllegalArgumentException error) {
            logger.error("Refusing unpriced platform model: {}", provider.model());
            return null;
        }
    }

    private Mono<ExecutionResult> chargeAfterRunCreated(
            UUID runId,
            ModelBudgetService.BudgetCheckResult budget,
            ProviderResolution provider,
            String orgId,
            String accountId,
            String capability,
            CreditFeature feature,
            UUID operationId,
            String decryptedKey,
            String priceTableVersion,
            int estimatedInputTokens,
            int estimatedOutputTokens) {
        ExecutionContext cancellationContext = new ExecutionContext(
                runId, orgId, accountId, capability, provider, budget, operationId,
                null, feature, provider.isPlatform(), decryptedKey,
                priceTableVersion, estimatedInputTokens, estimatedOutputTokens);
        Mono<Optional<CreditCharge>> chargeMono = provider.isPlatform()
                ? credits.consume(accountId, feature, operationId.toString()).map(Optional::of)
                : Mono.just(Optional.empty());
        Mono<ExecutionResult> charge = chargeMono.map(optCharge -> ExecutionResult.allowed(new ExecutionContext(
                runId, orgId, accountId, capability, provider, budget, operationId,
                optCharge.orElse(null), feature, provider.isPlatform(), decryptedKey,
                priceTableVersion, estimatedInputTokens, estimatedOutputTokens)));
        return Mono.usingWhen(
                        Mono.just(cancellationContext),
                        ignored -> charge,
                        ignored -> Mono.empty(),
                        (ignored, error) -> Mono.empty(),
                        ignored -> handlePreparationCancellation(cancellationContext).then())
                .onErrorResume(error -> {
                    ExecutionContext failedContext = new ExecutionContext(
                            runId, orgId, accountId, capability, provider, budget, operationId,
                            null, feature,
                            provider.isPlatform() && !(error instanceof InsufficientCreditsException),
                            decryptedKey, priceTableVersion, estimatedInputTokens, estimatedOutputTokens);
                    return handleFailure(failedContext, errorMessage(error))
                            .then(Mono.error(error));
                });
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() == null ? "AI run preparation failed" : error.getMessage();
    }

    /** Run 成功结算：落用量计量 + 实际成本 + 累加预算用量 + 同事务 append AiRunCompleted。 */
    public Mono<Boolean> settleSuccess(
            ExecutionContext ctx, Integer inputTokens, Integer outputTokens,
            int imagesGenerated, int videoSeconds) {

        int actualCents = ctx.provider().isPlatform()
                ? safeCost(ctx.provider().model(), inputTokens, outputTokens, imagesGenerated, videoSeconds)
                : 0;

        return settleSuccessWithCost(
                ctx, actualCents, inputTokens, outputTokens, imagesGenerated, videoSeconds);
    }

    /** 使用任务创建时冻结的成本结算异步媒体 Run。 */
    public Mono<Boolean> settleSuccessWithCost(
            ExecutionContext ctx, int actualCents, Integer inputTokens, Integer outputTokens,
            int imagesGenerated, int videoSeconds) {
        if (actualCents < 0 || imagesGenerated < 0 || videoSeconds < 0) {
            return Mono.error(new IllegalArgumentException("AI 用量或成本不能为负数"));
        }
        Mono<Boolean> chain = budgetService.completeRun(ctx.runId(), actualCents, inputTokens, outputTokens,
                imagesGenerated, videoSeconds)
                .flatMap(ok -> {
                    if (!ok) {
                        return Mono.error(new IllegalStateException("AI run completion state update failed"));
                    }
                    return budgetService.settleReservation(
                                    ctx.budgetReservation(), tokens(inputTokens, outputTokens), actualCents)
                            .flatMap(settled -> settled
                                    ? Mono.just(true)
                                    : Mono.error(new IllegalStateException("AI run budget settlement failed")));
                })
                .flatMap(ignored -> outbox
                        .append(aiRunEvent("AiRunCompleted", ctx, actualCents, inputTokens, outputTokens))
                        .thenReturn(true));

        return transactions.transactional(chain)
                .doOnError(e -> logger.warn("settleSuccess failed for run {}", ctx.runId(), e));
    }

    /** provider/charge 失败：状态、预算、事件和补偿意图同事务；提交后触发一次补偿快路径。 */
    public Mono<Boolean> handleFailure(ExecutionContext ctx, String reason) {
        Mono<Boolean> markFailed = transactions.transactional(
                budgetService.failRun(ctx.runId(), reason)
                        .flatMap(ok -> ok
                                ? budgetService.releaseReservation(ctx.budgetReservation())
                                        .then(enqueueCompensation(ctx, "AI run failed: " + reason))
                                        .then(outbox.append(aiRunEvent("AiRunFailed", ctx, null, null, null)))
                                        .thenReturn(true)
                                : Mono.just(false)));

        return markFailed.flatMap(ok -> {
            if (!ok || !ctx.creditCompensationRequired()) {
                return Mono.just(ok);
            }
            return compensationDispatcher.dispatchRun(ctx.runId())
                    .onErrorResume(error -> {
                        logger.warn("Immediate credit compensation dispatch failed for run {}; intent remains pending",
                                ctx.runId(), error);
                        return Mono.empty();
                    })
                    .thenReturn(true);
        });
    }

    private Mono<Void> enqueueCompensation(ExecutionContext ctx, String reason) {
        if (!ctx.creditCompensationRequired()) {
            return Mono.empty();
        }
        return compensationRepository.enqueue(
                ctx.runId(), ctx.operationId(), ctx.accountId(), ctx.creditFeature().key(),
                reason);
    }

    /** Cancellation before provider execution compensates a platform consume whose response is unknown. */
    private Mono<Boolean> handlePreparationCancellation(ExecutionContext ctx) {
        Mono<Boolean> cancellation = transactions.transactional(
                budgetService.cancelRun(ctx.runId())
                        .flatMap(ok -> ok
                                ? budgetService.releaseReservation(ctx.budgetReservation())
                                        .then(enqueueCompensation(
                                                ctx, "AI run cancelled before provider execution"))
                                        .then(outbox.append(aiRunEvent(
                                                "AiRunCancelled", ctx, null, null, null)))
                                        .thenReturn(true)
                                : Mono.just(false)));
        return cancellation.flatMap(ok -> {
            if (!ok || !ctx.creditCompensationRequired()) {
                return Mono.just(ok);
            }
            return compensationDispatcher.dispatchRun(ctx.runId())
                    .onErrorResume(error -> {
                        logger.warn(
                                "Immediate credit compensation dispatch failed for cancelled run {}; "
                                        + "intent remains pending",
                                ctx.runId(), error);
                        return Mono.empty();
                    })
                    .thenReturn(true);
        });
    }

    /** 用户主动 abort：事务内标记 cancelled、释放预算并发事件；不退积分（内容可能已流出）。 */
    public Mono<Boolean> handleCancellation(ExecutionContext ctx) {
        Mono<Boolean> cancellation = budgetService.cancelRun(ctx.runId())
                .flatMap(ok -> ok
                        ? budgetService.releaseReservation(ctx.budgetReservation())
                                .then(outbox.append(aiRunEvent("AiRunCancelled", ctx, null, null, null)))
                                .thenReturn(true)
                        : Mono.just(false));
        return transactions.transactional(cancellation)
                .doOnNext(ok -> {
                    if (ok) {
                        logger.info("Run cancelled by user, no refund: {}", ctx.runId());
                    }
                });
    }

    /** BYOK usage is untrusted: never settle below the server-side reservation. */
    public TextCompletionResult normalizeProviderUsage(
            ExecutionContext ctx, TextCompletionResult completion) {
        if (!ctx.provider().isByok()) {
            return completion;
        }
        long reported = Math.addExact((long) completion.inputTokens(), (long) completion.outputTokens());
        long minimum = ctx.budgetReservation().reservedTokens();
        if (reported >= minimum) {
            return completion;
        }
        long adjustedInput = Math.addExact(
                (long) completion.inputTokens(), minimum - reported);
        if (adjustedInput > Integer.MAX_VALUE) {
            throw new IntelligenceException(502, "AI provider usage 超出支持范围");
        }
        return new TextCompletionResult(
                completion.content(), (int) adjustedInput, completion.outputTokens());
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
            ModelBudgetService.BudgetCheckResult budgetReservation,
            UUID operationId,
            CreditCharge charge,        // 平台 run 的扣减凭据；BYOK 为 null
            CreditFeature creditFeature,
            boolean creditCompensationRequired,
            String decryptedKey,        // BYOK 解密明文；平台为 null
            String priceTableVersion,
            int estimatedInputTokens,
            int estimatedOutputTokens) {
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

    private record RunPreparation(
            boolean allowed,
            String denialReason,
            UUID runId,
            ModelBudgetService.BudgetCheckResult budget,
            String priceTableVersion) {
        private static RunPreparation allowed(
                UUID runId, ModelBudgetService.BudgetCheckResult budget, String priceTableVersion) {
            return new RunPreparation(true, null, runId, budget, priceTableVersion);
        }

        private static RunPreparation denied(String reason) {
            return new RunPreparation(false, reason, null, null, null);
        }
    }
}
