package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 一个镜头的配音（任务书 #64 卡1，P1 MiniMax TTS）。video_shot_audio 一行，shot_id 唯一。
 *
 * <p>旁白为空或 TTS 渠道不可用时落 {@code skipped}——**不阻断成片**（P6 前端不得卡死）。
 *
 * <p>{@code cues} 是字幕时间轴 JSON 文本（{@code [{"text":"...","startMs":0,"endMs":1800}]}），
 * 卡8 据此烧硬字幕并导出 SRT（P4 两者都要）。{@code durationMs} 是实测时长（真实 provider
 * 用 ffprobe、sandbox 由合成参数解析得出），卡8 音视频对齐与 P2 实际时长结算都读它。
 *
 * <p>卡5 起新增两组句柄：{@code providerTaskId}（MiniMax T2A 异步任务号，跨领单周期轮询）与
 * {@code runId}/{@code budgetId} 等免费执行环句柄——worker 每 cycle 重建 ExecutionContext，
 * 重启不悬空（video_generation_job 同款姿态）。
 */
public record VideoShotAudio(
        UUID id,
        UUID shotId,
        String provider,
        String model,
        String providerTaskId,
        String status,
        int attempts,
        UUID mediaId,
        String cues,
        Integer durationMs,
        UUID runId,
        UUID budgetId,
        LocalDate budgetReservationDate,
        Integer reservedCents,
        String errorCode,
        String errorMessage,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime claimedUntil,
        UUID claimToken,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        OffsetDateTime completedAt) {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_SUBMITTED = "submitted";
    public static final String STATUS_PROCESSING = "processing";
    public static final String STATUS_SUCCEEDED = "succeeded";
    public static final String STATUS_FAILED = "failed";
    /** 无旁白或 TTS 渠道不可用：静音成片，不算失败。 */
    public static final String STATUS_SKIPPED = "skipped";

    /** 有可用音轨（succeeded 且已归档）；skipped 走静音分支。 */
    @JsonIgnore
    public boolean isVoiced() {
        return STATUS_SUCCEEDED.equals(status) && mediaId != null;
    }

    /** 成片可继续（成功或明确跳过）。 */
    @JsonIgnore
    public boolean isSettled() {
        return isVoiced() || STATUS_SKIPPED.equals(status);
    }

    /** 已进免费执行环：失败路径必须走 handleFailure 释放预留。 */
    @JsonIgnore
    public boolean isBilled() {
        return runId != null;
    }
}
