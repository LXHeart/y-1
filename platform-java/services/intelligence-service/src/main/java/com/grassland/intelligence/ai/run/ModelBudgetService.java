package com.grassland.intelligence.ai.run;

import java.time.LocalDate;
import java.util.UUID;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Service;

/**
 * AI 模型预算服务（GL-P3-AI-001 Phase 3）。
 * <p>负责预算校验、用量统计和超预算硬停。
 */
@Service
public class ModelBudgetService {

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
     * @return 预算检查结果
     */
    public Mono<BudgetCheckResult> checkAndReserve(
            String organizationId,
            String capability,
            String provider,
            int estimatedTokens,
            int estimatedCents) {

        if (organizationId == null) {
            return Mono.just(BudgetCheckResult.allowed(null, null, estimatedTokens, estimatedCents));
        }

        return budgetRepository.findByOrganizationAndCapability(organizationId, capability, provider)
                .flatMap(budget -> {
                    if (budget.exceedsRunBudget(estimatedTokens, estimatedCents)) {
                        return Mono.just(BudgetCheckResult.denied("exceeds_run_budget"));
                    }
                    return budgetRepository.reserve(budget.id(), estimatedTokens, estimatedCents)
                            .map(reservationDate -> BudgetCheckResult.allowed(
                                    budget.id(), reservationDate, estimatedTokens, estimatedCents))
                            .switchIfEmpty(denialAfterReservationConflict(
                                    organizationId, capability, provider, estimatedTokens, estimatedCents));
                })
                .switchIfEmpty(Mono.just(BudgetCheckResult.allowed(
                        null, null, estimatedTokens, estimatedCents)));
    }

    private Mono<BudgetCheckResult> denialAfterReservationConflict(
            String organizationId, String capability, String provider,
            int estimatedTokens, int estimatedCents) {
        return budgetRepository.findByOrganizationAndCapability(organizationId, capability, provider)
                .map(current -> {
                    if (current.exceedsRunBudget(estimatedTokens, estimatedCents)) {
                        return BudgetCheckResult.denied("exceeds_run_budget");
                    }
                    if (current.exceedsDailyBudget(estimatedTokens, estimatedCents)) {
                        return BudgetCheckResult.denied("exceeds_daily_budget");
                    }
                    return BudgetCheckResult.denied("exceeds_monthly_budget");
                })
                .defaultIfEmpty(BudgetCheckResult.denied("exceeds_daily_budget"));
    }

    public Mono<Boolean> settleReservation(
            BudgetCheckResult reservation, long actualTokens, long actualCents) {
        if (reservation.budgetId() == null) {
            return Mono.just(true);
        }
        return budgetRepository.settleReservation(
                reservation.budgetId(), reservation.reservationDate(),
                reservation.reservedTokens(), reservation.reservedCents(),
                actualTokens, actualCents);
    }

    public Mono<Boolean> releaseReservation(BudgetCheckResult reservation) {
        if (reservation.budgetId() == null) {
            return Mono.just(true);
        }
        return budgetRepository.releaseReservation(
                reservation.budgetId(), reservation.reservationDate(),
                reservation.reservedTokens(), reservation.reservedCents());
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
            UUID budgetId,
            LocalDate reservationDate,
            int reservedTokens,
            int reservedCents
    ) {
        /** 允许执行。 */
        public static BudgetCheckResult allowed(
                UUID budgetId, LocalDate reservationDate, int reservedTokens, int reservedCents) {
            return new BudgetCheckResult(
                    true, null, budgetId, reservationDate, reservedTokens, reservedCents);
        }

        /** 拒绝执行。 */
        public static BudgetCheckResult denied(String reason) {
            return new BudgetCheckResult(false, reason, null, null, 0, 0);
        }
    }
}
