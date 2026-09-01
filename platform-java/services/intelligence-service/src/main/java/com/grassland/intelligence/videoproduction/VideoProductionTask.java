package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * 一次成片任务（任务书 #64 卡1）。video_production_task 一行，是 P2 计费的主体。
 *
 * <p><b>计费（P2 一价到底）</b>：预留 = {@code targetDurationSeconds × unitPriceCents}；
 * 结算 = {@code round(实际成片秒) × unitPriceCents}，差额退回。多 take / 重抽 / TTS / BGM /
 * 图文降级都不额外收费。本记录只存**冻结参数与句柄**——扣退一律经
 * {@code AiExecutionService.prepareMediaExecution / settleSuccessWithCost / handleFailure}，
 * 禁止任何手写账本 SQL（§5 计费红线）。
 *
 * <p>{@code mode}：{@code video}（有视频渠道）/ {@code slideshow}（P6 无渠道自动降级图文成片）。
 */
public record VideoProductionTask(
        UUID id,
        UUID storyboardId,
        String accountId,
        String organizationId,
        UUID contextSnapshotId,
        String operationId,
        String mode,
        String phase,
        int progress,
        String selection,
        UUID bgmTrackId,
        UUID finalMediaId,
        UUID srtMediaId,
        int targetDurationSeconds,
        Integer actualDurationSeconds,
        String pricingVersion,
        int unitPriceCents,
        int estimatedCostCents,
        Integer actualCostCents,
        String provider,
        String model,
        Integer platformModelVersion,
        UUID runId,
        UUID budgetId,
        LocalDate budgetReservationDate,
        Integer reservedCostCents,
        int attempts,
        String errorCode,
        String errorMessage,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime claimedUntil,
        UUID claimToken,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt) {

    public static final String MODE_VIDEO = "video";
    public static final String MODE_SLIDESHOW = "slideshow";

    public static final String PHASE_QUEUED = "queued";
    public static final String PHASE_GENERATING = "generating";
    public static final String PHASE_VOICING = "voicing";
    public static final String PHASE_COMPOSING = "composing";
    public static final String PHASE_SUCCEEDED = "succeeded";
    public static final String PHASE_FAILED = "failed";
    public static final String PHASE_CANCELLED = "cancelled";

    private static final Set<String> TERMINAL =
            Set.of(PHASE_SUCCEEDED, PHASE_FAILED, PHASE_CANCELLED);

    /** 终结态：worker 不再派发，前端停止轮询。 */
    @JsonIgnore
    public boolean isTerminal() {
        return isTerminalPhase(phase);
    }

    /** 卡4 SSE 终态帧判定（事件流据此收口）。 */
    public static boolean isTerminalPhase(String phase) {
        return TERMINAL.contains(phase);
    }

    /** P6 图文成片降级（无视频渠道）。 */
    @JsonIgnore
    public boolean isSlideshow() {
        return MODE_SLIDESHOW.equals(mode);
    }

    /** 已进入执行环（有 run_id）：失败路径必须走 handleFailure 释放预留。 */
    @JsonIgnore
    public boolean isBilled() {
        return runId != null;
    }
}
