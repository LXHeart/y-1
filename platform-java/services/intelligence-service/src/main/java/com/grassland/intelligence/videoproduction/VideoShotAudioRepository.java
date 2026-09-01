package com.grassland.intelligence.videoproduction;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * video_shot_audio 读写（任务书 #64 卡1，P1 MiniMax TTS）。shot_id 唯一，一镜一音轨。
 *
 * <p>旁白为空或 TTS 渠道不可用 → {@link #markSkipped}，**不阻断成片**（P6）。
 */
@Component
public class VideoShotAudioRepository {

    private static final String COLS = "id::text, shot_id::text, provider, model, provider_task_id, "
            + "status, attempts, media_id::text, cues::text, duration_ms, run_id::text, budget_id::text, "
            + "budget_reservation_date, reserved_cents, error_code, error_message, next_attempt_at, "
            + "claimed_until, claim_token::text, created_at, updated_at, completed_at";

    /** JOIN 查询用的带别名列表（别名列名与 COLS 一致，共用同一个 map）。 */
    private static final String JOIN_COLS = "a.id::text, a.shot_id::text, a.provider, a.model, "
            + "a.provider_task_id, a.status, a.attempts, a.media_id::text, a.cues::text, a.duration_ms, "
            + "a.run_id::text, a.budget_id::text, a.budget_reservation_date, a.reserved_cents, "
            + "a.error_code, a.error_message, a.next_attempt_at, a.claimed_until, a.claim_token::text, "
            + "a.created_at, a.updated_at, a.completed_at";

    private final DatabaseClient db;

    public VideoShotAudioRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 提交成片时逐镜入队；重放同一 shot 不报错也不重置已有进度。 */
    public Mono<VideoShotAudio> create(UUID shotId, String provider, String model) {
        return db.sql("INSERT INTO video_shot_audio(shot_id,provider,model) "
                        + "VALUES(CAST(:shot AS uuid),:provider,:model) "
                        + "ON CONFLICT(shot_id) DO NOTHING RETURNING " + COLS)
                .bind("shot", shotId.toString())
                .bind("provider", nullable(provider, String.class))
                .bind("model", nullable(model, String.class))
                .map(VideoShotAudioRepository::map)
                .one();
    }

    public Mono<VideoShotAudio> findByShot(UUID shotId) {
        return db.sql("SELECT " + COLS + " FROM video_shot_audio WHERE shot_id=CAST(:shot AS uuid)")
                .bind("shot", shotId.toString())
                .map(VideoShotAudioRepository::map)
                .one();
    }

    /** 卡8 合成读序：按镜序返回音轨，cues 供烧字幕与导出 SRT。 */
    public Flux<VideoShotAudio> findByStoryboard(UUID storyboardId) {
        return db.sql("SELECT " + JOIN_COLS + " FROM video_shot_audio a "
                        + "JOIN video_shot s ON s.id=a.shot_id "
                        + "WHERE s.storyboard_id=CAST(:sb AS uuid) ORDER BY s.seq")
                .bind("sb", storyboardId.toString())
                .map(VideoShotAudioRepository::map)
                .all();
    }

    /** worker 领单：与 take 同构的 lease 协议。 */
    public Flux<VideoShotAudio> claimBatch(int limit, Duration lease) {
        // RETURNING 全列加 a. 前缀：UPDATE ... FROM c 后 id 等列名与 c 撞（42702）
        return db.sql("WITH c AS (SELECT id FROM video_shot_audio "
                        + "WHERE status IN ('queued','submitted','processing') AND next_attempt_at<=now() "
                        + "AND (claimed_until IS NULL OR claimed_until<now()) "
                        + "ORDER BY next_attempt_at, created_at FOR UPDATE SKIP LOCKED LIMIT :l) "
                        + "UPDATE video_shot_audio a SET claimed_until=now()+CAST(:lease AS interval),"
                        + "claim_token=gen_random_uuid(),attempts=attempts+1,updated_at=now() "
                        + "FROM c WHERE a.id=c.id RETURNING a.id::text, a.shot_id::text, a.provider, a.model, "
                        + "a.provider_task_id, a.status, a.attempts, a.media_id::text, a.cues::text, "
                        + "a.duration_ms, a.run_id::text, a.budget_id::text, a.budget_reservation_date, "
                        + "a.reserved_cents, a.error_code, a.error_message, a.next_attempt_at, "
                        + "a.claimed_until, a.claim_token::text, a.created_at, a.updated_at, a.completed_at")
                .bind("l", limit)
                .bind("lease", lease.toSeconds() + " seconds")
                .map(VideoShotAudioRepository::map)
                .all();
    }

    /**
     * 挂免费执行环句柄（卡5）。只在 prepareExecution 成功后调用，本方法不动任何账本表；
     * run_id 已挂时不覆盖（重放周期沿用既有 run—— ExecutionContext 每 cycle 从行重建）。
     */
    public Mono<Boolean> attachRun(UUID id, UUID runId, UUID budgetId, LocalDate reservationDate,
            Integer reservedCents) {
        return db.sql("UPDATE video_shot_audio SET run_id=CAST(:run AS uuid),"
                        + "budget_id=CAST(:budget AS uuid),budget_reservation_date=:date,"
                        + "reserved_cents=:reserved,updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND run_id IS NULL "
                        + "AND status NOT IN ('succeeded','failed','skipped')")
                .bind("id", id.toString())
                .bind("run", runId.toString())
                .bind("budget", nullable(budgetId == null ? null : budgetId.toString(), String.class))
                .bind("date", nullable(reservationDate, LocalDate.class))
                .bind("reserved", nullable(reservedCents, Integer.class))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** 渠道进度回写（submitted / processing）：只留 provider 任务号，不落任何 URL。 */
    public Mono<Boolean> updateProviderState(UUID id, String status, String providerTaskId) {
        return db.sql("UPDATE video_shot_audio SET status=:status,"
                        + "provider_task_id=COALESCE(:task,provider_task_id),updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND status NOT IN ('succeeded','failed','skipped')")
                .bind("id", id.toString())
                .bind("status", status)
                .bind("task", nullable(providerTaskId, String.class))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /**
     * 归档完成置 succeeded。{@code durationMs} 必须是 ffprobe 实测值——卡8 音视频对齐与 P2
     * 实际时长结算都读它，估算值会让成片错位、账也算错。
     */
    public Mono<Boolean> attachMedia(UUID id, UUID mediaId, String cues, int durationMs) {
        return db.sql("UPDATE video_shot_audio SET status='succeeded',media_id=CAST(:media AS uuid),"
                        + "cues=CAST(:cues AS jsonb),duration_ms=:ms,error_code=NULL,error_message=NULL,"
                        + "claimed_until=NULL,claim_token=NULL,updated_at=now(),completed_at=now() "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString())
                .bind("media", mediaId.toString())
                .bind("cues", nullable(cues, String.class))
                .bind("ms", durationMs)
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** 无旁白 / 渠道不可用：静音分支，不算失败（P6 前端不得卡死）。 */
    public Mono<Boolean> markSkipped(UUID id, String reason) {
        return db.sql("UPDATE video_shot_audio SET status='skipped',error_code=:reason,"
                        + "claimed_until=NULL,claim_token=NULL,updated_at=now(),completed_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND status<>'succeeded'")
                .bind("id", id.toString())
                .bind("reason", nullable(reason, String.class))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    public Mono<Boolean> scheduleRetry(UUID id, String errorCode, String errorMessage, Duration backoff) {
        return db.sql("UPDATE video_shot_audio SET status='queued',error_code=:code,error_message=:message,"
                        + "next_attempt_at=now()+CAST(:backoff AS interval),claimed_until=NULL,"
                        + "claim_token=NULL,updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND status NOT IN ('succeeded','failed','skipped')")
                .bind("id", id.toString())
                .bind("code", nullable(errorCode, String.class))
                .bind("message", nullable(errorMessage, String.class))
                .bind("backoff", backoff.toSeconds() + " seconds")
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** 卡10 清理幂等标记：对象已删后置空 media_id。 */
    public Mono<Boolean> clearMedia(UUID id) {
        return db.sql("UPDATE video_shot_audio SET media_id=NULL,updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND media_id IS NOT NULL")
                .bind("id", id.toString())
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    public Mono<Boolean> markFailed(UUID id, String errorCode, String errorMessage) {
        return db.sql("UPDATE video_shot_audio SET status='failed',error_code=:code,error_message=:message,"
                        + "claimed_until=NULL,claim_token=NULL,updated_at=now(),completed_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND status NOT IN ('succeeded','skipped')")
                .bind("id", id.toString())
                .bind("code", nullable(errorCode, String.class))
                .bind("message", nullable(errorMessage, String.class))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    static VideoShotAudio map(Row r, RowMetadata m) {
        return new VideoShotAudio(
                UUID.fromString(r.get("id", String.class)),
                UUID.fromString(r.get("shot_id", String.class)),
                r.get("provider", String.class),
                r.get("model", String.class),
                r.get("provider_task_id", String.class),
                r.get("status", String.class),
                r.get("attempts", Integer.class),
                uuid(r.get("media_id", String.class)),
                r.get("cues", String.class),
                r.get("duration_ms", Integer.class),
                uuid(r.get("run_id", String.class)),
                uuid(r.get("budget_id", String.class)),
                r.get("budget_reservation_date", LocalDate.class),
                r.get("reserved_cents", Integer.class),
                r.get("error_code", String.class),
                r.get("error_message", String.class),
                r.get("next_attempt_at", OffsetDateTime.class),
                r.get("claimed_until", OffsetDateTime.class),
                uuid(r.get("claim_token", String.class)),
                r.get("created_at", OffsetDateTime.class),
                r.get("updated_at", OffsetDateTime.class),
                r.get("completed_at", OffsetDateTime.class));
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
