package com.grassland.identity.organization;

import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Organization 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，与 user/ 包风格一致）。
 *
 * <p>列名 snake_case（DB）↔ camelCase（record）在 {@link #map(Row)} 转换；timestamptz 经 OffsetDateTime 转 Instant。
 */
@Component
public class OrganizationRepository {

    private static final String SELECT_COLS =
            "id::text, owner_account_id::text, name, status, permission_tier, industry, created_at, updated_at";

    private final DatabaseClient db;

    public OrganizationRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Organization> create(String ownerAccountId, String name, String industry) {
        String id = UUID.randomUUID().toString();
        return db.sql("""
                INSERT INTO organization(id, owner_account_id, name, status, industry)
                VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), :name, 'active', :industry)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("owner", ownerAccountId).bind("name", name).bind("industry", industry)
                .map(OrganizationRepository::map).one();
    }

    public Mono<Organization> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM organization WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(OrganizationRepository::map).one();
    }

    /** 主体更名生效（V40 审核流专用；调用方事务内执行）。 */
    public Mono<Long> updateName(String id, String name) {
        return db.sql("UPDATE organization SET name = :name, updated_at = now() WHERE id = CAST(:id AS uuid)")
                .bind("name", name).bind("id", id)
                .fetch().rowsUpdated();
    }

    /** 在调用方事务内锁定组织，供跨表业务流程统一串行化。 */
    public Mono<Organization> findByIdForUpdate(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM organization WHERE id = CAST(:id AS uuid) FOR UPDATE")
                .bind("id", id)
                .map(OrganizationRepository::map).one();
    }

    public Flux<Organization> findByOwner(String ownerAccountId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM organization WHERE owner_account_id = CAST(:owner AS uuid) ORDER BY created_at")
                .bind("owner", ownerAccountId)
                .map(OrganizationRepository::map).all();
    }

    /**
     * 名下主体 = **owner 或任一主体的成员**（PRD §2.1：成员属于商家主体，按角色访问主体能力）。
     * 此前只列 owner 名下——被邀请加入的主体成员拉不到列表，前端会把他们降级成
     * 「仅门店经理权限」视图（admin 也看不到品牌/成员/权限卡）。owner 条件保留：
     * 老数据的 OWNER 成员行是 best-effort 种的，可能缺席。
     */
    public Flux<Organization> findForAccount(String accountId) {
        return db.sql("SELECT o.id::text, o.owner_account_id::text, o.name, o.status,"
                        + " o.permission_tier, o.industry, o.created_at, o.updated_at FROM organization o"
                        + " WHERE o.owner_account_id = CAST(:acct AS uuid)"
                        + " OR EXISTS (SELECT 1 FROM organization_membership m"
                        + " WHERE m.organization_id = o.id AND m.account_id = CAST(:acct AS uuid))"
                        + " ORDER BY o.created_at")
                .bind("acct", accountId)
                .map(OrganizationRepository::map).all();
    }

    /**
     * 原子升级商家准入权限（Slice 2F）。只允许从低等级写到高等级；并发或陈旧审核绝不降级。
     * 返回 0 表示组织不存在，或当前等级已经达到/超过目标等级。
     */
    public Mono<Long> updatePermissionTier(String id, String tier) {
        return db.sql("""
                UPDATE organization SET permission_tier = :tier, updated_at = now()
                WHERE id = CAST(:id AS uuid)
                  AND CASE permission_tier
                        WHEN 'draft' THEN 0
                        WHEN 'basic_publish' THEN 1
                        WHEN 'finance_transaction' THEN 2
                        ELSE -1
                      END
                      < CASE :tier
                          WHEN 'draft' THEN 0
                          WHEN 'basic_publish' THEN 1
                          WHEN 'finance_transaction' THEN 2
                          ELSE -1
                        END
                """)
                .bind("tier", tier).bind("id", id)
                .fetch().rowsUpdated();
    }

    private static Organization map(Readable row) {
        return new Organization(
                row.get("id", String.class),
                row.get("owner_account_id", String.class),
                row.get("name", String.class),
                row.get("status", String.class),
                row.get("permission_tier", String.class),
                row.get("industry", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
