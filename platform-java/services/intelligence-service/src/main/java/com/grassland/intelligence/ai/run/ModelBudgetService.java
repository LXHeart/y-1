package com.grassland.intelligence.ai.run;

import java.util.UUID;
import reactor.core.publisher.Mono;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI 模型预算服务（GL-P3-AI-001 Phase 3）。
 * <p>负责预算校验、用量统计和超预算硬停。
 */
@Service
public class ModelBudgetService {

    private static final Logger logger = LoggerFactory.getLogger(ModelBudgetService.class);

    private final AiModelBudgetRepository budgetRepository;
    private final AiRunRepository runRepository;

    public ModelBudgetService(
            AiModelBudgetRepository budgetRepository,
            AiRunRepository runRepository) {
        this.budgetRepository = budgetRepository;
        this.runRepository = runRepository;
    }

    /**
     * 检查并预留预算。
     *
     * @param organizationId 组织 ID（个人用户为 null）
     * @param capability 能力（text/image_generation 等）
     * @param estimatedTokens 预估 token 数
     * @param estimatedCents 预估金额（分）
     * @param operationId 操作 ID（幂等键）
     * @return 预算检查结果
     */
    public Mono<BudgetCheckResult> checkAndReserve(
            String organizationId,
            String capability,
            int estimatedTokens,
            int estimatedCents,
            UUID operationId) {

        if (organizationId == null) {
            // 个人用户默认无预算限制（TODO：可添加个人级预算）
            return Mono.just(BudgetCheckResult.allowed(estimatedCents));
        }

        return budgetRepository.findByOrganizationAndCapability(organizationId, capability)
                .flatMap(budget -> {
                    // 自动重置（跨日/跨月）
                    AiModelBudget resetBudget = budget;
                    if (budget.needsDailyReset()) {
                        logger.info("Resetting daily budget for org: {}, capability: {}", organizationId, capability);
                        resetBudget = resetBudget.resetDaily();
                        return budgetRepository.resetDaily(budget.id())
                                .then(Mono.just(resetBudget));
                    }
                    if (budget.needsMonthlyReset()) {
                        logger.info("Resetting monthly budget for org: {}, capability: {}", organizationId, capability);
                        resetBudget = resetBudget.resetMonthly();
                        return budgetRepository.resetMonthly(budget.id())
                                .then(Mono.just(resetBudget));
                    }
                    return Mono.just(budget);
                })
                .flatMap(budget -> {
                    // 检查单次 Run 预算
                    if (budget.exceedsRunBudget(estimatedTokens, estimatedCents)) {
                        logger.warn("Run budget exceeded for org: {}, capability: {}, tokens: {}, cents: {}",
                                organizationId, capability, estimatedTokens, estimatedCents);
                        return Mono.just(BudgetCheckResult.denied("exceeds_run_budget"));
                    }

                    // 检查每日预算
                    if (budget.exceedsDailyBudget(estimatedTokens, estimatedCents)) {
                        logger.warn("Daily budget exceeded for org: {}, capability: {}", organizationId, capability);
                        return Mono.just(BudgetCheckResult.denied("exceeds_daily_budget"));
                    }

                    // 检查每月预算
                    if (budget.exceedsMonthlyBudget(estimatedTokens, estimatedCents)) {
                        logger.warn("Monthly budget exceeded for org: {}, capability: {}", organizationId, capability);
                        return Mono.just(BudgetCheckResult.denied("exceeds_monthly_budget"));
                    }

                    // 通过检查，预留成功
                    return Mono.just(BudgetCheckResult.allowed(estimatedCents));
                })
                .switchIfEmpty(Mono.just(BudgetCheckResult.allowed(estimatedCents)));  // 无预算配置=允许
    }

    /**
     * 累加实际用量（Run 完成后调用）。
     *
     * @param organizationId 组织 ID
     * @param capability 能力
     * @param actualTokens 实际 token 数
     * @param actualCents 实际金额（分）
     * @return 是否成功累加
     */
    public Mono<Boolean> accumulateUsage(
            String organizationId,
            String capability,
            long actualTokens,
            long actualCents) {

        if (organizationId == null) {
            // 个人用户不统计
            return Mono.just(true);
        }

        return budgetRepository.findByOrganizationAndCapability(organizationId, capability)
                .flatMap(budget -> budgetRepository.accumulate(budget.id(), actualTokens, actualCents))
                .switchIfEmpty(Mono.just(true));  // 无预算配置=跳过
    }

    /**
     * 创建 AI Run 记录。
     *
     * @param run Run 实体
     * @return 创建后的 Run ID
     */
    public Mono<UUID> createRun(AiRun run) {
        return runRepository.create(run);
    }

    /**
     * 完成 AI Run（结算）—— 一并落用量计量（GL-P3-AI-001：计量列原恒空）。
     *
     * @param runId Run ID
     * @param actualCents 实际消耗（分）
     * @return 是否成功
     */
    public Mono<Boolean> completeRun(UUID runId, int actualCents, Integer inputTokens, Integer outputTokens,
                                     int imagesGenerated, int videoSeconds) {
        return runRepository.complete(runId, actualCents, inputTokens, outputTokens, imagesGenerated, videoSeconds);
    }

    /**
     * 标记 AI Run 失败。
     *
     * @param runId Run ID
     * @param reason 失败原因
     * @return 是否成功
     */
    public Mono<Boolean> failRun(UUID runId, String reason) {
        return runRepository.fail(runId, reason);
    }

    /**
     * 取消 AI Run（用户主动 abort）。
     *
     * @param runId Run ID
     * @return 是否成功
     */
    public Mono<Boolean> cancelRun(UUID runId) {
        return runRepository.cancel(runId);
    }

    /**
     * 预算检查结果。
     */
    public record BudgetCheckResult(
            boolean allowed,
            String denialReason,
            int reservedCents
    ) {
        /** 允许执行。 */
        public static BudgetCheckResult allowed(int reservedCents) {
            return new BudgetCheckResult(true, null, reservedCents);
        }

        /** 拒绝执行。 */
        public static BudgetCheckResult denied(String reason) {
            return new BudgetCheckResult(false, reason, 0);
        }
    }
}
