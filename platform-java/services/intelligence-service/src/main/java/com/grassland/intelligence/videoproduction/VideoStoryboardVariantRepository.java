package com.grassland.intelligence.videoproduction;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 独立方案谱系存取（任务书 #100 C100-14 / V75 / API-11/12）。
 *
 * <p>写入口 {@link #insert}（操作键唯一冲突 0 行 → 服务层复读重放）；每根至多 20 派生
 * 由服务层在根行锁下计数闸。
 */
@Component
public class VideoStoryboardVariantRepository {

    private final DatabaseClient db;

    public VideoStoryboardVariantRepository(DatabaseClient db) {
        this.db = db;
    }

    public record Variant(UUID storyboardId, UUID parentStoryboardId, UUID rootStoryboardId, String accountId,
            UUID operationId, String requestHash, long sourceEditVersion, int sourceDraftVersion, String title,
            String shotIdMapJson, OffsetDateTime createdAt, UUID draftId) {
    }

    /** 谱系行 + 工作区绑定草稿（§6.1 VariantSummary.draftId；无绑定行时为 null——防御位，画布流必建绑定）。 */
    private static final String COLS = "v.storyboard_id::text, v.parent_storyboard_id::text, v.root_storyboard_id::text, "
            + "v.account_id, v.operation_id::text, v.request_hash, v.source_edit_version, v.source_draft_version, "
            + "v.title, v.shot_id_map::text, v.created_at, w.draft_id::text";

    public Mono<Long> insert(Variant variant) {
        return db.sql("INSERT INTO video_storyboard_variant(storyboard_id, parent_storyboard_id, "
                        + "root_storyboard_id, account_id, operation_id, request_hash, source_edit_version, "
                        + "source_draft_version, title, shot_id_map) VALUES (CAST(:id AS uuid), "
                        + "CAST(:parent AS uuid), CAST(:root AS uuid), :account, CAST(:operation AS uuid), "
                        + ":hash, :sourceEditVersion, :sourceDraftVersion, :title, CAST(:shotMap AS jsonb)) "
                        + "ON CONFLICT (account_id, operation_id) DO NOTHING")
                .bind("id", variant.storyboardId().toString())
                .bind("parent", variant.parentStoryboardId().toString())
                .bind("root", variant.rootStoryboardId().toString())
                .bind("account", variant.accountId())
                .bind("operation", variant.operationId().toString())
                .bind("hash", variant.requestHash())
                .bind("sourceEditVersion", variant.sourceEditVersion())
                .bind("sourceDraftVersion", variant.sourceDraftVersion())
                .bind("title", variant.title())
                .bind("shotMap", variant.shotIdMapJson())
                .fetch().rowsUpdated();
    }

    public Mono<Variant> findByStoryboard(UUID storyboardId) {
        return db.sql("SELECT " + COLS + " FROM video_storyboard_variant v "
                        + "LEFT JOIN video_storyboard_workspace w ON w.storyboard_id = v.storyboard_id "
                        + "WHERE v.storyboard_id=CAST(:id AS uuid)")
                .bind("id", storyboardId.toString())
                .map(VideoStoryboardVariantRepository::map)
                .one();
    }

    /** 幂等重放键：同账号同 operationId（跨账号不存在——插入时已绑定 account）。 */
    public Mono<Variant> findByAccountAndOperation(String accountId, UUID operationId) {
        return db.sql("SELECT " + COLS + " FROM video_storyboard_variant v "
                        + "LEFT JOIN video_storyboard_workspace w ON w.storyboard_id = v.storyboard_id "
                        + "WHERE v.account_id=:account AND v.operation_id=CAST(:operation AS uuid)")
                .bind("account", accountId)
                .bind("operation", operationId.toString())
                .map(VideoStoryboardVariantRepository::map)
                .one();
    }

    /** 谱系列表（root 升序）：根方案的所有派生。 */
    public Flux<Variant> findByRoot(UUID rootStoryboardId) {
        return db.sql("SELECT " + COLS + " FROM video_storyboard_variant v "
                        + "LEFT JOIN video_storyboard_workspace w ON w.storyboard_id = v.storyboard_id "
                        + "WHERE v.root_storyboard_id=CAST(:root AS uuid) "
                        + "ORDER BY v.created_at, v.storyboard_id")
                .bind("root", rootStoryboardId.toString())
                .map(VideoStoryboardVariantRepository::map)
                .all();
    }

    /** 根方案行的 draftId/createdAt（API-12 根行摘要；根分镜无绑定行时 draftId 为 null）。 */
    public Mono<RootRow> findRootRow(UUID rootStoryboardId) {
        return db.sql("SELECT w.draft_id::text, s.created_at FROM video_storyboard s "
                        + "LEFT JOIN video_storyboard_workspace w ON w.storyboard_id = s.id "
                        + "WHERE s.id=CAST(:root AS uuid)")
                .bind("root", rootStoryboardId.toString())
                .map((row, meta) -> new RootRow(
                        row.get("draft_id", String.class) == null ? null : UUID.fromString(row.get("draft_id", String.class)),
                        row.get("created_at", OffsetDateTime.class)))
                .one();
    }

    public record RootRow(UUID draftId, OffsetDateTime createdAt) {
    }

    public Mono<Long> countByRoot(UUID rootStoryboardId) {
        return db.sql("SELECT COUNT(*) FROM video_storyboard_variant "
                        + "WHERE root_storyboard_id=CAST(:root AS uuid)")
                .bind("root", rootStoryboardId.toString())
                .map((row, meta) -> row.get(0, Long.class))
                .one();
    }

    private static Variant map(Row row, RowMetadata meta) {
        return new Variant(UUID.fromString(row.get("storyboard_id", String.class)),
                UUID.fromString(row.get("parent_storyboard_id", String.class)),
                UUID.fromString(row.get("root_storyboard_id", String.class)),
                row.get("account_id", String.class),
                UUID.fromString(row.get("operation_id", String.class)),
                row.get("request_hash", String.class).trim(),
                row.get("source_edit_version", Long.class),
                row.get("source_draft_version", Integer.class),
                row.get("title", String.class),
                row.get("shot_id_map", String.class),
                row.get("created_at", OffsetDateTime.class),
                row.get("draft_id", String.class) == null ? null : UUID.fromString(row.get("draft_id", String.class)));
    }
}
