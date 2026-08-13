package com.grassland.intelligence.videoproduction;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import static com.grassland.intelligence.config.R2dbcBindings.nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class VideoGenerationJobRepository {
    private static final String COLS = "id::text, account_id, organization_id, idempotency_key, run_id::text, context_snapshot_id::text, provider, model, provider_task_id, status, progress, input_payload::text, result_url, requested_duration_seconds, actual_duration_seconds, aspect_ratio, pricing_version, unit_price_cents, estimated_cost_cents, actual_cost_cents, budget_id::text, budget_reservation_date, reserved_cost_cents, platform_model_version, provider_config_fingerprint, attempt_count, next_attempt_at, error_code, error_message";
    private final DatabaseClient db;
    static String columns() { return COLS; }
    static VideoGenerationJob mapRow(Row r, RowMetadata m) { return map(r, m); }
    public VideoGenerationJobRepository(DatabaseClient db) { this.db = db; }
    public Mono<VideoGenerationJob> findById(UUID id, String accountId) { return db.sql("SELECT "+COLS+" FROM video_generation_job WHERE id=CAST(:id AS uuid) AND account_id=:accountId").bind("id", id.toString()).bind("accountId", accountId).map(VideoGenerationJobRepository::map).one(); }
    public Mono<VideoGenerationJob> findByIdempotency(String accountId, String key) { return db.sql("SELECT "+COLS+" FROM video_generation_job WHERE account_id=:accountId AND idempotency_key=:key").bind("accountId", accountId).bind("key", key).map(VideoGenerationJobRepository::map).one(); }
    public Flux<VideoGenerationJob> findByAccount(String accountId) { return db.sql("SELECT "+COLS+" FROM video_generation_job WHERE account_id=:accountId ORDER BY created_at DESC LIMIT 100").bind("accountId", accountId).map(VideoGenerationJobRepository::map).all(); }
    public Flux<VideoGenerationJob> claimBatch(int limit, java.time.Duration lease) { return db.sql("WITH c AS (SELECT id FROM video_generation_job WHERE status IN ('queued','submitted','processing') AND next_attempt_at<=now() AND (claimed_until IS NULL OR claimed_until<now()) ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT :l) UPDATE video_generation_job j SET claimed_until=now()+CAST(:lease AS interval),claim_token=gen_random_uuid(),attempt_count=attempt_count+1,updated_at=now() FROM c WHERE j.id=c.id RETURNING "+COLS).bind("l",limit).bind("lease",lease.toSeconds()+" seconds").map(VideoGenerationJobRepository::map).all(); }
    public Mono<VideoGenerationJob> create(String accountId, String orgId, String key, UUID snapshotId,
                                            String provider, String model, String payload, int duration,
                                            String aspect, String pricing, int unitPrice, int estimated,
                                            int modelVersion, String configFingerprint) {
        return db.sql("INSERT INTO video_generation_job(account_id,organization_id,idempotency_key,context_snapshot_id,provider,model,input_payload,requested_duration_seconds,aspect_ratio,pricing_version,unit_price_cents,estimated_cost_cents,platform_model_version,provider_config_fingerprint) VALUES(:a,:o,:k,CAST(:snapshot AS uuid),:p,:m,CAST(:payload AS jsonb),:d,:r,:v,:u,:e,:mv,:fingerprint) ON CONFLICT(account_id,idempotency_key) DO NOTHING RETURNING "+COLS)
                .bind("a", accountId).bind("o", nullable(orgId,String.class)).bind("k",key)
                .bind("snapshot", nullable(snapshotId == null ? null : snapshotId.toString(), String.class))
                .bind("p",provider).bind("m",model).bind("payload",payload).bind("d",duration)
                .bind("r",aspect).bind("v",pricing).bind("u",unitPrice).bind("e",estimated)
                .bind("mv",modelVersion).bind("fingerprint",configFingerprint)
                .map(VideoGenerationJobRepository::map).one();
    }
    public Mono<Boolean> attachRun(UUID id, UUID runId, UUID budgetId, java.time.LocalDate date, int reserved) { return db.sql("UPDATE video_generation_job SET run_id=CAST(:r AS uuid),budget_id=CAST(:b AS uuid),budget_reservation_date=:d,reserved_cost_cents=:c,status='queued',updated_at=now() WHERE id=CAST(:id AS uuid) AND status='preparing'").bind("id",id.toString()).bind("r",runId.toString()).bind("b",nullable(budgetId,String.class)).bind("d",nullable(date,java.time.LocalDate.class)).bind("c",reserved).fetch().rowsUpdated().map(n->n>0); }
    public Mono<Boolean> update(UUID id, VideoGenerationProvider.ProviderResult r) {
        String status = switch (r.state()) {
            case UNKNOWN -> "processing";
            default -> r.state().name().toLowerCase();
        };
        boolean done = r.state() == VideoGenerationProvider.ProviderResult.State.SUCCEEDED
                || r.state() == VideoGenerationProvider.ProviderResult.State.FAILED;
        // Provider result URLs are never persisted or returned. result_url is reserved for the
        // internal /api/media/{id} reference written after private object-storage archival.
        return db.sql("UPDATE video_generation_job SET provider_task_id=COALESCE(:t,provider_task_id),"
                        + "status=CASE WHEN :done THEN :s WHEN status='processing' THEN status "
                        + "WHEN :s='processing' THEN :s WHEN status='submitted' AND :s='queued' THEN status ELSE :s END,"
                        + "progress=GREATEST(progress,COALESCE(:p,progress)),"
                        + "result_url=CASE WHEN :ec='archive_pending' THEN NULL ELSE result_url END,"
                        + "actual_duration_seconds=COALESCE(:d,actual_duration_seconds),error_code=:ec,error_message=:em,"
                        + "updated_at=now(),completed_at=CASE WHEN :done THEN now() ELSE completed_at END "
                        + "WHERE id=CAST(:id AS uuid) AND status NOT IN ('cancelled','succeeded','failed')")
                .bind("id",id.toString()).bind("t",nullable(r.providerTaskId(),String.class)).bind("s",status)
                .bind("p",nullable(r.progress(),Integer.class))
                .bind("d",nullable(r.durationSeconds(),Integer.class)).bind("ec",nullable(r.errorCode(),String.class))
                .bind("em",nullable(r.errorMessage(),String.class)).bind("done",done)
                .fetch().rowsUpdated().map(n->n>0);
    }
    public Mono<Boolean> setCost(UUID id, int cents) { return db.sql("UPDATE video_generation_job SET actual_cost_cents=:c,updated_at=now() WHERE id=CAST(:id AS uuid) AND status <> 'cancelled'").bind("id",id.toString()).bind("c",cents).fetch().rowsUpdated().map(n->n>0); }
    public Mono<Boolean> setResultReference(UUID id, String resultReference) { return db.sql("UPDATE video_generation_job SET result_url=:u,updated_at=now() WHERE id=CAST(:id AS uuid) AND status<>'cancelled'").bind("id",id.toString()).bind("u",resultReference).fetch().rowsUpdated().map(n->n>0); }
    public Mono<Boolean> cancel(UUID id, String accountId) { return db.sql("UPDATE video_generation_job SET status='cancelled',updated_at=now(),completed_at=now() WHERE id=CAST(:id AS uuid) AND account_id=:a AND status IN ('preparing','queued') AND (claimed_until IS NULL OR claimed_until<now())").bind("id",id.toString()).bind("a",accountId).fetch().rowsUpdated().map(n->n>0); }
    private static VideoGenerationJob map(Row r, RowMetadata m) { return new VideoGenerationJob(UUID.fromString(r.get("id",String.class)),r.get("account_id",String.class),r.get("organization_id",String.class),r.get("idempotency_key",String.class),uuid(r.get("run_id",String.class)),uuid(r.get("context_snapshot_id",String.class)),r.get("provider",String.class),r.get("model",String.class),r.get("provider_task_id",String.class),r.get("status",String.class),r.get("progress",Integer.class),r.get("input_payload",String.class),r.get("result_url",String.class),r.get("requested_duration_seconds",Integer.class),r.get("actual_duration_seconds",Integer.class),r.get("aspect_ratio",String.class),r.get("pricing_version",String.class),r.get("unit_price_cents",Integer.class),r.get("estimated_cost_cents",Integer.class),r.get("actual_cost_cents",Integer.class),uuid(r.get("budget_id",String.class)),r.get("budget_reservation_date",java.time.LocalDate.class),r.get("reserved_cost_cents",Integer.class),r.get("platform_model_version",Integer.class),r.get("provider_config_fingerprint",String.class),r.get("attempt_count",Integer.class),r.get("next_attempt_at",OffsetDateTime.class).toInstant(),r.get("error_code",String.class),r.get("error_message",String.class)); }
    private static UUID uuid(String v){return v==null?null:UUID.fromString(v);}
}
