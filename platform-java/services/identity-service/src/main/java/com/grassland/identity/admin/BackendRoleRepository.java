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
 * <p>
 * 读多值 {@code backend_role} 表（V26）；{@code app_users.role} 仅作为旧系统兼容投影，
 * grant/revoke 会在同一事务中锁定账号行、变更角色并重算该投影。 identity 是 account
 * 权威，{@code findByAccountId} 供 edge-bff 组装断言 role claim + 本地 requireRole 判定。
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
				.bind("acct", accountId).map(row -> row.get("role", String.class)).all().map(BackendRole::fromDb)
				.filter(java.util.Objects::nonNull).collect(Collectors.toCollection(java.util.LinkedHashSet::new));
	}

	/**
	 * 批量取多账号后台角色（任务书 #94 D94-10：admin 用户列表单次 IN 查询，消除逐账号 N+1）。 入参按 ≤500 分批（防超长
	 * IN）；空入参 → empty。返回行不含无角色账号（调用方按缺失 = 无角色处理）。
	 */
	public Flux<AccountRole> findByAccountIds(java.util.Collection<String> accountIds) {
		if (accountIds == null || accountIds.isEmpty()) {
			return Flux.empty();
		}
		java.util.List<String> ids = java.util.List.copyOf(new java.util.LinkedHashSet<>(accountIds));
		java.util.List<java.util.List<String>> batches = new java.util.ArrayList<>();
		for (int start = 0; start < ids.size(); start += 500) {
			batches.add(ids.subList(start, Math.min(start + 500, ids.size())));
		}
		return Flux.fromIterable(batches)
				.flatMap(batch -> db.sql("""
						SELECT account_id::text AS acct, role FROM backend_role
						WHERE account_id = ANY(CAST(:ids AS uuid[]))
						ORDER BY account_id, granted_at
						""").bind("ids", batch.toArray(String[]::new))
						.map(row -> new AccountRole(row.get("acct", String.class),
								BackendRole.fromDb(row.get("role", String.class))))
						.all().filter(accountRole -> accountRole.role() != null));
	}

	/** 批量查询结果行。 */
	public record AccountRole(String accountId, BackendRole role) {
	}

	/** 授予角色（幂等：已存在则更新 granted_at/granted_by）。 */
	public Mono<Void> grant(String accountId, BackendRole role, String grantedBy) {
		Mono<Void> mutation = db.sql("""
				INSERT INTO backend_role(account_id, role, granted_by)
				VALUES (CAST(:acct AS uuid), :role, CAST(:by AS uuid))
				ON CONFLICT (account_id, role) DO UPDATE
				    SET granted_at = now(), granted_by = EXCLUDED.granted_by
				""").bind("acct", accountId).bind("role", role.dbValue()).bind("by", UUID.fromString(grantedBy)).then();
		return mutateAndRefreshProjection(accountId, mutation);
	}

	/** 撤销角色（不存在则 no-op）。 */
	public Mono<Void> revoke(String accountId, BackendRole role) {
		Mono<Void> mutation = db.sql("""
				DELETE FROM backend_role
				 WHERE account_id = CAST(:acct AS uuid) AND role = :role
				""").bind("acct", accountId).bind("role", role.dbValue()).then();
		return mutateAndRefreshProjection(accountId, mutation);
	}

	private Mono<Void> mutateAndRefreshProjection(String accountId, Mono<Void> mutation) {
		Mono<Void> lockedMutation = db.sql("""
				SELECT id FROM app_users WHERE id = CAST(:acct AS uuid) FOR UPDATE
				""").bind("acct", accountId).fetch().rowsUpdated().then(mutation).then(db.sql("""
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
				""").bind("acct", accountId).then());
		return transactions.transactional(lockedMutation);
	}
}
