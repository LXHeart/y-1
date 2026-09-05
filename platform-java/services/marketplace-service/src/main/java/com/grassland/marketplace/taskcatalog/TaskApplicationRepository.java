package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * task_application 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，风格同
 * {@link TaskRepository}）。草场 Epic 4 Slice 4B（4F 资金预留中间态）。
 *
 * <p>
 * 状态变更用条件 UPDATE（{@code status = :from} 守卫，泛化自 4B 的硬编码 {@code 'pending'}）+
 * {@code RETURNING}： 0 行 → {@link Mono#empty()}（调用方据此判 409「已处理」或幂等跳过）。4F 新增
 * reserving 流转：
 * {@code beginAcceptance}（pending→reserving）、{@code acceptFromReserving}（reserving→accepted，不重写
 * reviewer）、 {@code revertReserving}（reserving→pending 补偿，清空
 * reviewer/decided_at 回可重试态）。并发名额由 {@link TaskAcceptanceCounterRepository}
 * 在同一事务内控制。
 */
@Component
public class TaskApplicationRepository {

	private static final String SELECT_COLS = "id::text, task_id::text, recommender_account_id::text, status, note,"
			+ " reviewed_by_account_id::text, decided_at, created_at, updated_at, confirmed_at, bounty_cents,"
			+ " merchant_confirm_deadline_at, auto_confirmed_at, merchant_rejected_at, rejection_reason,"
			+ " merchant_rejection_dispute_id::text, contest_requested_at, rejection_workflow_started_at,"
			+ " reputation_level_at_accept, reputation_policy_version_at_accept,"
			+ " settlement_delay_days_at_accept, commission_bonus_bps_at_accept, premium_support_at_accept,"
			+ " confirmed_metric_value, freebie_deposit_cents";

	private final DatabaseClient db;

	public TaskApplicationRepository(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * 报名（status=pending）。note 可空。{@code bountyCents} = 报名时 task
	 * 赏金（provisional；accept 时才冻结）； {@code freebieDepositCents} 同为 provisional
	 * 押金快照（ADR-D12）。UNIQUE(task,recommender) 违例 → empty（调用方判 409「已报名」）。
	 */
	public Mono<TaskApplication> create(String taskId, String recommenderAccountId, String note, long bountyCents,
			long freebieDepositCents) {
		String id = UUID.randomUUID().toString();
		var spec = db.sql("""
				INSERT INTO task_application(id, task_id, recommender_account_id, status, note, bounty_cents,
				                             freebie_deposit_cents)
				VALUES (CAST(:id AS uuid), CAST(:taskId AS uuid), CAST(:rec AS uuid), 'pending', :note, :bounty,
				        :freebieDeposit)
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId).bind("rec", recommenderAccountId)
				.bind("bounty", bountyCents).bind("freebieDeposit", freebieDepositCents);
		spec = bindNullable(spec, "note", note);
		return spec.map(TaskApplicationRepository::map).one().onErrorResume(R2dbcDataIntegrityViolationException.class,
				e -> Mono.empty());
	}

	public Mono<TaskApplication> findById(String id) {
		return db.sql("SELECT " + SELECT_COLS + " FROM task_application WHERE id = CAST(:id AS uuid)").bind("id", id)
				.map(TaskApplicationRepository::map).one();
	}

	/**
	 * Frozen task contract captured by the database on pending/reserving ->
	 * accepted.
	 */
	public Mono<String> findTaskContextSnapshot(String id) {
		return db.sql("SELECT task_context_snapshot::text AS snapshot FROM task_application WHERE id=CAST(:id AS uuid)")
				.bind("id", id).map(row -> row.get("snapshot", String.class)).one();
	}

	/** 某 recommender 在某 task 的现存报名（去重预查；UNIQUE 保证至多一行）。 */
	public Mono<TaskApplication> findByTaskAndRecommender(String taskId, String recommenderAccountId) {
		return db
				.sql("SELECT " + SELECT_COLS + " FROM task_application WHERE task_id = CAST(:taskId AS uuid)"
						+ " AND recommender_account_id = CAST(:rec AS uuid)")
				.bind("taskId", taskId).bind("rec", recommenderAccountId).map(TaskApplicationRepository::map).one();
	}

	public Flux<TaskApplication> findByTaskId(String taskId) {
		return db
				.sql("SELECT " + SELECT_COLS
						+ " FROM task_application WHERE task_id = CAST(:taskId AS uuid) ORDER BY created_at, id")
				.bind("taskId", taskId).map(TaskApplicationRepository::map).all();
	}

	/**
	 * 推荐官「我的报名」跨任务列表（任务书 #29+#30 Stage 2）：按 recommender 过滤 + keyset 游标分页， join
	 * {@code task} 取标题/状态/平台（#77 卡 D 补 platform；门店名/城市由 controller 经
	 * TaskStoreEnrichment 批量增强——门店资料在 identity 侧，本地无 store 表）。
	 *
	 * <p>
	 * 排序 {@code (created_at DESC, id DESC)} 稳定，游标语义同
	 * {@code TaskRepository.findFeed}； {@code cursorTs} 为 null 时首页（不带 keyset 谓词，避免
	 * null/值混合绑定歧义）。 {@code settledAt} 取该报名最近一条 {@code EngagementSettled} outbox
	 * 事件时间（无则 null）。 #77 卡 D：{@code statuses} 多值 IN 过滤（单值 =
	 * 单元素列表，向后兼容）；{@code settled} 非空时按 LATERAL join 出的 settled_at 有无过滤（join 语义不变，仅
	 * WHERE 补条件）。 仅绑定 SQL 中实际出现的命名参数（r2dbc 对不存在的标识符 bind 会抛
	 * NoSuchElementException）。
	 */
	public Flux<MyApplicationRow> findMyApplications(String recommenderAccountId, List<String> statuses,
			Boolean settled, Instant cursorTs, String cursorId, int limit) {
		boolean firstPage = cursorTs == null;
		boolean filterStatuses = statuses != null && !statuses.isEmpty();
		StringBuilder sql = new StringBuilder("""
				SELECT a.id::text AS application_id, a.task_id::text AS task_id,
				       t.title AS task_title, t.status AS task_status, t.platform,
				       t.store_id::text AS store_id,
				       a.status AS application_status, a.bounty_cents,
				       a.created_at AS applied_at, settled.settled_at
				FROM task_application a
				JOIN task t ON t.id = a.task_id
				LEFT JOIN LATERAL (
				    SELECT created_at AS settled_at FROM marketplace_outbox
				    WHERE event_type = 'EngagementSettled' AND aggregate_id = a.id::text
				    ORDER BY created_at DESC, id DESC LIMIT 1
				) settled ON true
				WHERE a.recommender_account_id = CAST(:rec AS uuid)
				""");
		if (filterStatuses) {
			sql.append(" AND a.status IN (:statuses)");
		}
		if (settled != null) {
			sql.append(settled ? " AND settled.settled_at IS NOT NULL" : " AND settled.settled_at IS NULL");
		}
		if (!firstPage) {
			sql.append(" AND (a.created_at, a.id) < (CAST(:cursorTs AS timestamptz), CAST(:cursorId AS uuid))");
		}
		sql.append(" ORDER BY a.created_at DESC, a.id DESC LIMIT :limit");
		var spec = db.sql(sql.toString()).bind("rec", recommenderAccountId).bind("limit",
				Math.max(1, Math.min(limit, 100)));
		if (filterStatuses) {
			spec = spec.bind("statuses", statuses);
		}
		if (!firstPage) {
			spec = spec.bind("cursorTs", cursorTs.atOffset(java.time.ZoneOffset.UTC)).bind("cursorId", cursorId);
		}
		return spec.map(TaskApplicationRepository::mapMyApplication).all();
	}

	/**
	 * 「我的报名」投影行：application + join task 的展示字段 + settledAt（#77 卡 D 补
	 * platform/storeId）。
	 */
	public record MyApplicationRow(String applicationId, String taskId, String taskTitle, String taskStatus,
			String applicationStatus, long bountyCents, Instant appliedAt, Instant settledAt, String platform,
			String storeId) {
	}

	private static MyApplicationRow mapMyApplication(Readable row) {
		return new MyApplicationRow(row.get("application_id", String.class), row.get("task_id", String.class),
				row.get("task_title", String.class), row.get("task_status", String.class),
				row.get("application_status", String.class), longValue(row.get("bounty_cents", Long.class)),
				toInstant(row.get("applied_at", OffsetDateTime.class)),
				toInstant(row.get("settled_at", OffsetDateTime.class)), row.get("platform", String.class),
				row.get("store_id", String.class));
	}

	/**
	 * Filtered application list for merchant operations; null filters preserve the
	 * legacy query semantics.
	 */
	public Flux<TaskApplication> findByTaskId(String taskId, String status, Instant createdAfter, Instant createdBefore,
			int limit) {
		StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLS)
				.append(" FROM task_application WHERE task_id = CAST(:taskId AS uuid)");
		if (status != null && !status.isBlank())
			sql.append(" AND status = :status");
		if (createdAfter != null)
			sql.append(" AND created_at >= :createdAfter");
		if (createdBefore != null)
			sql.append(" AND created_at < :createdBefore");
		sql.append(" ORDER BY created_at, id LIMIT :limit");
		var spec = db.sql(sql.toString()).bind("taskId", taskId).bind("limit", Math.max(1, Math.min(limit, 500)));
		if (status != null && !status.isBlank())
			spec = spec.bind("status", status);
		if (createdAfter != null)
			spec = spec.bind("createdAfter", createdAfter.atOffset(java.time.ZoneOffset.UTC));
		if (createdBefore != null)
			spec = spec.bind("createdBefore", createdBefore.atOffset(java.time.ZoneOffset.UTC));
		return spec.map(TaskApplicationRepository::map).all();
	}

	/**
	 * 接受（4B 直连路径）：pending → accepted，记录操作商家 + decided_at，<b>并冻结 accept 时赏金到
	 * bounty_cents</b> （snapshot-pinning：此后结算读这列而非可变 task 行）。0 行（非 pending / 不属该
	 * task）→ empty。
	 */
	public Mono<TaskApplication> accept(String id, String taskId, String reviewerAccountId, long bountyCents,
			ReputationEntitlementSnapshot entitlement) {
		return db.sql("""
				UPDATE task_application
				SET status = :status, reviewed_by_account_id = CAST(:reviewer AS uuid),
				    decided_at = now(), bounty_cents = :bounty, updated_at = now(),
				    reputation_level_at_accept = :level,
				    reputation_policy_version_at_accept = :policyVersion,
				    settlement_delay_days_at_accept = :settlementDays,
				    commission_bonus_bps_at_accept = :commissionBps,
				    premium_support_at_accept = :premiumSupport
				WHERE id = CAST(:id AS uuid)
				  AND task_id = CAST(:taskId AS uuid)
				  AND status = :from
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId)
				.bind("from", ApplicationStatus.PENDING.dbValue()).bind("status", ApplicationStatus.ACCEPTED.dbValue())
				.bind("reviewer", reviewerAccountId).bind("bounty", bountyCents).bind("level", entitlement.level())
				.bind("policyVersion", entitlement.policyVersion())
				.bind("settlementDays", entitlement.settlementDelayDays())
				.bind("commissionBps", entitlement.commissionBonusBps())
				.bind("premiumSupport", entitlement.premiumSupport()).map(TaskApplicationRepository::map).one();
	}

	/**
	 * 结算确认（Slice 5A）：accepted + 未确认 → 设 confirmed_at（商家 ConfirmEngagement）。0 行（非
	 * accepted / 已确认）→ empty。 商家身份由上游 loadOwnedTask 校验为 task owner，故不另存
	 * confirmed_by。
	 * <p>
	 * D-03：窗口到期自动结算复用本方法（{@code ConfirmationActivityImpl} 调）——条件
	 * {@code confirmed_at IS NULL} 保证「商家先确认 vs 自动结算」竞态只有一方落 confirmed_at，另一方 0
	 * 行→abort，无双结算。
	 */
	public Mono<TaskApplication> confirm(String id, String taskId) {
		return confirm(id, taskId, null);
	}

	/** D-02：商家手动确认可同时冻结申报的阶梯指标达成值（与 confirmed_at 同一 guarded UPDATE，此后不可变）。 */
	public Mono<TaskApplication> confirm(String id, String taskId, Long confirmedMetricValue) {
		var spec = db.sql("""
				UPDATE task_application SET confirmed_at = now(), updated_at = now(), confirmed_metric_value = :metric
				WHERE id = CAST(:id AS uuid)
				  AND task_id = CAST(:taskId AS uuid)
				  AND status = 'accepted'
				  AND confirmed_at IS NULL
				  AND contest_requested_at IS NULL
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId);
		spec = confirmedMetricValue == null
				? spec.bindNull("metric", Long.class)
				: spec.bind("metric", confirmedMetricValue);
		return spec.map(TaskApplicationRepository::map).one();
	}

	/**
	 * F6 contest 门闩：在任何 trust/Temporal 出站调用前先原子落 durable intent。 与
	 * {@link #confirm}/{@link #autoConfirm} 更新同一 application 行；提交后赢家唯一，输家谓词复查为 0 行。
	 */
	public Mono<TaskApplication> claimContest(String id, String taskId, String reason) {
		GenericExecuteSpec spec = db.sql("""
				UPDATE task_application
				SET contest_requested_at = now(), rejection_reason = :reason, updated_at = now()
				WHERE id = CAST(:id AS uuid)
				  AND task_id = CAST(:taskId AS uuid)
				  AND status = 'accepted'
				  AND confirmed_at IS NULL
				  AND contest_requested_at IS NULL
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId);
		spec = bindNullable(spec, "reason", reason);
		return spec.map(TaskApplicationRepository::map).one();
	}

	/**
	 * 完成已 claim 的 contest：写 trust 案号与资金 reconciliation 所需 confirmed_at。 只允许 durable
	 * claim 推进；同一 dispute 重试返回当前行，不允许 Timer auto-confirm 后再接管。
	 */
	public Mono<TaskApplication> completeContest(String id, String taskId, String disputeId) {
		return db
				.sql("""
						UPDATE task_application
						SET confirmed_at = COALESCE(confirmed_at, now()), merchant_rejected_at = COALESCE(merchant_rejected_at, now()),
						    merchant_rejection_dispute_id = CAST(:dispute AS uuid), updated_at = now()
						WHERE id = CAST(:id AS uuid)
						  AND task_id = CAST(:taskId AS uuid)
						  AND status = 'accepted'
						  AND contest_requested_at IS NOT NULL
						  AND auto_confirmed_at IS NULL
						  AND (merchant_rejection_dispute_id IS NULL
						       OR merchant_rejection_dispute_id = CAST(:dispute AS uuid))
						RETURNING %s
						"""
						.formatted(SELECT_COLS))
				.bind("id", id).bind("taskId", taskId).bind("dispute", disputeId).map(TaskApplicationRepository::map)
				.one();
	}

	/** 待恢复 contest：已 claim，但 trust 案/本地完成/SLA 启动任一步未完成。 */
	public Flux<TaskApplication> findContestDispatchable(int limit) {
		return db
				.sql("SELECT " + SELECT_COLS + " FROM task_application" + " WHERE contest_requested_at IS NOT NULL"
						+ " AND (merchant_rejected_at IS NULL OR rejection_workflow_started_at IS NULL)"
						+ " ORDER BY contest_requested_at LIMIT :limit")
				.bind("limit", Math.max(1, limit)).map(TaskApplicationRepository::map).all();
	}

	/** 固定 workflow 启动成功后的 guarded 标记；重复派发/AlreadyStarted 均幂等。 */
	public Mono<Boolean> markRejectionWorkflowStarted(String id, String disputeId) {
		return db.sql("""
				UPDATE task_application
				SET rejection_workflow_started_at = COALESCE(rejection_workflow_started_at, now()), updated_at = now()
				WHERE id = CAST(:id AS uuid)
				  AND merchant_rejection_dispute_id = CAST(:dispute AS uuid)
				  AND rejection_workflow_started_at IS NULL
				""").bind("id", id).bind("dispute", disputeId).fetch().rowsUpdated().map(n -> n > 0)
				.defaultIfEmpty(false);
	}

	/**
	 * 窗口到期自动确认（D-03）：accepted + 未确认 → 同时设 confirmed_at / auto_confirmed_at。 0 行 =
	 * 商家先手动确认 / 非 accepted。{@code auto_confirmed_at} 支撑 activity 崩溃重试：重试见它非空可继续
	 * capture， 仅 confirmed_at 非空则说明商家先确认，本 workflow abort。
	 */
	public Mono<TaskApplication> autoConfirm(String id, String taskId) {
		return db.sql("""
				UPDATE task_application
				SET confirmed_at = now(), auto_confirmed_at = now(), updated_at = now()
				WHERE id = CAST(:id AS uuid)
				  AND task_id = CAST(:taskId AS uuid)
				  AND status = 'accepted'
				  AND confirmed_at IS NULL
				  AND contest_requested_at IS NULL
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId).map(TaskApplicationRepository::map)
				.one();
	}

	/**
	 * 设商家确认窗口截止（D-03）：推荐官提交履约时调，deadline = now() + windowSeconds（DB 算，避免绑时间戳）。 0
	 * 行（属该 task 的报名不存在）→ empty。
	 */
	public Mono<TaskApplication> setConfirmDeadline(String id, String taskId, long windowSeconds) {
		return db.sql("""
				UPDATE task_application
				SET merchant_confirm_deadline_at = now() + (:seconds * interval '1 second'), updated_at = now()
				WHERE id = CAST(:id AS uuid)
				  AND task_id = CAST(:taskId AS uuid)
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId)
				.bind("seconds", Math.max(0, windowSeconds)).map(TaskApplicationRepository::map).one();
	}

	/** 拒绝：pending → rejected。 */
	public Mono<TaskApplication> reject(String id, String taskId, String reviewerAccountId) {
		return transition(id, taskId, ApplicationStatus.PENDING.dbValue(), ApplicationStatus.REJECTED.dbValue(),
				reviewerAccountId);
	}

	/**
	 * 开始接受（Slice 4F Saga beginAcceptance）：pending → reserving，记录操作商家 + decided_at。
	 * 同时以 claim 时金额刷新 provisional 快照（ADR-D12：apply 时写入的值可能已被修订覆盖，claim-time 权威；
	 * activateEngagement 按 refresh 后的行值冻结，规避 claim→Saga 窗口内的模式翻转竞态）。0 行 → empty。
	 */
	public Mono<TaskApplication> beginAcceptance(String id, String taskId, String reviewerAccountId,
			ReputationEntitlementSnapshot entitlement, long bountyCents, long freebieDepositCents) {
		return db.sql("""
				UPDATE task_application
				SET status = :status, reviewed_by_account_id = CAST(:reviewer AS uuid), decided_at = now(),
				    bounty_cents = :bounty, freebie_deposit_cents = :freebieDeposit,
				    reputation_level_at_accept = :level,
				    reputation_policy_version_at_accept = :policyVersion,
				    settlement_delay_days_at_accept = :settlementDays,
				    commission_bonus_bps_at_accept = :commissionBps,
				    premium_support_at_accept = :premiumSupport,
				    updated_at = now()
				WHERE id = CAST(:id AS uuid) AND task_id = CAST(:taskId AS uuid) AND status = :from
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId)
				.bind("from", ApplicationStatus.PENDING.dbValue()).bind("status", ApplicationStatus.RESERVING.dbValue())
				.bind("reviewer", reviewerAccountId).bind("bounty", bountyCents)
				.bind("freebieDeposit", freebieDepositCents).bind("level", entitlement.level())
				.bind("policyVersion", entitlement.policyVersion())
				.bind("settlementDays", entitlement.settlementDelayDays())
				.bind("commissionBps", entitlement.commissionBonusBps())
				.bind("premiumSupport", entitlement.premiumSupport()).map(TaskApplicationRepository::map).one();
	}

	public Mono<TaskApplication> beginAcceptance(String id, String taskId, String reviewerAccountId,
			ReputationEntitlementSnapshot entitlement) {
		return db.sql("""
				UPDATE task_application
				SET status = :status, reviewed_by_account_id = CAST(:reviewer AS uuid), decided_at = now(),
				    reputation_level_at_accept = :level,
				    reputation_policy_version_at_accept = :policyVersion,
				    settlement_delay_days_at_accept = :settlementDays,
				    commission_bonus_bps_at_accept = :commissionBps,
				    premium_support_at_accept = :premiumSupport,
				    updated_at = now()
				WHERE id = CAST(:id AS uuid) AND task_id = CAST(:taskId AS uuid) AND status = :from
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId)
				.bind("from", ApplicationStatus.PENDING.dbValue()).bind("status", ApplicationStatus.RESERVING.dbValue())
				.bind("reviewer", reviewerAccountId).bind("level", entitlement.level())
				.bind("policyVersion", entitlement.policyVersion())
				.bind("settlementDays", entitlement.settlementDelayDays())
				.bind("commissionBps", entitlement.commissionBonusBps())
				.bind("premiumSupport", entitlement.premiumSupport()).map(TaskApplicationRepository::map).one();
	}

	/**
	 * Rolling-upgrade compatibility path. The current controller freezes the
	 * snapshot before starting the workflow, but an older caller may reach this
	 * transition with no snapshot after V21 has installed its completeness CHECK.
	 * Preserve any frozen values; otherwise atomically apply the same conservative
	 * Lv1 baseline used by V21 backfill.
	 */
	public Mono<TaskApplication> beginAcceptance(String id, String taskId, String reviewerAccountId) {
		return db.sql("""
				UPDATE task_application
				SET status = :status, reviewed_by_account_id = CAST(:reviewer AS uuid), decided_at = now(),
				    reputation_level_at_accept = COALESCE(reputation_level_at_accept, 1),
				    reputation_policy_version_at_accept = COALESCE(reputation_policy_version_at_accept, 1),
				    settlement_delay_days_at_accept = COALESCE(settlement_delay_days_at_accept, 2),
				    commission_bonus_bps_at_accept = COALESCE(commission_bonus_bps_at_accept, 0),
				    premium_support_at_accept = COALESCE(premium_support_at_accept, false),
				    updated_at = now()
				WHERE id = CAST(:id AS uuid) AND task_id = CAST(:taskId AS uuid) AND status = :from
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId)
				.bind("from", ApplicationStatus.PENDING.dbValue()).bind("status", ApplicationStatus.RESERVING.dbValue())
				.bind("reviewer", reviewerAccountId).map(TaskApplicationRepository::map).one();
	}

	public Mono<TaskApplication> snapshotPendingEntitlement(String id, String taskId,
			ReputationEntitlementSnapshot entitlement) {
		return db.sql("""
				UPDATE task_application
				SET reputation_level_at_accept = :level,
				    reputation_policy_version_at_accept = :policyVersion,
				    settlement_delay_days_at_accept = :settlementDays,
				    commission_bonus_bps_at_accept = :commissionBps,
				    premium_support_at_accept = :premiumSupport,
				    updated_at = now()
				WHERE id = CAST(:id AS uuid) AND task_id = CAST(:taskId AS uuid) AND status = 'pending'
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId).bind("level", entitlement.level())
				.bind("policyVersion", entitlement.policyVersion())
				.bind("settlementDays", entitlement.settlementDelayDays())
				.bind("commissionBps", entitlement.commissionBonusBps())
				.bind("premiumSupport", entitlement.premiumSupport()).map(TaskApplicationRepository::map).one();
	}

	/**
	 * 激活（Slice 4F Saga activate）：reserving → accepted，<b>冻结 accept
	 * 时赏金与押金</b>（snapshot-pinning，ADR-D12 D7）。 不重写
	 * reviewer/decided_at（beginAcceptance 已记录）。0 行（非 reserving）→ empty（幂等：重试或已变迁）。
	 */
	public Mono<TaskApplication> acceptFromReserving(String id, String taskId, long bountyCents,
			long freebieDepositCents) {
		return db.sql("""
				UPDATE task_application SET status = :status, bounty_cents = :bounty,
				        freebie_deposit_cents = :freebieDeposit, updated_at = now()
				WHERE id = CAST(:id AS uuid)
				  AND task_id = CAST(:taskId AS uuid)
				  AND status = :from
				  AND reputation_level_at_accept IS NOT NULL
				  AND reputation_policy_version_at_accept IS NOT NULL
				  AND settlement_delay_days_at_accept IS NOT NULL
				  AND commission_bonus_bps_at_accept IS NOT NULL
				  AND premium_support_at_accept IS NOT NULL
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId).bind("bounty", bountyCents)
				.bind("freebieDeposit", freebieDepositCents).bind("from", ApplicationStatus.RESERVING.dbValue())
				.bind("status", ApplicationStatus.ACCEPTED.dbValue()).map(TaskApplicationRepository::map).one();
	}

	/**
	 * 补偿回退（Slice 4F Saga compensate）：reserving → pending，清空
	 * reviewer/decided_at（回可重试态）。 0 行（非 reserving）→ empty（幂等：重试或已回退）。
	 */
	public Mono<TaskApplication> revertReserving(String id, String taskId) {
		return db.sql("""
				UPDATE task_application
				SET status = :status, reviewed_by_account_id = NULL, decided_at = NULL,
				    reputation_level_at_accept = NULL,
				    reputation_policy_version_at_accept = NULL,
				    settlement_delay_days_at_accept = NULL,
				    commission_bonus_bps_at_accept = NULL,
				    premium_support_at_accept = NULL,
				    updated_at = now()
				WHERE id = CAST(:id AS uuid)
				  AND task_id = CAST(:taskId AS uuid)
				  AND status = :from
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId)
				.bind("from", ApplicationStatus.RESERVING.dbValue()).bind("status", ApplicationStatus.PENDING.dbValue())
				.map(TaskApplicationRepository::map).one();
	}

	/**
	 * 撤销：本人 pending → withdrawn（无 reviewer）。WHERE 含 recommender 即资源级自查（HLD 7.4）。
	 */
	public Mono<TaskApplication> withdraw(String id, String taskId, String recommenderAccountId) {
		return db.sql("""
				UPDATE task_application SET status = 'withdrawn', updated_at = now()
				WHERE id = CAST(:id AS uuid)
				  AND task_id = CAST(:taskId AS uuid)
				  AND recommender_account_id = CAST(:rec AS uuid)
				  AND status = 'pending'
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId).bind("rec", recommenderAccountId)
				.map(TaskApplicationRepository::map).one();
	}

	/** 已被接受的名额计数（名额控制用）。 */
	public Mono<Integer> countAcceptedByTask(String taskId) {
		return db
				.sql("SELECT COUNT(*)::int AS c FROM task_application"
						+ " WHERE task_id = CAST(:taskId AS uuid) AND status = 'accepted'")
				.bind("taskId", taskId).map(r -> r.get("c", Integer.class)).one();
	}

	/**
	 * 已报名成功的计数（PRD §2.3 修订守卫）：accepted + reserving——reserving 是资金预留中的
	 * 在途态，对外即「报名成功」（预留失败会补偿回滚，但修订入口必须在途即冻结）。
	 */
	public Mono<Integer> countAcceptedOrReservingByTask(String taskId) {
		return db
				.sql("SELECT COUNT(*)::int AS c FROM task_application"
						+ " WHERE task_id = CAST(:taskId AS uuid) AND status IN ('accepted', 'reserving')")
				.bind("taskId", taskId).map(r -> r.get("c", Integer.class)).one();
	}

	/**
	 * D-03 §5：某任务下「已 accept 但未提交凭证」的报名（商家 cancel 时全额返还商家；已提交/核实的照常结算）。 NOT EXISTS
	 * 子查询排除有 engagement_submission 的报名。
	 */
	public Flux<TaskApplication> findAcceptedByTaskWithoutSubmission(String taskId) {
		return db
				.sql("SELECT " + SELECT_COLS + " FROM task_application a"
						+ " WHERE task_id = CAST(:taskId AS uuid) AND status = 'accepted'"
						+ " AND NOT EXISTS (SELECT 1 FROM engagement_submission s WHERE s.application_id = a.id)")
				.bind("taskId", taskId).map(TaskApplicationRepository::map).all();
	}

	/**
	 * 商家取消任务后把「已 accept 未提交凭证」的 engagement 置终态 refunded（D-03 §5）。
	 *
	 * <p>
	 * 前置条件写进 WHERE：status='accepted' 且确无 submission —— 与
	 * {@code SubmissionRepository.create} 的 {@code FOR SHARE OF t} 一起保证不会与并发提交交叉。已
	 * refunded → 返回空（cancel 重试幂等）。
	 */
	public Mono<TaskApplication> markRefunded(String id, String taskId) {
		return db.sql("""
				UPDATE task_application a
				SET status = 'refunded', decided_at = COALESCE(decided_at, now()), updated_at = now()
				WHERE a.id = CAST(:id AS uuid)
				  AND a.task_id = CAST(:taskId AS uuid)
				  AND a.status = 'accepted'
				  AND NOT EXISTS (SELECT 1 FROM engagement_submission s WHERE s.application_id = a.id)
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId).map(TaskApplicationRepository::map)
				.one();
	}

	private Mono<TaskApplication> transition(String id, String taskId, String fromStatus, String toStatus,
			String reviewerAccountId) {
		return db.sql("""
				UPDATE task_application
				SET status = :status, reviewed_by_account_id = CAST(:reviewer AS uuid),
				    decided_at = now(), updated_at = now()
				WHERE id = CAST(:id AS uuid)
				  AND task_id = CAST(:taskId AS uuid)
				  AND status = :from
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("taskId", taskId).bind("from", fromStatus)
				.bind("status", toStatus).bind("reviewer", reviewerAccountId).map(TaskApplicationRepository::map).one();
	}

	private static TaskApplication map(Readable row) {
		return new TaskApplication(row.get("id", String.class), row.get("task_id", String.class),
				row.get("recommender_account_id", String.class), row.get("status", String.class),
				row.get("note", String.class), row.get("reviewed_by_account_id", String.class),
				toInstant(row.get("decided_at", OffsetDateTime.class)),
				toInstant(row.get("created_at", OffsetDateTime.class)),
				toInstant(row.get("updated_at", OffsetDateTime.class)),
				toInstant(row.get("confirmed_at", OffsetDateTime.class)),
				longValue(row.get("bounty_cents", Long.class)),
				toInstant(row.get("merchant_confirm_deadline_at", OffsetDateTime.class)),
				toInstant(row.get("auto_confirmed_at", OffsetDateTime.class)),
				toInstant(row.get("merchant_rejected_at", OffsetDateTime.class)),
				row.get("rejection_reason", String.class), row.get("merchant_rejection_dispute_id", String.class),
				toInstant(row.get("contest_requested_at", OffsetDateTime.class)),
				toInstant(row.get("rejection_workflow_started_at", OffsetDateTime.class)),
				row.get("reputation_level_at_accept", Integer.class),
				row.get("reputation_policy_version_at_accept", Long.class),
				row.get("settlement_delay_days_at_accept", Integer.class),
				row.get("commission_bonus_bps_at_accept", Integer.class),
				row.get("premium_support_at_accept", Boolean.class), row.get("confirmed_metric_value", Long.class),
				longValue(row.get("freebie_deposit_cents", Long.class)));
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private static long longValue(Long raw) {
		return raw == null ? 0L : raw;
	}

	private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
		return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
	}
}
