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
 * video_production_task 读写（任务书 #64 卡1）。
 *
 * <p><b>计费红线</b>：本类只存冻结参数与账本句柄（run_id / budget_id / reserved_cost_cents），
 * **不含任何扣退 SQL**。预留与结算一律经 {@code AiExecutionService.prepareMediaExecution /
 * settleSuccessWithCost / handleFailure}；{@link #attachResult} 写的 actual_cost_cents 是结算后的
 * 回填快照，不是记账动作。
 */
@Component
public class VideoProductionTaskRepository {

    private static final String COLS = "id::text, storyboard_id::text, account_id, organization_id, "
            + "context_snapshot_id::text, operation_id, mode, phase, progress, selection::text, "
            + "bgm_track_id::text, final_media_id::text, srt_media_id::text, target_duration_seconds, "
            + "actual_duration_seconds, pricing_version, unit_price_cents, estimated_cost_cents, "
            + "actual_cost_cents, provider, model, platform_model_version, run_id::text, budget_id::text, "
            + "budget_reservation_date, reserved_cost_cents, attempts, error_code, error_message, "
            + "next_attempt_at, claimed_until, claim_token::text, created_at, updated_at, completed_at";

    private final DatabaseClient db;

    public VideoProductionTaskRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 建任务。{@code (account_id, operation_id)} UNIQUE + DO NOTHING：同一 operationId 重放返回空
     * Mono，调用方据此改读既有行（P2 幂等，不重复预留）。
     */
    public Mono<VideoProductionTask> create(UUID storyboardId, String accountId, String organizationId,
            UUID contextSnapshotId, String operationId, String mode, UUID bgmTrackId, String selection,
            int targetDurationSeconds, String pricingVersion, int unitPriceCents, int estimatedCostCents,
            String provider, String model, Integer platformModelVersion) {
        return db.sql("INSERT INTO video_production_task(storyboard_id,account_id,organization_id,"
                        + "context_snapshot_id,operation_id,mode,bgm_track_id,selection,"
                        + "target_duration_seconds,pricing_version,unit_price_cents,estimated_cost_cents,"
                        + "provider,model,platform_model_version) "
                        + "VALUES(CAST(:sb AS uuid),:account,:org,CAST(:snapshot AS uuid),:operation,:mode,"
                        + "CAST(:bgm AS uuid),CAST(:selection AS jsonb),:target,:pricing,:unit,:estimated,"
                        + ":provider,:model,:modelVersion) "
                        + "ON CONFLICT(account_id,operation_id) DO NOTHING RETURNING " + COLS)
                .bind("sb", storyboardId.toString())
                .bind("account", accountId)
                .bind("org", nullable(organizationId, String.class))
                .bind("snapshot", nullable(contextSnapshotId == null ? null : contextSnapshotId.toString(),
                        String.class))
                .bind("operation", operationId)
                .bind("mode", mode)
                .bind("bgm", nullable(bgmTrackId == null ? null : bgmTrackId.toString(), String.class))
                .bind("selection", nullable(selection, String.class))
                .bind("target", targetDurationSeconds)
                .bind("pricing", pricingVersion)
                .bind("unit", unitPriceCents)
                .bind("estimated", estimatedCostCents)
                .bind("provider", nullable(provider, String.class))
                .bind("model", nullable(model, String.class))
                .bind("modelVersion", nullable(platformModelVersion, Integer.class))
                .map(VideoProductionTaskRepository::map)
                .one();
    }

    public Mono<VideoProductionTask> findById(UUID id, String accountId) {
        return db.sql("SELECT " + COLS + " FROM video_production_task "
                        + "WHERE id=CAST(:id AS uuid) AND account_id=:accountId")
                .bind("id", id.toString())
                .bind("accountId", accountId)
                .map(VideoProductionTaskRepository::map)
                .one();
    }

    /** P2 幂等复读：create 撞 UNIQUE 返回空时用它取既有任务。 */
    public Mono<VideoProductionTask> findByAccountAndOperationId(String accountId, String operationId) {
        return db.sql("SELECT " + COLS + " FROM video_production_task "
                        + "WHERE account_id=:accountId AND operation_id=:operationId")
                .bind("accountId", accountId)
                .bind("operationId", operationId)
                .map(VideoProductionTaskRepository::map)
                .one();
    }

    /** 卡10 清理：已成功且完成早于阈值的任务（中间产物可清理）。 */
    public Flux<VideoProductionTask> findSucceededBefore(java.time.OffsetDateTime cutoff, int limit) {
        return db.sql("SELECT " + COLS + " FROM video_production_task "
                        + "WHERE phase='succeeded' AND completed_at<:cutoff "
                        + "ORDER BY completed_at LIMIT :limit")
                .bind("cutoff", cutoff)
                .bind("limit", limit)
                .map(VideoProductionTaskRepository::map)
                .all();
    }

    public Mono<Long> countByAccount(String accountId) {
        return db.sql("SELECT COUNT(*) AS total FROM video_production_task WHERE account_id=:accountId")
                .bind("accountId", accountId)
                .map(row -> row.get("total", Long.class))
                .one();
    }

    public Flux<VideoProductionTask> findByAccount(String accountId, int limit, long offset) {
        return db.sql("SELECT " + COLS + " FROM video_production_task WHERE account_id=:accountId "
                        + "ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
                .bind("accountId", accountId)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(VideoProductionTaskRepository::map)
                .all();
    }

    /** 一条分镜的最近任务（卡9 前端回到分镜页时恢复进度）。 */
    public Mono<VideoProductionTask> findLatestByStoryboard(UUID storyboardId, String accountId) {
        return db.sql("SELECT " + COLS + " FROM video_production_task "
                        + "WHERE storyboard_id=CAST(:sb AS uuid) AND account_id=:accountId "
                        + "ORDER BY created_at DESC LIMIT 1")
                .bind("sb", storyboardId.toString())
                .bind("accountId", accountId)
                .map(VideoProductionTaskRepository::map)
                .one();
    }
    /**
     * worker 领单：与 take/audio 同构的 lease 协议（RETURNING 全列加 t. 前缀防 c.id 歧义）。
     * 只领 composing——卡11 冒烟实测：SQL 不过滤 phase 时，generating 阶段的任务被领走后
     * 被 worker filter 丢弃，但 10 分钟租约已占用，紧随其后的 compose 请求领不到单。
     */
    public Flux<VideoProductionTask> claimBatch(int limit, Duration lease) {
        return db.sql("WITH c AS (SELECT id FROM video_production_task "
                        + "WHERE phase='composing' "
                        + "AND next_attempt_at<=now() "
                        + "AND (claimed_until IS NULL OR claimed_until<now()) "
                        + "ORDER BY next_attempt_at, created_at FOR UPDATE SKIP LOCKED LIMIT :l) "
                        + "UPDATE video_production_task t SET claimed_until=now()+CAST(:lease AS interval),"
                        + "claim_token=gen_random_uuid(),attempts=attempts+1,updated_at=now() "
                        + "FROM c WHERE t.id=c.id RETURNING t.id::text, t.storyboard_id::text, t.account_id, "
                        + "t.organization_id, t.context_snapshot_id::text, t.operation_id, t.mode, t.phase, "
                        + "t.progress, t.selection::text, t.bgm_track_id::text, t.final_media_id::text, "
                        + "t.srt_media_id::text, t.target_duration_seconds, t.actual_duration_seconds, "
                        + "t.pricing_version, t.unit_price_cents, t.estimated_cost_cents, t.actual_cost_cents, "
                        + "t.provider, t.model, t.platform_model_version, t.run_id::text, t.budget_id::text, "
                        + "t.budget_reservation_date, t.reserved_cost_cents, t.attempts, t.error_code, "
                        + "t.error_message, t.next_attempt_at, t.claimed_until, t.claim_token::text, "
                        + "t.created_at, t.updated_at, t.completed_at")
                .bind("l", limit)
                .bind("lease", lease.toSeconds() + " seconds")
                .map(VideoProductionTaskRepository::map)
                .all();
    }

    /**
     * 挂账本句柄。只在 {@code AiExecutionService.prepareMediaExecution} 成功返回后调用，
     * 本方法不动任何账本表；phase 仍为 queued 时才挂，避免重放覆盖已推进的任务。
     */
    public Mono<Boolean> attachRun(UUID id, UUID runId, UUID budgetId, LocalDate reservationDate,
            Integer reservedCostCents) {
        return db.sql("UPDATE video_production_task SET run_id=CAST(:run AS uuid),"
                        + "budget_id=CAST(:budget AS uuid),budget_reservation_date=:date,"
                        + "reserved_cost_cents=:reserved,updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND run_id IS NULL AND phase='queued'")
                .bind("id", id.toString())
                .bind("run", runId.toString())
                .bind("budget", nullable(budgetId == null ? null : budgetId.toString(), String.class))
                .bind("date", nullable(reservationDate, LocalDate.class))
                .bind("reserved", nullable(reservedCostCents, Integer.class))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** 阶段推进 + 进度单调不回退（前端轮询看到的进度不能倒着走）。 */
    public Mono<Boolean> updatePhase(UUID id, String phase, int progress) {
        return db.sql("UPDATE video_production_task SET phase=:phase,"
                        + "progress=GREATEST(progress,:progress),updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND phase NOT IN ('succeeded','failed','cancelled')")
                .bind("id", id.toString())
                .bind("phase", phase)
                .bind("progress", progress)
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** 用户选片/换片（卡9）：只在未终结时可改。 */
    public Mono<Boolean> setSelection(UUID id, String accountId, String selection) {
        return db.sql("UPDATE video_production_task SET selection=CAST(:selection AS jsonb),updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND account_id=:accountId "
                        + "AND phase NOT IN ('succeeded','failed','cancelled')")
                .bind("id", id.toString())
                .bind("accountId", accountId)
                .bind("selection", nullable(selection, String.class))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /**
     * 成片收口（卡8）。{@code actualDurationSeconds} 是 ffprobe 实测成片时长——P2 按它结算，
     * {@code actualCostCents} 是结算返回值的回填快照。**结算本身在 AiExecutionService**。
     */
    public Mono<Boolean> attachResult(UUID id, UUID finalMediaId, UUID srtMediaId,
            int actualDurationSeconds, Integer actualCostCents) {
        return db.sql("UPDATE video_production_task SET phase='succeeded',progress=100,"
                        + "final_media_id=CAST(:final AS uuid),srt_media_id=CAST(:srt AS uuid),"
                        + "actual_duration_seconds=:seconds,actual_cost_cents=:cost,"
                        + "error_code=NULL,error_message=NULL,claimed_until=NULL,claim_token=NULL,"
                        + "updated_at=now(),completed_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND phase NOT IN ('succeeded','cancelled')")
                .bind("id", id.toString())
                .bind("final", finalMediaId.toString())
                .bind("srt", nullable(srtMediaId == null ? null : srtMediaId.toString(), String.class))
                .bind("seconds", actualDurationSeconds)
                .bind("cost", nullable(actualCostCents, Integer.class))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    public Mono<Boolean> scheduleRetry(UUID id, String errorCode, String errorMessage, Duration backoff) {
        return db.sql("UPDATE video_production_task SET error_code=:code,error_message=:message,"
                        + "next_attempt_at=now()+CAST(:backoff AS interval),claimed_until=NULL,"
                        + "claim_token=NULL,updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND phase NOT IN ('succeeded','failed','cancelled')")
                .bind("id", id.toString())
                .bind("code", nullable(errorCode, String.class))
                .bind("message", nullable(errorMessage, String.class))
                .bind("backoff", backoff.toSeconds() + " seconds")
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** 终态失败。调用方必须已（或紧随）调 handleFailure 释放预留，否则预留悬空。 */
    public Mono<Boolean> markFailed(UUID id, String errorCode, String errorMessage) {
        return db.sql("UPDATE video_production_task SET phase='failed',error_code=:code,"
                        + "error_message=:message,claimed_until=NULL,claim_token=NULL,"
                        + "updated_at=now(),completed_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND phase NOT IN ('succeeded','cancelled')")
                .bind("id", id.toString())
                .bind("code", nullable(errorCode, String.class))
                .bind("message", nullable(errorMessage, String.class))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** 用户取消：只在未被 worker 持有且未终结时放行，避免与执行中的 ffmpeg 抢状态。 */
    public Mono<Boolean> cancel(UUID id, String accountId) {
        return db.sql("UPDATE video_production_task SET phase='cancelled',claimed_until=NULL,"
                        + "claim_token=NULL,updated_at=now(),completed_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND account_id=:accountId "
                        + "AND phase IN ('queued','generating','voicing') "
                        + "AND (claimed_until IS NULL OR claimed_until<now())")
                .bind("id", id.toString())
                .bind("accountId", accountId)
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    static VideoProductionTask map(Row r, RowMetadata m) {
        return new VideoProductionTask(
                UUID.fromString(r.get("id", String.class)),
                UUID.fromString(r.get("storyboard_id", String.class)),
                r.get("account_id", String.class),
                r.get("organization_id", String.class),
                uuid(r.get("context_snapshot_id", String.class)),
                r.get("operation_id", String.class),
                r.get("mode", String.class),
                r.get("phase", String.class),
                r.get("progress", Integer.class),
                r.get("selection", String.class),
                uuid(r.get("bgm_track_id", String.class)),
                uuid(r.get("final_media_id", String.class)),
                uuid(r.get("srt_media_id", String.class)),
                r.get("target_duration_seconds", Integer.class),
                r.get("actual_duration_seconds", Integer.class),
                r.get("pricing_version", String.class),
                r.get("unit_price_cents", Integer.class),
                r.get("estimated_cost_cents", Integer.class),
                r.get("actual_cost_cents", Integer.class),
                r.get("provider", String.class),
                r.get("model", String.class),
                r.get("platform_model_version", Integer.class),
                uuid(r.get("run_id", String.class)),
                uuid(r.get("budget_id", String.class)),
                r.get("budget_reservation_date", LocalDate.class),
                r.get("reserved_cost_cents", Integer.class),
                r.get("attempts", Integer.class),
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
