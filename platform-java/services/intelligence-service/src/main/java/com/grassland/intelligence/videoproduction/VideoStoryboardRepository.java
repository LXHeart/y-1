package com.grassland.intelligence.videoproduction;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** video_storyboard 读写（任务书 #64 卡1）。SQL 手写，沿用 VideoGenerationJobRepository 的映射惯例。 */
@Component
public class VideoStoryboardRepository {

    private static final String COLS = "id::text, account_id, organization_id, "
            + "context_snapshot_id::text, target_duration_seconds, request_payload::text, "
            + "status, created_at, updated_at";

    private final DatabaseClient db;

    public VideoStoryboardRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<VideoStoryboard> create(String accountId, String organizationId, UUID contextSnapshotId,
            int targetDurationSeconds, String requestPayload) {
        return db.sql("INSERT INTO video_storyboard(account_id,organization_id,context_snapshot_id,"
                        + "target_duration_seconds,request_payload) "
                        + "VALUES(:a,:o,CAST(:snapshot AS uuid),:d,CAST(:payload AS jsonb)) RETURNING " + COLS)
                .bind("a", accountId)
                .bind("o", nullable(organizationId, String.class))
                .bind("snapshot", nullable(contextSnapshotId == null ? null : contextSnapshotId.toString(),
                        String.class))
                .bind("d", targetDurationSeconds)
                .bind("payload", requestPayload)
                .map(VideoStoryboardRepository::map)
                .one();
    }

    public Mono<VideoStoryboard> findById(UUID id, String accountId) {
        return db.sql("SELECT " + COLS + " FROM video_storyboard "
                        + "WHERE id=CAST(:id AS uuid) AND account_id=:accountId")
                .bind("id", id.toString())
                .bind("accountId", accountId)
                .map(VideoStoryboardRepository::map)
                .one();
    }

    public Flux<VideoStoryboard> findByAccount(String accountId, int limit, int offset) {
        return db.sql("SELECT " + COLS + " FROM video_storyboard WHERE account_id=:accountId "
                        + "ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
                .bind("accountId", accountId)
                .bind("limit", limit)
                .bind("offset", offset)
                .map(VideoStoryboardRepository::map)
                .all();
    }

    /** 首次提交成片时把分镜冻结为 committed；已 committed 返回 false（幂等重放不报错）。 */
    public Mono<Boolean> markCommitted(UUID id) {
        return db.sql("UPDATE video_storyboard SET status='committed',updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND status='draft'")
                .bind("id", id.toString())
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    static VideoStoryboard map(Row r, RowMetadata m) {
        return new VideoStoryboard(
                UUID.fromString(r.get("id", String.class)),
                r.get("account_id", String.class),
                r.get("organization_id", String.class),
                uuid(r.get("context_snapshot_id", String.class)),
                r.get("target_duration_seconds", Integer.class),
                r.get("request_payload", String.class),
                r.get("status", String.class),
                r.get("created_at", OffsetDateTime.class),
                r.get("updated_at", OffsetDateTime.class));
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }
}
