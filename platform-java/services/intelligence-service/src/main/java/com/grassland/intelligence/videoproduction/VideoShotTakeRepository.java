package com.grassland.intelligence.videoproduction;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * video_shot_take 读写（任务书 #64 卡1）。claim 协议与 VideoGenerationJobRepository 同构。
 *
 * <p><b>红线</b>：provider 的临时结果 URL 永不落库、永不出响应；{@code media_id} 只在归档进私有
 * 对象存储后由 {@link #attachMedia} 写入。
 */
@Component
public class VideoShotTakeRepository {

    private static final String COLS = "id::text, shot_id::text, take_no, provider, model, "
            + "provider_task_id, status, attempts, media_id::text, duration_ms, error_code, "
            + "error_message, next_attempt_at, claimed_until, claim_token::text, created_at, "
            + "updated_at, completed_at";

    /** JOIN 查询用的带别名列表（别名列名与 COLS 一致，共用同一个 map）。 */
    private static final String JOIN_COLS = "t.id::text, t.shot_id::text, t.take_no, t.provider, "
            + "t.model, t.provider_task_id, t.status, t.attempts, t.media_id::text, t.duration_ms, "
            + "t.error_code, t.error_message, t.next_attempt_at, t.claimed_until, t.claim_token::text, "
            + "t.created_at, t.updated_at, t.completed_at";

    private final DatabaseClient db;

    public VideoShotTakeRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 派发前建候选行；同 (shot_id, take_no) 重放不报错（卡6 重抽走新的 take_no）。 */
    public Mono<VideoShotTake> create(UUID shotId, int takeNo, String provider, String model) {
        return db.sql("INSERT INTO video_shot_take(shot_id,take_no,provider,model) "
                        + "VALUES(CAST(:shot AS uuid),:no,:provider,:model) "
                        + "ON CONFLICT(shot_id,take_no) DO NOTHING RETURNING " + COLS)
                .bind("shot", shotId.toString())
                .bind("no", takeNo)
                .bind("provider", provider)
                .bind("model", model)
                .map(VideoShotTakeRepository::map)
                .one();
    }

    public Flux<VideoShotTake> findByShot(UUID shotId) {
        return db.sql("SELECT " + COLS + " FROM video_shot_take "
                        + "WHERE shot_id=CAST(:shot AS uuid) ORDER BY take_no")
                .bind("shot", shotId.toString())
                .map(VideoShotTakeRepository::map)
                .all();
    }

    /** 整条分镜的全部候选（卡8 合成、卡9 选片列表）：按镜序再按 take 序。 */
    public Flux<VideoShotTake> findByStoryboard(UUID storyboardId) {
        return db.sql("SELECT " + JOIN_COLS + " FROM video_shot_take t "
                        + "JOIN video_shot s ON s.id=t.shot_id "
                        + "WHERE s.storyboard_id=CAST(:sb AS uuid) ORDER BY s.seq, t.take_no")
                .bind("sb", storyboardId.toString())
                .map(VideoShotTakeRepository::map)
                .all();
    }

    /** 归属闸：经 shot→storyboard 两跳落到 account_id，越权取不到行（调用方 404）。 */
    public Mono<VideoShotTake> findByIdForAccount(UUID id, String accountId) {
        return db.sql("SELECT " + JOIN_COLS + " FROM video_shot_take t "
                        + "JOIN video_shot s ON s.id=t.shot_id "
                        + "JOIN video_storyboard b ON b.id=s.storyboard_id "
                        + "WHERE t.id=CAST(:id AS uuid) AND b.account_id=:accountId")
                .bind("id", id.toString())
                .bind("accountId", accountId)
                .map(VideoShotTakeRepository::map)
                .one();
    }

    /** worker 领单：FOR UPDATE SKIP LOCKED + lease，attempts 自增（RETURNING 全列加 t. 前缀防 c.id 歧义）。 */
    public Flux<VideoShotTake> claimBatch(int limit, Duration lease) {
        return db.sql("WITH c AS (SELECT id FROM video_shot_take "
                        + "WHERE status IN ('queued','submitted','processing') AND next_attempt_at<=now() "
                        + "AND (claimed_until IS NULL OR claimed_until<now()) "
                        + "ORDER BY next_attempt_at, created_at FOR UPDATE SKIP LOCKED LIMIT :l) "
                        + "UPDATE video_shot_take t SET claimed_until=now()+CAST(:lease AS interval),"
                        + "claim_token=gen_random_uuid(),attempts=attempts+1,updated_at=now() "
                        + "FROM c WHERE t.id=c.id RETURNING t.id::text, t.shot_id::text, t.take_no, "
                        + "t.provider, t.model, t.provider_task_id, t.status, t.attempts, t.media_id::text, "
                        + "t.duration_ms, t.error_code, t.error_message, t.next_attempt_at, t.claimed_until, "
                        + "t.claim_token::text, t.created_at, t.updated_at, t.completed_at")
                .bind("l", limit)
                .bind("lease", lease.toSeconds() + " seconds")
                .map(VideoShotTakeRepository::map)
                .all();
    }

    /** 渠道进度回写（submitted / processing）：不落 URL，只留 provider 任务号。 */
    public Mono<Boolean> updateProviderState(UUID id, String status, String providerTaskId) {
        return db.sql("UPDATE video_shot_take SET status=:status,"
                        + "provider_task_id=COALESCE(:task,provider_task_id),updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND status NOT IN ('succeeded','failed','cancelled')")
                .bind("id", id.toString())
                .bind("status", status)
                .bind("task", nullable(providerTaskId, String.class))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** 归档完成才置 succeeded：media_id 为空的 succeeded 不可入选（见 isSelectable）。 */
    public Mono<Boolean> attachMedia(UUID id, UUID mediaId, Integer durationMs) {
        return db.sql("UPDATE video_shot_take SET status='succeeded',media_id=CAST(:media AS uuid),"
                        + "duration_ms=COALESCE(:ms,duration_ms),error_code=NULL,error_message=NULL,"
                        + "claimed_until=NULL,claim_token=NULL,updated_at=now(),completed_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND status<>'cancelled'")
                .bind("id", id.toString())
                .bind("media", mediaId.toString())
                .bind("ms", nullable(durationMs, Integer.class))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** 可重试失败：留在非终结态、推后 next_attempt_at 并释放 lease。 */
    public Mono<Boolean> scheduleRetry(UUID id, String errorCode, String errorMessage, Duration backoff) {
        return db.sql("UPDATE video_shot_take SET status='queued',error_code=:code,error_message=:message,"
                        + "next_attempt_at=now()+CAST(:backoff AS interval),claimed_until=NULL,"
                        + "claim_token=NULL,updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND status NOT IN ('succeeded','failed','cancelled')")
                .bind("id", id.toString())
                .bind("code", nullable(errorCode, String.class))
                .bind("message", nullable(errorMessage, String.class))
                .bind("backoff", backoff.toSeconds() + " seconds")
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** 卡10 清理幂等标记：对象已删后置空 media_id（终态任务不受可选性影响）。 */
    public Mono<Boolean> clearMedia(UUID id) {
        return db.sql("UPDATE video_shot_take SET media_id=NULL,updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND media_id IS NOT NULL")
                .bind("id", id.toString())
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    public Mono<Boolean> markFailed(UUID id, String errorCode, String errorMessage) {
        return db.sql("UPDATE video_shot_take SET status='failed',error_code=:code,error_message=:message,"
                        + "claimed_until=NULL,claim_token=NULL,updated_at=now(),completed_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND status NOT IN ('succeeded','cancelled')")
                .bind("id", id.toString())
                .bind("code", nullable(errorCode, String.class))
                .bind("message", nullable(errorMessage, String.class))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** 任务取消时收口未终结候选；已成功的行保留（媒体已归档，清理归卡10）。 */
    public Mono<Long> cancelPendingByStoryboard(UUID storyboardId) {
        return db.sql("UPDATE video_shot_take t SET status='cancelled',claimed_until=NULL,"
                + "claim_token=NULL,updated_at=now(),completed_at=now() "
                + "FROM video_shot s WHERE s.id=t.shot_id "
                + "AND s.storyboard_id=CAST(:sb AS uuid) "
                + "AND t.status IN ('queued','submitted','processing')")
                .bind("sb", storyboardId.toString())
                .fetch().rowsUpdated();
    }

    /** 成片后单镜重抽（#65 卡6）：旧候选软删（cancelled），为同镜新候选腾出选择面。 */
    public Mono<Long> softDeleteByShot(UUID shotId) {
        return db.sql("UPDATE video_shot_take SET status='cancelled',claimed_until=NULL,"
                + "claim_token=NULL,updated_at=now(),completed_at=now() "
                + "WHERE shot_id=CAST(:shot AS uuid) AND status<>'cancelled'")
                .bind("shot", shotId.toString())
                .fetch().rowsUpdated();
    }

    static VideoShotTake map(Row r, RowMetadata m) {
        return new VideoShotTake(
                UUID.fromString(r.get("id", String.class)),
                UUID.fromString(r.get("shot_id", String.class)),
                r.get("take_no", Integer.class),
                r.get("provider", String.class),
                r.get("model", String.class),
                r.get("provider_task_id", String.class),
                r.get("status", String.class),
                r.get("attempts", Integer.class),
                uuid(r.get("media_id", String.class)),
                r.get("duration_ms", Integer.class),
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
