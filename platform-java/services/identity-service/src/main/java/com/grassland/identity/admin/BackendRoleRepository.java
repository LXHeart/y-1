package com.grassland.identity.admin;

import com.grassland.identity.assertion.BackendRole;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 后台角色数据访问（GL-P2-ADMIN-001）。
 *
 * <p>读多值 {@code backend_role} 表（V26）；{@code app_users.role} 仅作为旧系统兼容投影，
 * grant/revoke 会在同一事务中锁定账号行、变更角色并重算该投影。
 * identity 是 account 权威，{@code findByAccountId} 供 edge-bff 组装断言 role claim + 本地 requireRole 判定。
 */
@Component
public class BackendRoleRepository {

    private final DatabaseClient db;
    private final TransactionalOperator transactions;

    public BackendRoleRepository(DatabaseClient db, TransactionalOperator transactions) {
        this.db = db;
        this.transactions = transactions;
    }

    /** 某账号持有的全部后台角色（按授予时间排序）。 */
    public Mono<java.util.Set<BackendRole>> findByAccountId(String accountId) {
        return db.sql("SELECT role FROM backend_role WHERE account_id = CAST(:acct AS uuid) ORDER BY granted_at")
                .bind("acct", accountId)
                .map(row -> row.get("role", String.class))
                .all()
                .map(BackendRole::fromDb)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    /** 授予角色（幂等：已存在则更新 granted_at/granted_by）。 */
    public Mono<Void> grant(String accountId, BackendRole role, String grantedBy) {
        Mono<Void> mutation = db.sql("""
                INSERT INTO backend_role(account_id, role, granted_by)
                VALUES (CAST(:acct AS uuid), :role, CAST(:by AS uuid))
                ON CONFLICT (account_id, role) DO UPDATE
                    SET granted_at = now(), granted_by = EXCLUDED.granted_by
                """)
                .bind("acct", accountId)
                .bind("role", role.dbValue())
                .bind("by", UUID.fromString(grantedBy))
                .then();
        return mutateAndRefreshProjection(accountId, mutation);
    }

    /** 撤销角色（不存在则 no-op）。 */
    public Mono<Void> revoke(String accountId, BackendRole role) {
        Mono<Void> mutation = db.sql("""
                DELETE FROM backend_role
                 WHERE account_id = CAST(:acct AS uuid) AND role = :role
                """)
                .bind("acct", accountId)
                .bind("role", role.dbValue())
                .then();
        return mutateAndRefreshProjection(accountId, mutation);
    }

    private Mono<Void> mutateAndRefreshProjection(String accountId, Mono<Void> mutation) {
        Mono<Void> lockedMutation = db.sql("""
                SELECT id FROM app_users WHERE id = CAST(:acct AS uuid) FOR UPDATE
                """)
                .bind("acct", accountId)
                .fetch()
                .rowsUpdated()
                .then(mutation)
                .then(db.sql("""
                        UPDATE app_users
                           SET role = CASE
                               WHEN EXISTS (SELECT 1 FROM backend_role
                                            WHERE account_id = CAST(:acct AS uuid)
                                              AND role = 'platform_admin') THEN 'admin'
                               WHEN EXISTS (SELECT 1 FROM backend_role
                                            WHERE account_id = CAST(:acct AS uuid)
                                              AND role = 'customer_service') THEN 'customer_service'
                               ELSE 'user'
                           END
                         WHERE id = CAST(:acct AS uuid)
                        """)
                        .bind("acct", accountId)
                        .then());
        return transactions.transactional(lockedMutation);
    }
}
