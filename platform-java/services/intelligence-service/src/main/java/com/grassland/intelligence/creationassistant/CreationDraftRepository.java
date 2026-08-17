package com.grassland.intelligence.creationassistant;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 创作草稿仓储（草场 PRD §4.9.7 / Slice 15）。表 {@code creation_draft}（可变当前行）+
 * {@code creation_draft_version}（不可变快照）由 Flyway V19 建。
 *
 * <p>用法复刻 {@code ContentAssetRepository}：注入 {@link DatabaseClient}，timestamptz 读为
 * {@link OffsetDateTime} 再转 {@link Instant}，uuid 经 {@code ::text}/{@code CAST}，可空字段用 {@code bindNull}。
 */
@Component
public class CreationDraftRepository {

    private static final String SELECT_COLS = """
            id::text, owner_account_id, organization_id, title, source_type, task_id, task_version,
            store_id, platform, content_form, topic, article_title, outline, content, status, version,
            created_at, updated_at, deleted_at
            """;

    private static final String VERSION_UNION = """
            SELECT draft_id::text, version, title, source_type, task_id, task_version, store_id,
                   platform, content_form, topic, article_title, outline, content, status,
                   snapshotted_at AS created_at
            FROM creation_draft_version
            WHERE draft_id=CAST(:draftId AS uuid)
            UNION ALL
            SELECT id::text AS draft_id, version, title, source_type, task_id, task_version, store_id,
                   platform, content_form, topic, article_title, outline, content, status,
                   updated_at AS created_at
            FROM creation_draft
            WHERE id=CAST(:draftId AS uuid) AND deleted_at IS NULL
            """;

    private final DatabaseClient db;

    public CreationDraftRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建草稿。 */
    public Mono<CreationDraft> create(CreationDraft draft) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO creation_draft (
                    id, owner_account_id, organization_id, title, source_type, task_id, task_version,
                    store_id, platform, content_form, topic, article_title, outline, content, status)
                VALUES (
                    CAST(:id AS uuid), :ownerAccountId, :organizationId, :title, :sourceType, :taskId, :taskVersion,
                    :storeId, :platform, :contentForm, :topic, :articleTitle, :outline, :content, :status)
                """)
                .bind("id", draft.id().toString())
                .bind("ownerAccountId", draft.ownerAccountId())
                .bind("title", draft.title())
                .bind("sourceType", draft.sourceType().db())
                .bind("status", draft.status().db());
        spec = bindNullableString(spec, "organizationId", draft.organizationId());
        spec = bindNullableString(spec, "taskId", draft.taskId());
        spec = bindNullableInt(spec, "taskVersion", draft.taskVersion());
        spec = bindNullableString(spec, "storeId", draft.storeId());
        spec = bindNullableString(spec, "platform", draft.platform());
        spec = bindNullableString(spec, "contentForm", draft.contentForm());
        spec = bindNullableString(spec, "topic", draft.topic());
        spec = bindNullableString(spec, "articleTitle", draft.articleTitle());
        spec = bindNullableString(spec, "outline", draft.outline());
        spec = bindNullableString(spec, "content", draft.content());
        return spec.then().then(findById(draft.id()));
    }

    public Mono<CreationDraft> findById(UUID id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM creation_draft WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString())
                .map(CreationDraftRepository::map).one();
    }

    /** 列某用户的草稿（仅未软删，按更新时间倒序）。 */
    public Flux<CreationDraft> listByAccount(String ownerAccountId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM creation_draft"
                + " WHERE owner_account_id=:ownerAccountId AND deleted_at IS NULL"
                + " ORDER BY updated_at DESC")
                .bind("ownerAccountId", ownerAccountId)
                .map(CreationDraftRepository::map).all();
    }

    /**
     * 自动保存（乐观锁）。guarded：仅当 id + expectedVersion + 未软删匹配时更新创作字段。
     * 0 行（版本冲突/不存在）→ empty（controller 转 409）。
     */
    public Mono<CreationDraft> save(UUID id, int expectedVersion, String title, String topic,
                                    String articleTitle, String outline, String content,
                                    String platform, String contentForm, DraftStatus status) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                UPDATE creation_draft SET
                    title=:title, topic=:topic, article_title=:articleTitle, outline=:outline,
                    content=:content, platform=:platform, content_form=:contentForm, status=:status,
                    version=version+1, updated_at=now()
                WHERE id=CAST(:id AS uuid) AND version=:expectedVersion AND deleted_at IS NULL
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id.toString())
                .bind("expectedVersion", expectedVersion)
                .bind("title", title)
                .bind("status", status.db());
        spec = bindNullableString(spec, "topic", topic);
        spec = bindNullableString(spec, "articleTitle", articleTitle);
        spec = bindNullableString(spec, "outline", outline);
        spec = bindNullableString(spec, "content", content);
        spec = bindNullableString(spec, "platform", platform);
        spec = bindNullableString(spec, "contentForm", contentForm);
        return spec.map(CreationDraftRepository::map).one();
    }

    /** 软删草稿。guarded：仅当 id + 未软删时置 deleted_at。返回是否命中。 */
    public Mono<Boolean> softDelete(UUID id) {
        return db.sql("UPDATE creation_draft SET deleted_at=now(), updated_at=now()"
                + " WHERE id=CAST(:id AS uuid) AND deleted_at IS NULL")
                .bind("id", id.toString())
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    /**
     * 落不可变历史快照（镜像 task_version / content_asset_version）。每次保存前调用，
     * 把当前行整行镜像到 creation_draft_version。与 save 同事务（由 controller 串）。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 是并发自动保存的必需项，不是防御性冗余：跨设备/debounce 抖动下
     * 两个 PUT 可能读到同一 version 并各自 insert 同一 {@code (draft_id, version)} 主键。后到者会阻塞在
     * 唯一索引上直到前者提交，然后抛 duplicate key —— 该异常不在 {@code IntelligenceErrorHandler} 覆盖内，
     * 会漏成 500，掩盖本该由 {@link #save} 乐观锁给出的 409。同一 version 的快照内容恒等，忽略冲突安全。
     */
    public Mono<Void> appendVersion(CreationDraft draft, String savedBy) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO creation_draft_version (
                    draft_id, version, title, source_type, task_id, task_version, store_id,
                    platform, content_form, topic, article_title, outline, content, status, snapshotted_by)
                VALUES (
                    CAST(:draftId AS uuid), :version, :title, :sourceType, :taskId, :taskVersion, :storeId,
                    :platform, :contentForm, :topic, :articleTitle, :outline, :content, :status, :savedBy)
                ON CONFLICT (draft_id, version) DO NOTHING
                """)
                .bind("draftId", draft.id().toString())
                .bind("version", draft.version())
                .bind("title", draft.title())
                .bind("sourceType", draft.sourceType().db())
                .bind("status", draft.status().db())
                .bind("savedBy", savedBy);
        spec = bindNullableString(spec, "taskId", draft.taskId());
        spec = bindNullableInt(spec, "taskVersion", draft.taskVersion());
        spec = bindNullableString(spec, "storeId", draft.storeId());
        spec = bindNullableString(spec, "platform", draft.platform());
        spec = bindNullableString(spec, "contentForm", draft.contentForm());
        spec = bindNullableString(spec, "topic", draft.topic());
        spec = bindNullableString(spec, "articleTitle", draft.articleTitle());
        spec = bindNullableString(spec, "outline", draft.outline());
        spec = bindNullableString(spec, "content", draft.content());
        return spec.then();
    }

    /**
     * 版本历史按版本号倒序做 keyset 分页。当前版本尚未进入快照表，因此与历史快照合并返回。
     */
    public Flux<CreationDraftVersion> listVersions(UUID draftId, Integer beforeVersion, int fetchLimit) {
        String cursorClause = beforeVersion == null ? "" : " WHERE version < :beforeVersion";
        DatabaseClient.GenericExecuteSpec spec = db.sql("SELECT * FROM (" + VERSION_UNION + ") versions"
                        + cursorClause + " ORDER BY version DESC LIMIT :fetchLimit")
                .bind("draftId", draftId.toString())
                .bind("fetchLimit", fetchLimit);
        if (beforeVersion != null) {
            spec = spec.bind("beforeVersion", beforeVersion);
        }
        return spec.map(CreationDraftRepository::mapVersion).all();
    }

    /** 读取指定版本；历史版本来自快照，当前版本来自当前草稿行。 */
    public Mono<CreationDraftVersion> findVersion(UUID draftId, int version) {
        return db.sql("SELECT * FROM (" + VERSION_UNION + ") versions WHERE version=:version")
                .bind("draftId", draftId.toString())
                .bind("version", version)
                .map(CreationDraftRepository::mapVersion).one();
    }

    private static CreationDraft map(Readable row) {
        return new CreationDraft(
                UUID.fromString(row.get("id", String.class)),
                row.get("owner_account_id", String.class),
                row.get("organization_id", String.class),
                row.get("title", String.class),
                DraftSourceType.fromRequest(row.get("source_type", String.class)),
                row.get("task_id", String.class),
                row.get("task_version", Integer.class),
                row.get("store_id", String.class),
                row.get("platform", String.class),
                row.get("content_form", String.class),
                row.get("topic", String.class),
                row.get("article_title", String.class),
                row.get("outline", String.class),
                row.get("content", String.class),
                DraftStatus.fromDb(row.get("status", String.class)),
                intValue(row.get("version", Integer.class), 1),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)),
                toInstant(row.get("deleted_at", OffsetDateTime.class)));
    }

    private static CreationDraftVersion mapVersion(Readable row) {
        return new CreationDraftVersion(
                UUID.fromString(row.get("draft_id", String.class)),
                intValue(row.get("version", Integer.class), 1),
                row.get("title", String.class),
                DraftSourceType.fromRequest(row.get("source_type", String.class)),
                row.get("task_id", String.class),
                row.get("task_version", Integer.class),
                row.get("store_id", String.class),
                row.get("platform", String.class),
                row.get("content_form", String.class),
                row.get("topic", String.class),
                row.get("article_title", String.class),
                row.get("outline", String.class),
                row.get("content", String.class),
                DraftStatus.fromDb(row.get("status", String.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static int intValue(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableString(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullableInt(
            DatabaseClient.GenericExecuteSpec spec, String name, Integer value) {
        return value == null ? spec.bindNull(name, Integer.class) : spec.bind(name, value);
    }
}
