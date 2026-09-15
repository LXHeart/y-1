package com.grassland.marketplace.taskcatalog;

import java.util.function.BiFunction;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 合作写入统一父锁（任务书 #103 D103-01 / §4.1 规则 2）。
 *
 * <p>
 * 统一顺序 task → application：先对 task 行取 FOR SHARE（与任务级取消/状态 UPDATE 互斥），
 * 再对 task_application 行取 FOR UPDATE；work 在同一本地事务内以锁内重读的行执行。
 * 提交/审稿、里程碑确认、体验兑现、人工验收、超时终结、商家取消与无责/协商退出全部经此锁，
 * 竞争双方由数据库行锁串行化，败方在锁内看到终态后拒绝，不再发起任何资金动作。
 * 跨域 RPC 一律不在本事务内（调用方在 claim 提交后再执行/交给恢复 worker）。
 */
@Component
public class ApplicationMutationGuard {

	private final DatabaseClient db;
	private final TaskRepository tasks;
	private final TaskApplicationRepository apps;
	private final TransactionalOperator transactions;

	public ApplicationMutationGuard(DatabaseClient db, TaskRepository tasks, TaskApplicationRepository apps,
			TransactionalOperator transactions) {
		this.db = db;
		this.tasks = tasks;
		this.apps = apps;
		this.transactions = transactions;
	}

	/**
	 * 在父行锁事务内执行 work：task 重读（锁后新鲜值）、application 锁内重读；行不存在 → 空。
	 * work 抛错/返回错误 Mono → 整个事务回滚，锁内已写的任何事实一并撤销。
	 */
	public <T> Mono<T> withLockedApplication(String taskId, String applicationId,
			BiFunction<Task, TaskApplication, Mono<T>> work) {
		return transactions.transactional(
				db.sql("SELECT 1 FROM task WHERE id = CAST(:task AS uuid) FOR SHARE").bind("task", taskId)
						.fetch().rowsUpdated()
						.then(tasks.findById(taskId))
						.switchIfEmpty(Mono.error(new IllegalStateException("task row vanished: " + taskId)))
						.flatMap(task -> apps.lockById(applicationId)
								.<T>flatMap(app -> work.apply(task, app))
								.switchIfEmpty(Mono.<T>empty())));
	}
}
