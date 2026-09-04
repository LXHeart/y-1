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
        // 任务书 #49 D5：建主体即生成成员账号前缀（org + id 前 8 位 hex，与 V43 回填同规则，
        // id 唯一故前缀唯一）；管理员可在主体设置里改，改后只影响新建账号。
        String accountPrefix = "org" + id.replace("-", "").substring(0, 8);
        return db.sql("""
                INSERT INTO organization(id, owner_account_id, name, status, industry, account_prefix)
                VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), :name, 'active', :industry, :prefix)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("owner", ownerAccountId).bind("name", name).bind("industry", industry)
                .bind("prefix", accountPrefix)
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

    /** 更新组织行业。调用方必须先完成行业枚举校验，并在持有组织行锁的事务内调用。 */
    public Mono<Long> updateIndustry(String id, String industry) {
        return db.sql("UPDATE organization SET industry = :industry, updated_at = now() WHERE id = CAST(:id AS uuid)")
                .bind("industry", industry).bind("id", id)
                .fetch().rowsUpdated();
    }

    /**
     * guarded 状态迁移（任务书 #72 卡 B）：只允许 {@code from→to} 单向一次写入（active↔suspended）。
     * 返回 0 = 组织不存在或当前状态不符，由调用方回查现值映射 404/409（同 app_users guardedStatusUpdate 风格）。
     */
    public Mono<Long> updateStatus(String id, String from, String to) {
        return db.sql("UPDATE organization SET status = :to, updated_at = now()"
                        + " WHERE id = CAST(:id AS uuid) AND status = :from")
                .bind("to", to).bind("id", id).bind("from", from)
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

    // ---------- 任务书 #49 D5：成员账号前缀（刻意不进 record，同 member_review_required 先例） ----------
    // #52 决策 A：member_review_required 的读写方法已随审核流退役删除（列保留，回滚友好）。

    public Mono<String> selectAccountPrefix(String organizationId) {
        return db.sql("SELECT account_prefix FROM organization WHERE id = CAST(:id AS uuid)")
                .bind("id", organizationId)
                .map(row -> row.get("account_prefix", String.class))
                .one();
    }

    /** UNIQUE 冲突（前缀被其他主体占用）由调用方映射 409。 */
    public Mono<Long> updateAccountPrefix(String organizationId, String prefix) {
        return db.sql("UPDATE organization SET account_prefix = :prefix, updated_at = now()"
                        + " WHERE id = CAST(:id AS uuid)")
                .bind("prefix", prefix).bind("id", organizationId)
                .fetch().rowsUpdated();
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
