package com.grassland.identity.store;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * store_membership 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，与 membership/ 风格一致）。
 * 一个账号在同一 store 至多一条（UNIQUE(store_id, account_id)）。
 */
@Component
public class StoreMembershipRepository {

    private static final String SELECT_COLS =
            "id::text, store_id::text, account_id::text, role, created_at, updated_at";

    private final DatabaseClient db;

    public StoreMembershipRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 幂等插入（ON CONFLICT DO NOTHING）：已是门店成员返回空（邀请接受视为幂等成功，不降级既有角色）。 */
    public Mono<StoreMembership> createIfAbsent(String storeId, String accountId, String role) {
        String id = UUID.randomUUID().toString();
        return db.sql("""
                INSERT INTO store_membership(id, store_id, account_id, role)
                VALUES (CAST(:id AS uuid), CAST(:store AS uuid), CAST(:acct AS uuid), :role)
                ON CONFLICT (store_id, account_id) DO NOTHING
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("store", storeId).bind("acct", accountId).bind("role", role)
                .map(StoreMembershipRepository::map).one();
    }

    public Mono<StoreMembership> create(String storeId, String accountId, String role) {
        String id = UUID.randomUUID().toString();
        return db.sql("""
                INSERT INTO store_membership(id, store_id, account_id, role)
                VALUES (CAST(:id AS uuid), CAST(:store AS uuid), CAST(:acct AS uuid), :role)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("store", storeId).bind("acct", accountId).bind("role", role)
                .map(StoreMembershipRepository::map).one();
    }

    /** 列门店成员并带账号状态（任务书 #48：UI 需展示待审/停用并给出审核入口）。 */
    public Flux<StoreMembership> findByStoreWithAccountStatus(String storeId) {
        return db.sql("""
                SELECT sm.id::text, sm.store_id::text, sm.account_id::text, sm.role,
                       sm.created_at, sm.updated_at, u.status AS account_status, n.username
                FROM store_membership sm
                LEFT JOIN app_users u ON u.id = sm.account_id
                LEFT JOIN account_username n ON n.account_id = sm.account_id
                WHERE sm.store_id = CAST(:store AS uuid)
                ORDER BY sm.created_at
                """)
                .bind("store", storeId)
                .map(StoreMembershipRepository::mapWithStatus).all();
    }

    public Flux<StoreMembership> findByStore(String storeId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM store_membership WHERE store_id = CAST(:store AS uuid) ORDER BY created_at")
                .bind("store", storeId)
                .map(StoreMembershipRepository::map).all();
    }

    /** 店内成员总数（删除门店守卫②）。 */
    public Mono<Long> countByStore(String storeId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM store_membership WHERE store_id = CAST(:store AS uuid)")
                .bind("store", storeId)
                .map(row -> row.get("c", Integer.class).longValue()).one();
    }

    public Mono<String> findRole(String storeId, String accountId) {
        return db.sql("SELECT role FROM store_membership"
                + " WHERE store_id = CAST(:store AS uuid) AND account_id = CAST(:acct AS uuid)")
                .bind("store", storeId).bind("acct", accountId)
                .map(row -> row.get("role", String.class)).one();
    }

    /**
     * 当前账号显式加入的门店范围；纯门店成员只能发现这些门店，不扩散到同组织其他门店。
     * 所属组织被平台冻结（status='suspended'）时整店不出现在范围里（任务书 #72 卡 B D3）。
     */
    public Flux<StoreAccessScope> findAccessScopesByAccount(String accountId) {
        return db.sql("""
                SELECT s.id::text AS store_id, s.name AS store_name, s.status AS store_status,
                       o.id::text AS organization_id, o.name AS organization_name,
                       o.status AS organization_status, o.permission_tier, sm.role
                FROM store_membership sm
                JOIN store s ON s.id = sm.store_id
                JOIN organization o ON o.id = s.organization_id
                WHERE sm.account_id = CAST(:acct AS uuid) AND s.deleted_at IS NULL
                  AND o.status <> 'suspended'
                ORDER BY o.name, s.name, s.id
                """)
                .bind("acct", accountId)
                .map(row -> new StoreAccessScope(
                        row.get("store_id", String.class),
                        row.get("store_name", String.class),
                        row.get("store_status", String.class),
                        row.get("organization_id", String.class),
                        row.get("organization_name", String.class),
                        row.get("organization_status", String.class),
                        row.get("permission_tier", String.class),
                        row.get("role", String.class)))
                .all();
    }

    public Mono<Long> deleteByStoreAndAccount(String storeId, String accountId) {
        return db.sql("DELETE FROM store_membership"
                + " WHERE store_id = CAST(:store AS uuid) AND account_id = CAST(:acct AS uuid)")
                .bind("store", storeId).bind("acct", accountId)
                .fetch().rowsUpdated();
    }

    public Mono<Long> countByStoreAndRole(String storeId, String role) {
        return db.sql("SELECT COUNT(*)::bigint AS c FROM store_membership"
                + " WHERE store_id = CAST(:store AS uuid) AND role = :role")
                .bind("store", storeId).bind("role", role)
                .map(row -> row.get("c", Long.class)).one();
    }

    // ---------- 任务书 #48 子账号服务支撑 ----------

    /** 目标账号在本组织全部门店的角色（含重复，供「仅有 staff」判定）。 */
    public Flux<String> findRolesByAccountInOrg(String accountId, String organizationId) {
        return db.sql("""
                SELECT sm.role FROM store_membership sm
                JOIN store s ON s.id = sm.store_id
                WHERE sm.account_id = CAST(:acct AS uuid)
                  AND s.organization_id = CAST(:org AS uuid)
                ORDER BY sm.role
                """)
                .bind("acct", accountId).bind("org", organizationId)
                .map(row -> row.get("role", String.class)).all();
    }

    /** 同上但去重（操作者判定只需要「有没有 manager」）。 */
    public Flux<String> findDistinctRolesByAccountInOrg(String accountId, String organizationId) {
        return db.sql("""
                SELECT DISTINCT sm.role FROM store_membership sm
                JOIN store s ON s.id = sm.store_id
                WHERE sm.account_id = CAST(:acct AS uuid)
                  AND s.organization_id = CAST(:org AS uuid)
                ORDER BY sm.role
                """)
                .bind("acct", accountId).bind("org", organizationId)
                .map(row -> row.get("role", String.class)).all();
    }

    /** 目标账号在本组织的门店归属是否存在（跨主体隔离判定；无任何行 → 404 口径）。 */
    public Mono<Boolean> existsByAccountAndOrganization(String accountId, String organizationId) {
        return db.sql("""
                SELECT 1 AS hit FROM store_membership sm
                JOIN store s ON s.id = sm.store_id
                WHERE sm.account_id = CAST(:acct AS uuid)
                  AND s.organization_id = CAST(:org AS uuid)
                LIMIT 1
                """)
                .bind("acct", accountId).bind("org", organizationId)
                .map(row -> true).one().hasElement();
    }

    /**
     * 一店一店长闸（#52 决策 B）：门店内已有的店长<b>关系行</b>数——不联 app_users 判状态
     * （停用中的店长仍占着店长位，须先移除/调度才能指派新店长），排除自身仅在「同店改角色」
     * 时传入。{@code excludeAccountId} 可空。
     */
    public Mono<Long> countManagerRows(String storeId, String excludeAccountId) {
        var spec = db.sql("""
                SELECT COUNT(*)::bigint AS c
                FROM store_membership sm
                WHERE sm.store_id = CAST(:store AS uuid)
                  AND sm.role = 'manager'
                  AND (:excl IS NULL OR sm.account_id <> CAST(:excl AS uuid))
                """).bind("store", storeId);
        spec = excludeAccountId == null ? spec.bindNull("excl", String.class)
                : spec.bind("excl", excludeAccountId);
        return spec.map(row -> row.get("c", Long.class)).one();
    }

    /** 解除账号在本组织内的一切门店挂靠（#52 assign-or-move 的「先解旧」半步 + 移除回池）。 */
    public Mono<Long> deleteAllByAccountInOrg(String accountId, String organizationId) {
        return db.sql("""
                DELETE FROM store_membership
                WHERE account_id = CAST(:acct AS uuid)
                  AND store_id IN (SELECT id FROM store WHERE organization_id = CAST(:org AS uuid))
                """)
                .bind("acct", accountId).bind("org", organizationId)
                .fetch().rowsUpdated();
    }

    private static StoreMembership map(Readable row) {
        return new StoreMembership(
                row.get("id", String.class),
                row.get("store_id", String.class),
                row.get("account_id", String.class),
                row.get("role", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static StoreMembership mapWithStatus(Readable row) {
        return new StoreMembership(
                row.get("id", String.class),
                row.get("store_id", String.class),
                row.get("account_id", String.class),
                row.get("role", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)),
                row.get("account_status", String.class),
                row.get("username", String.class)
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
