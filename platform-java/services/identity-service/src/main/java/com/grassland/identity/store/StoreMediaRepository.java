package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * store_media 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，照 StoreProfileRepository 风格）。
 * 任务书 #42 Stage 2。
 *
 * <p>所有写 SQL 按 {@code (organization_id, store_id)} 双重限定（经 store 表 join 归属校验），
 * 跨组织统一查空/不落行（CLAUDE.md 第二道闸，controller 层映射 404）。
 */
@Component
public class StoreMediaRepository {

    private static final String SELECT_COLS =
            "sm.id::text, sm.organization_id::text, sm.store_id::text, sm.media_reference_id::text, "
                    + "sm.kind, sm.position, sm.mime_type, sm.size_bytes, "
                    + "sm.uploaded_by_account_id::text, sm.created_at";

    /** INSERT ... RETURNING 用：目标表无别名，列按表名限定（同 {@link #SELECT_COLS} 的列集合/列名）。 */
    private static final String RETURNING_COLS =
            "store_media.id::text, store_media.organization_id::text, store_media.store_id::text, "
                    + "store_media.media_reference_id::text, store_media.kind, store_media.position, "
                    + "store_media.mime_type, store_media.size_bytes, "
                    + "store_media.uploaded_by_account_id::text, store_media.created_at";

    private final DatabaseClient db;

    public StoreMediaRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 批量绑定媒体到门店分类（#42 D10）：position = 该 {@code (store_id, kind)} 当前
     * {@code MAX(position)+1} 递增（解绑留空洞不回补）。帽（6/12/12/3）在 INSERT 前
     * {@code count} 校验 → 409「该分类数量已达上限」；UNIQUE(store_id, media_reference_id)
     * 冲突 → 409「媒体已绑定该门店」。调用方包事务。
     *
     * <p>stats 查询前先对 store 行加锁（{@code SELECT ... FOR UPDATE}，org 双重限定）：
     * READ COMMITTED 下并发事务若无锁会各自读到旧 count/MAX 后双双放行，突破帽且 position 撞号；
     * 行锁串行化同店写，后到事务在锁释放后重读 stats，帽与 position 恒成立。
     * 查无行（跨组织/不存在）→ 404，与既有跨组织查空语义一致。{@link #insertBinding} 的
     * 404 回落仅余双保险意义。
     */
    public Flux<StoreMediaBinding> bind(String organizationId, String storeId, StoreMediaKind kind,
                                        List<NewBinding> items, String uploadedByAccountId) {
        if (items.isEmpty()) {
            return Flux.empty();
        }
        return db.sql("""
                SELECT id FROM store
                WHERE id = CAST(:store AS uuid) AND organization_id = CAST(:org AS uuid)
                FOR UPDATE
                """)
                .bind("org", organizationId)
                .bind("store", storeId)
                .map(row -> Boolean.TRUE).all().hasElements()
                .flatMapMany(storeLocked -> {
                    if (!storeLocked) {
                        return Flux.error(new IdentityException(404, "门店不存在"));
                    }
                    return db.sql("""
                            SELECT COALESCE(MAX(sm.position), 0) AS max_position, COUNT(*) AS total
                            FROM store_media sm
                            WHERE sm.store_id = CAST(:store AS uuid)
                              AND sm.kind = :kind
                            """)
                            .bind("store", storeId)
                            .bind("kind", kind.db())
                            .map(row -> new Object[]{row.get("max_position", Integer.class), row.get("total", Long.class)})
                            .one()
                            .flatMapMany(stats -> {
                                long total = (Long) stats[1];
                                if (total + items.size() > kind.maxPerStore()) {
                                    return Flux.error(new IdentityException(409, "该分类数量已达上限"));
                                }
                                int base = (Integer) stats[0];
                                List<Mono<StoreMediaBinding>> inserts = new ArrayList<>();
                                for (int i = 0; i < items.size(); i++) {
                                    inserts.add(insertBinding(organizationId, storeId, kind,
                                            items.get(i), base + i + 1, uploadedByAccountId));
                                }
                                return Flux.concat(inserts);
                            });
                });
    }

    private Mono<StoreMediaBinding> insertBinding(String organizationId, String storeId, StoreMediaKind kind,
                                                  NewBinding item, int position, String uploadedByAccountId) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO store_media(organization_id, store_id, media_reference_id, kind, position,
                    mime_type, size_bytes, uploaded_by_account_id)
                SELECT CAST(:org AS uuid), CAST(:store AS uuid), CAST(:media AS uuid), :kind, :position,
                    :mimeType, :sizeBytes, CAST(:uploadedBy AS uuid)
                FROM store s
                WHERE s.id = CAST(:store AS uuid) AND s.organization_id = CAST(:org AS uuid)
                  AND s.deleted_at IS NULL
                RETURNING %s
                """.formatted(RETURNING_COLS))
                .bind("org", organizationId)
                .bind("store", storeId)
                .bind("media", item.mediaReferenceId())
                .bind("kind", kind.db())
                .bind("position", position)
                .bind("uploadedBy", uploadedByAccountId);
        spec = item.mimeType() == null
                ? spec.bindNull("mimeType", String.class)
                : spec.bind("mimeType", item.mimeType());
        spec = item.sizeBytes() == null
                ? spec.bindNull("sizeBytes", Long.class)
                : spec.bind("sizeBytes", item.sizeBytes());
        return spec.map(StoreMediaRepository::map).one()
                .switchIfEmpty(Mono.error(new IdentityException(404, "门店不存在")))
                .onErrorMap(DataIntegrityViolationException.class,
                        error -> new IdentityException(409, "媒体已绑定该门店"));
    }

    /** 解绑：只删绑定行，不删对象本体（D6）。返回是否删除成功（未绑定 → false，controller 映射 404）。 */
    public Mono<Boolean> unbind(String organizationId, String storeId, String mediaReferenceId) {
        return db.sql("""
                DELETE FROM store_media
                USING store s
                WHERE store_media.store_id = s.id
                  AND s.organization_id = CAST(:org AS uuid)
                  AND store_media.store_id = CAST(:store AS uuid)
                  AND store_media.media_reference_id = CAST(:media AS uuid)
                """)
                .bind("org", organizationId)
                .bind("store", storeId)
                .bind("media", mediaReferenceId)
                .fetch().rowsUpdated()
                .map(updated -> updated > 0);
    }

    /**
     * 整类重排（#42 D10）：请求集合必须与该类当前绑定集合精确相等（乱序可以，缺项/多项/重复 → 409）。
     * position 按请求顺序重写为 1..n。调用方包事务。
     */
    public Mono<Void> reorder(String organizationId, String storeId, StoreMediaKind kind,
                              List<String> orderedMediaIds) {
        return db.sql("""
                SELECT sm.media_reference_id::text AS media_id
                FROM store_media sm
                INNER JOIN store s ON s.id = sm.store_id
                WHERE s.organization_id = CAST(:org AS uuid) AND sm.store_id = CAST(:store AS uuid)
                  AND sm.kind = :kind
                """)
                .bind("org", organizationId)
                .bind("store", storeId)
                .bind("kind", kind.db())
                .map(row -> row.get("media_id", String.class))
                .all()
                .collectList()
                .flatMap(current -> {
                    Set<String> currentSet = new HashSet<>(current);
                    Set<String> requestSet = new HashSet<>(orderedMediaIds);
                    if (orderedMediaIds.size() != currentSet.size()
                            || currentSet.size() != current.size()
                            || !currentSet.equals(requestSet)) {
                        return Mono.error(new IdentityException(409, "排序列表与该分类当前媒体不一致"));
                    }
                    List<Mono<Long>> updates = new ArrayList<>();
                    for (int i = 0; i < orderedMediaIds.size(); i++) {
                        updates.add(updatePosition(organizationId, storeId, kind, orderedMediaIds.get(i), i + 1));
                    }
                    return Flux.concat(updates).then();
                });
    }

    private Mono<Long> updatePosition(String organizationId, String storeId, StoreMediaKind kind,
                                         String mediaReferenceId, int position) {
        return db.sql("""
                UPDATE store_media sm
                SET position = :position
                FROM store s
                WHERE sm.store_id = s.id
                  AND s.organization_id = CAST(:org AS uuid)
                  AND s.deleted_at IS NULL
                  AND sm.store_id = CAST(:store AS uuid)
                  AND sm.kind = :kind
                  AND sm.media_reference_id = CAST(:media AS uuid)
                """)
                .bind("org", organizationId)
                .bind("store", storeId)
                .bind("kind", kind.db())
                .bind("media", mediaReferenceId)
                .bind("position", position)
                .fetch().rowsUpdated();
    }

    /** org-scoped 读取整店绑定（管理端点）：ORDER BY kind, position, created_at（D10）。 */
    public Flux<StoreMediaBinding> findByOrganizationAndStore(String organizationId, String storeId) {
        return db.sql("""
                SELECT %s
                FROM store_media sm
                INNER JOIN store s ON s.id = sm.store_id
                WHERE s.organization_id = CAST(:org AS uuid) AND sm.store_id = CAST(:store AS uuid)
                ORDER BY sm.kind, sm.position, sm.created_at
                """.formatted(SELECT_COLS))
                .bind("org", organizationId)
                .bind("store", storeId)
                .map(StoreMediaRepository::map).all();
    }

    /**
     * 公开聚合读（#42 D4/D8）：gate = store.status=active 且所属 organization.status=active
     * （照 StoreProfileRepository.findPublicProfile 的 join），无需 store_profile 行存在。
     * 不满足/无绑定 → 空 Flux（controller 区分 404 与空组靠 {@link #isPubliclyReadable}）。
     */
    public Flux<StoreMediaBinding> findPublic(String storeId) {
        return db.sql("""
                SELECT %s
                FROM store_media sm
                INNER JOIN store s ON s.id = sm.store_id
                INNER JOIN organization o ON o.id = s.organization_id
                WHERE sm.store_id = CAST(:id AS uuid) AND s.status = 'active' AND o.status = 'active' AND s.deleted_at IS NULL
                ORDER BY sm.kind, sm.position, sm.created_at
                """.formatted(SELECT_COLS))
                .bind("id", storeId)
                .map(StoreMediaRepository::map).all();
    }

    /** 公开门槛判定：门店与所属组织均 active（同 findPublic 的 gate），否则 false（controller 404）。 */
    public Mono<Boolean> isPubliclyReadable(String storeId) {
        return db.sql("""
                SELECT 1 AS gate
                FROM store s
                INNER JOIN organization o ON o.id = s.organization_id
                WHERE s.id = CAST(:id AS uuid) AND s.status = 'active' AND o.status = 'active'
                """)
                .bind("id", storeId)
                .map(row -> Boolean.TRUE).all()
                .hasElements();
    }

    private static StoreMediaBinding map(Readable row) {
        OffsetDateTime createdAt = row.get("created_at", OffsetDateTime.class);
        return new StoreMediaBinding(
                row.get("id", String.class),
                row.get("organization_id", String.class),
                row.get("store_id", String.class),
                row.get("media_reference_id", String.class),
                row.get("kind", String.class),
                row.get("position", Integer.class) == null ? 0 : row.get("position", Integer.class),
                row.get("mime_type", String.class),
                row.get("size_bytes", Long.class),
                row.get("uploaded_by_account_id", String.class),
                createdAt == null ? null : createdAt.toInstant()
        );
    }

    /** 待绑定项：mediaId + mime/size 快照（取自 intelligence 批量换 URL 返回值，fail-closed 校验后）。 */
    public record NewBinding(String mediaReferenceId, String mimeType, Long sizeBytes) {}
}
