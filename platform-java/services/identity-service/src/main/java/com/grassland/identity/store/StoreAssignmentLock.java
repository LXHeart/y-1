package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 任务书 #103 C103-17（R08）：成员池写路径的组织级事务互斥。
 *
 * <p>
 * 锁 = 事务内 {@code SELECT id FROM organization WHERE id = :org FOR UPDATE}——
 * 同一组织行上的行锁让「创建/分配/调度/同店角色变化/移除」在真实数据库层面串行， 一店一店长的 count 前置检查只有放进这把锁内才可信（两次并发
 * count=0 不再产生双店长）。 <b>不得</b>用 JVM synchronized 代替（多副本部署无效）；锁序统一：组织父行 → 成员/门店行。
 *
 * <p>
 * 嵌套事务语义：本组件开启的事务内，调用方既有的 {@code TransactionalOperator} （默认 REQUIRED
 * 传播）参与同一事务，不会自我挂起。
 */
@Component
public class StoreAssignmentLock {

	private final DatabaseClient db;
	private final TransactionalOperator transactions;

	public StoreAssignmentLock(DatabaseClient db, TransactionalOperator transactions) {
		this.db = db;
		this.transactions = transactions;
	}

	/**
	 * 开启事务 → 锁组织父行 → 执行 work（同一事务）。 组织不存在 → 404（不能对不存在的组织拿锁）。
	 */
	public <T> Mono<T> withOrganizationLock(String organizationId, Mono<T> work) {
		return transactions.transactional(acquireInCurrentTransaction(organizationId).then(work));
	}

	/**
	 * 裸锁语句：调用方已在事务内时使用（作为该事务的第一条语句）。
	 */
	public Mono<Void> acquireInCurrentTransaction(String organizationId) {
		return db.sql("SELECT id FROM organization WHERE id = CAST(:org AS uuid) FOR UPDATE")
				.bind("org", organizationId).fetch().one()
				.switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在"))).then();
	}
}
