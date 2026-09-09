package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 协商退出申请数据访问（任务书 #97 / D97-04）。
 *
 * <p>
 * 双向申请-响应两段式（照 {@link EngagementExtensionRepository} / V55-V56 惯例）： 任一方
 * {@code insertPending}（V59 部分唯一索引保证同一报名至多一条 pending，并发双开收敛为唯一 冲突 → 调用方 409）；对方
 * {@code respond}（pending→confirmed/rejected 的 guarded UPDATE， 0 行 = 已处理/不存在，条件
 * UPDATE 单边胜出）；发起方 {@code cancelByInitiator} 撤回 pending。 超时失效由 dispatcher 扫
 * {@code expireOverdue}（pending + 响应窗已过 → expired，合作照常继续）。
 */
@Component
public class EngagementExitRequestRepository {

	/** 协商退出申请行。 */
	public record EngagementExitRequest(String id, String applicationId, String taskId, String initiatedByAccountId,
			String initiatedRole, String reason, String status, Instant respondDeadlineAt, String respondedByAccountId,
			Instant respondedAt, Instant createdAt) {

		public boolean pending() {
			return "pending".equals(status);
		}
	}

	private static final String COLS = "id::text, application_id::text, task_id::text,"
			+ " initiated_by_account_id::text, initiated_role, reason, status, respond_deadline_at,"
			+ " responded_by_account_id::text, responded_at, created_at";

	private final DatabaseClient db;

	public EngagementExitRequestRepository(DatabaseClient db) {
		this.db = db;
	}

	/** 新建待响应申请。pending 唯一冲突 → empty（调用方 409「已有待处理的协商退出申请」）。 */
	public Mono<EngagementExitRequest> insertPending(String applicationId, String taskId, String initiatedBy,
			String initiatedRole, String reason, Instant respondDeadlineAt) {
		return db.sql("""
				INSERT INTO exit_request(id, application_id, task_id, initiated_by_account_id,
				                        initiated_role, reason, status, respond_deadline_at)
				VALUES (CAST(:id AS uuid), CAST(:app AS uuid), CAST(:task AS uuid), CAST(:by AS uuid),
				        :role, :reason, 'pending', :deadline)
				RETURNING %s
				""".formatted(COLS)).bind("id", UUID.randomUUID().toString()).bind("app", applicationId)
				.bind("task", taskId).bind("by", initiatedBy).bind("role", initiatedRole).bind("reason", reason)
				.bind("deadline", respondDeadlineAt.atOffset(java.time.ZoneOffset.UTC))
				.map(EngagementExitRequestRepository::map).one()
				.onErrorResume(io.r2dbc.spi.R2dbcDataIntegrityViolationException.class, e -> Mono.empty());
	}

	public Mono<EngagementExitRequest> findById(String id) {
		return db.sql("SELECT " + COLS + " FROM exit_request WHERE id = CAST(:id AS uuid)").bind("id", id)
				.map(EngagementExitRequestRepository::map).one();
	}

	/** 该报名当前开放申请（无则空）——互斥入口与动作契约共用。 */
	public Mono<EngagementExitRequest> findPendingByApplication(String applicationId) {
		return db
				.sql("SELECT " + COLS + " FROM exit_request"
						+ " WHERE application_id = CAST(:app AS uuid) AND status = 'pending'"
						+ " ORDER BY created_at DESC LIMIT 1")
				.bind("app", applicationId).map(EngagementExitRequestRepository::map).one();
	}

	/** 双方申请列表（新→旧）。 */
	public Flux<EngagementExitRequest> listByApplication(String applicationId) {
		return db
				.sql("SELECT " + COLS + " FROM exit_request WHERE application_id = CAST(:app AS uuid)"
						+ " ORDER BY created_at DESC")
				.bind("app", applicationId).map(EngagementExitRequestRepository::map).all();
	}

	/**
	 * 对方响应：pending → confirmed/rejected（guarded，0 行 = 已处理/不存在/响应窗已过—— 超时失效与响应的竞态由
	 * {@code respond_deadline_at > now()} 烧进 WHERE 单边胜出：dispatcher 置 expired 后本
	 * UPDATE 自然 0 行；反之响应先落定时 dispatcher 的 pending 扫描不再命中）。 responded_by
	 * 烧入响应方账号（终局审计）；confirmed 后的结算腿由领域服务在事务外幂等收敛。
	 */
	public Mono<EngagementExitRequest> respond(String id, boolean confirmed, String respondedBy) {
		return db.sql("""
				UPDATE exit_request
				SET status = :status, responded_by_account_id = CAST(:by AS uuid), responded_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'pending' AND respond_deadline_at > now()
				RETURNING %s
				""".formatted(COLS)).bind("id", id).bind("by", respondedBy)
				.bind("status", confirmed ? "confirmed" : "rejected").map(EngagementExitRequestRepository::map).one();
	}

	/** 发起方撤回：仅 pending 且本人发起（0 行 = 已处理/越权 → 调用方区分 409/403）。 */
	public Mono<EngagementExitRequest> cancelByInitiator(String id, String initiatedBy) {
		return db.sql("""
				UPDATE exit_request SET status = 'cancelled', responded_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'pending'
				  AND initiated_by_account_id = CAST(:by AS uuid)
				RETURNING %s
				""".formatted(COLS)).bind("id", id).bind("by", initiatedBy).map(EngagementExitRequestRepository::map)
				.one();
	}

	/** 终态先到（超时终结/商家取消/无责退出）：残留 pending 同事务收口为 cancelled（D97-05）。 */
	public Mono<Void> cancelPendingByApplication(String applicationId) {
		return db
				.sql("UPDATE exit_request SET status = 'cancelled', responded_at = now()"
						+ " WHERE application_id = CAST(:app AS uuid) AND status = 'pending'")
				.bind("app", applicationId).then();
	}

	/** 超时失效扫描（dispatcher）：pending 且响应窗已过 → expired。返回失效行供事件外发。 */
	public Flux<EngagementExitRequest> expireOverdue(int limit) {
		return db.sql("UPDATE exit_request SET status = 'expired', responded_at = now()"
				+ " WHERE id IN (SELECT id FROM exit_request WHERE status = 'pending'"
				+ " AND respond_deadline_at <= now() ORDER BY respond_deadline_at LIMIT :limit)" + " RETURNING " + COLS)
				.bind("limit", Math.max(1, Math.min(limit, 200))).map(EngagementExitRequestRepository::map).all();
	}

	private static EngagementExitRequest map(Readable row) {
		return new EngagementExitRequest(row.get("id", String.class), row.get("application_id", String.class),
				row.get("task_id", String.class), row.get("initiated_by_account_id", String.class),
				row.get("initiated_role", String.class), row.get("reason", String.class),
				row.get("status", String.class), instant(row, "respond_deadline_at"),
				row.get("responded_by_account_id", String.class), instant(row, "responded_at"),
				instant(row, "created_at"));
	}

	private static Instant instant(Readable row, String column) {
		java.time.OffsetDateTime value = row.get(column, java.time.OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
