package com.grassland.identity.membership;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Organization membership 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，与 organization/ 风格一致）。
 *
 * <p>鉴权热路径 {@link #findRole(String, String)} 只 SELECT role 单列；其余按完整列映射。
 */
@Component
public class MembershipRepository {

    private static final String SELECT_COLS =
            "id::text, organization_id::text, account_id::text, role, created_at, updated_at";

    private final DatabaseClient db;

    public MembershipRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Membership> create(String organizationId, String accountId, String role) {
        String id = UUID.randomUUID().toString();
        return db.sql("""
                INSERT INTO organization_membership(id, organization_id, account_id, role)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:acct AS uuid), :role)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId).bind("acct", accountId).bind("role", role)
                .map(MembershipRepository::map).one();
    }

    /**
     * 幂等建成员（Slice 7C-2）：{@code ON CONFLICT DO NOTHING}——已存在时返回<b>空 Mono</b>（无异常），
     * 供调用方做「已是成员」幂等判定。与 {@link #create} 的区别：后者用于会因重复报 409 的路径（org 侧加成员），
     * 本方法用于「已成员视为成功」的邀请接受——避免在 R2DBC 事务内捕获 {@code DataIntegrityViolation}
     * （被捕获的 INSERT 失败会把事务置 rollback-only）。
     */
    public Mono<Membership> createIfAbsent(String organizationId, String accountId, String role) {
        String id = UUID.randomUUID().toString();
        return db.sql("""
                INSERT INTO organization_membership(id, organization_id, account_id, role)
                VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:acct AS uuid), :role)
                ON CONFLICT (organization_id, account_id) DO NOTHING
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("org", organizationId).bind("acct", accountId).bind("role", role)
                .map(MembershipRepository::map).one();
    }

    /** 列组织成员并带账号状态（任务书 #48：UI 展示停用态与恢复入口）。 */
    public Flux<Membership> findByOrganizationWithAccountStatus(String organizationId) {
        return db.sql("""
                SELECT m.id::text, m.organization_id::text, m.account_id::text, m.role,
                       m.created_at, m.updated_at, u.status AS account_status, n.username,
                       a.store_id::text, a.store_role, a.store_name
                FROM organization_membership m
                LEFT JOIN app_users u ON u.id = m.account_id
                LEFT JOIN account_username n ON n.account_id = m.account_id
                LEFT JOIN LATERAL (
                    SELECT sm.store_id::text AS store_id, sm.role AS store_role, s.name AS store_name
                    FROM store_membership sm
                    JOIN store s ON s.id = sm.store_id
                    WHERE sm.account_id = m.account_id
                      AND s.organization_id = m.organization_id
                      AND s.deleted_at IS NULL
                    ORDER BY sm.created_at
                    LIMIT 1
                ) a ON TRUE
                WHERE m.organization_id = CAST(:org AS uuid)
                ORDER BY m.created_at
                """)
                .bind("org", organizationId)
                .map(MembershipRepository::mapWithStatus).all();
    }

    public Flux<Membership> findByOrganization(String organizationId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM organization_membership WHERE organization_id = CAST(:org AS uuid) ORDER BY created_at")
                .bind("org", organizationId)
                .map(MembershipRepository::map).all();
    }

    /** 返回账号当前所属的全部组织 ID；identity 是该关系的唯一权威来源。 */
    public Flux<String> findOrganizationIdsByAccount(String accountId) {
        return db.sql("SELECT organization_id::text AS organization_id FROM organization_membership"
                        + " WHERE account_id = CAST(:acct AS uuid) ORDER BY organization_id")
                .bind("acct", accountId)
                .map(row -> row.get("organization_id", String.class)).all();
    }

    /**
     * 列账号的组织访问范围（本人视角）：成员表行 + owner_account_id 兜底，角色解析与
     * {@link OrgAuthorization#roleOfAccount} 同口径（成员表优先）。供 /api/me/organization-scopes。
     */
    public Flux<OrganizationAccessScope> findScopesByAccount(String accountId) {
        return db.sql("""
                SELECT o.id::text AS organization_id, o.name AS organization_name,
                       o.status AS organization_status, o.permission_tier,
                       COALESCE(m.role, CASE WHEN o.owner_account_id = CAST(:acct AS uuid)
                           THEN 'owner' END) AS role
                FROM organization o
                LEFT JOIN organization_membership m
                    ON m.organization_id = o.id AND m.account_id = CAST(:acct AS uuid)
                WHERE m.id IS NOT NULL OR o.owner_account_id = CAST(:acct AS uuid)
                ORDER BY o.created_at
                """)
                .bind("acct", accountId)
                .map((Readable row) -> new OrganizationAccessScope(
                        row.get("organization_id", String.class),
                        row.get("organization_name", String.class),
                        row.get("organization_status", String.class),
                        row.get("permission_tier", String.class),
                        row.get("role", String.class)))
                .all();
    }

    /** 鉴权热路径：返回该账号在 org 的 role，不存在返回空 Mono。 */
    public Mono<String> findRole(String organizationId, String accountId) {
        return db.sql("SELECT role FROM organization_membership"
                + " WHERE organization_id = CAST(:org AS uuid) AND account_id = CAST(:acct AS uuid)")
                .bind("org", organizationId).bind("acct", accountId)
                .map(row -> row.get("role", String.class)).one();
    }

    public Mono<Long> deleteByOrganizationAndAccount(String organizationId, String accountId) {
        return db.sql("DELETE FROM organization_membership"
                + " WHERE organization_id = CAST(:org AS uuid) AND account_id = CAST(:acct AS uuid)")
                .bind("org", organizationId).bind("acct", accountId)
                .fetch().rowsUpdated();
    }

    public Mono<Long> countByOrganizationAndRole(String organizationId, String role) {
        return db.sql("SELECT COUNT(*)::bigint AS c FROM organization_membership"
                + " WHERE organization_id = CAST(:org AS uuid) AND role = :role")
                .bind("org", organizationId).bind("role", role)
                .map(row -> row.get("c", Long.class)).one();
    }

    private static Membership map(Readable row) {
        return new Membership(
                row.get("id", String.class),
                row.get("organization_id", String.class),
                row.get("account_id", String.class),
                row.get("role", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Membership mapWithStatus(Readable row) {
        return new Membership(
                row.get("id", String.class),
                row.get("organization_id", String.class),
                row.get("account_id", String.class),
                row.get("role", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)),
                row.get("account_status", String.class),
                row.get("username", String.class),
                row.get("store_id", String.class),
                row.get("store_role", String.class),
                row.get("store_name", String.class)
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
