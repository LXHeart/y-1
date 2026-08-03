package com.grassland.intelligence.ai.run;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.intelligence.ai.byok.AiProviderKey;
import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * AI 执行服务（GL-P3-AI-001 整合）。
 * <p>整合预算检查、BYOK 分发、用量记录的统一入口。
 */
@Service
public class AiExecutionService {

    private static final Logger logger = LoggerFactory.getLogger(AiExecutionService.class);

    private final ModelBudgetService budgetService;
    private final ByokRoutingService routingService;
    private final PriceTableService priceTableService;
    private final EnvelopeEncryption encryption;
    private final IntelligenceCallerResolver callers;

    public AiExecutionService(
            ModelBudgetService budgetService,
            ByokRoutingService routingService,
            PriceTableService priceTableService,
            EnvelopeEncryption encryption,
            IntelligenceCallerResolver callers) {
        this.budgetService = budgetService;
        this.routingService = routingService;
        this.priceTableService = priceTableService;
        this.encryption = encryption;
        this.callers = callers;
    }

    /**
     * 执行 AI 调用前检查（预算 + Provider 解析）。
     *
     * @param exchange HTTP 请求
     * @param capability 能力
     * @param estimatedTokens 预估 token
     * @param estimatedCents 预估金额
     * @return 执行上下文（包含 Provider 配置和预算检查结果）
     */
    public Mono<ExecutionContext> prepareExecution(
            ServerWebExchange exchange,
            String capability,
            int estimatedTokens,
            int estimatedCents) {

        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> {
                    String orgId = caller.organizationId();
                    String accountId = caller.accountId();
                    UUID operationId = UUID.randomUUID();

                    // 1. 预算检查
                    return budgetService.checkAndReserve(
                            orgId, capability, estimatedTokens, estimatedCents, operationId)
                            .flatMap(budgetResult -> {
                                if (!budgetResult.allowed()) {
                                    logger.warn("Budget denied for account: {}, reason: {}",
                                            accountId, budgetResult.denialReason());
                                    return Mono.just(ExecutionResult.denied(budgetResult.denialReason()));
                                }

                                // 2. Provider 解析
                                return routingService.resolveProvider(
                                        orgId, accountId, capability, false)
                                        .map(provider -> {
                                            // 3. 创建 AI Run 记录
                                            AiRun run = AiRun.forCreate(
                                                orgId,
                                                accountId,
                                                capability,
                                                provider.provider(),
                                                provider.model(),
                                                "sync",
                                                budgetResult.reservedCents(),
                                                operationId
                                            );

                                            return new ExecutionContext(
                                                provider,
                                                budgetResult.reservedCents(),
                                                operationId,
                                                run,
                                                null  // 暂无 decryptedKey
                                            );
                                        });
                            });
                });
    }

    /**
     * 执行成功后结算。
     *
     * @param context 执行上下文
     * @param inputTokens 输入 token
     * @param outputTokens 输出 token
     * @return 是否成功结算
     */
    public Mono<Boolean> settleSuccess(
            ExecutionContext context,
            int inputTokens,
            int outputTokens) {

        // 计算实际成本
        int actualCents;
        if (context.provider().isPlatform()) {
            actualCents = priceTableService.calculateCost(
                context.provider().model(),
                inputTokens,
                outputTokens,
                0, 0
            );
        } else {
            // BYOK 不收 AI 费，实际消耗为 0
            actualCents = 0;
        }

        // 完成 Run 记录
        return budgetService.completeRun(context.run().id(), actualCents)
                .flatMap(completed -> {
                    if (!completed) {
                        logger.warn("Failed to complete run: {}", context.run().id());
                        return Mono.just(false);
                    }

                    // 累加用量（仅平台模型；BYOK 不统计）
                    if (context.provider().isPlatform()) {
                        return budgetService.accumulateUsage(
                            context.run().organizationId(),
                            context.run().capability(),
                            inputTokens + outputTokens,
                            actualCents
                        );
                    }
                    return Mono.just(true);
                });
    }

    /**
     * 执行失败处理（退回预留）。
     *
     * @param context 执行上下文
     * @param reason 失败原因
     * @return 是否成功处理
     */
    public Mono<Boolean> handleFailure(ExecutionContext context, String reason) {
        return budgetService.failRun(context.run().id(), reason)
                .flatMap(failed -> {
                    if (!failed) {
                        logger.warn("Failed to mark run as failed: {}", context.run().id());
                        return Mono.just(false);
                    }
                    // TODO: 触发 credits 退款（GL-P0-BILL-002）
                    logger.info("Run failed, refund pending: {}, reason: {}",
                            context.operationId(), reason);
                    return Mono.just(true);
                });
    }

    /**
     * 用户主动取消（不退预留）。
     *
     * @param context 执行上下文
     * @return 是否成功取消
     */
    public Mono<Boolean> handleCancellation(ExecutionContext context) {
        return budgetService.cancelRun(context.run().id())
                .map(cancelled -> {
                    if (cancelled) {
                        logger.info("Run cancelled by user, no refund: {}", context.operationId());
                    }
                    return cancelled;
                });
    }

    /**
     * 执行上下文。
     */
    public record ExecutionContext(
        ProviderResolution provider,
        int reservedCents,
        UUID operationId,
        AiRun run,
        String decryptedKey  // BYOK 解密后的密钥（运行时使用）
    ) {
        /** 是否是 BYOK 调用。 */
        public boolean isByok() {
            return provider.isByok();
        }

        /** 是否是平台模型调用。 */
        public boolean isPlatform() {
            return provider.isPlatform();
        }

        /** 是否需要扣平台 AI 费。 */
        public boolean chargesPlatformFee() {
            return provider.chargesPlatformFee();
        }
    }

    /**
     * 执行结果。
     */
    public record ExecutionResult(
        boolean allowed,
        String denialReason,
        ExecutionContext context
    ) {
        /** 拒绝执行。 */
        public static ExecutionResult denied(String reason) {
            return new ExecutionResult(false, reason, null);
        }

        /** 允许执行。 */
        public static ExecutionResult allowed(ExecutionContext context) {
            return new ExecutionResult(true, null, context);
        }
    }
}
