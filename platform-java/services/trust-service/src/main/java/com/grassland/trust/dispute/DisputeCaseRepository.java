package com.grassland.trust.dispute;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * dispute_case 数据访问（草场 Epic 6 Slice 6A 受理 + 6C 审判扩字段 + 任务书 #74 小法庭重构，风格同
 * finance ReservationRepository）。
 *
 * <p>
 * {@link #createWithId} 用 partial unique(engagement_ref WHERE status<>'final')
 * 保幂等：每 engagement 至多一个<b>未终局</b>争议，
 * 中间态（open/evidence/voting/decided/appealed）持续占用活跃槽阻塞结算；终局后可再开新争议。并发违例 →
 * empty（调用方判既有）。
 *
 * <p>
 * 任务书 #74：{@code channel}（court/cs_direct）决定受理态——standard+court 直入
 * {@code evidence} 质证期并落 {@code evidence_deadline}；cs_direct 落
 * {@code cs_due_at} 停 open（卡 A）；merchant_rejection 不吃 channel 恒 open。
 */
@Component
public class DisputeCaseRepository {

	private static final String SELECT_COLS = "id::text, engagement_ref, organization_id::text, opened_by_account_id::text, opened_by_role,"
			+ " status, reason, decision, decided_at, created_at, updated_at,"
			+ " round, version, appeal_state, final_decision, final_decided_by::text, evidence_ref, kind,"
			+ " premium_support, support_priority,"
			+ " channel, cs_due_at, task_platform, claimant_done_at, respondent_done_at,"
			+ " respondent_answered, evidence_deadline, respondent_account_id::text";

	private final DatabaseClient db;

	public DisputeCaseRepository(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * 开争议（status=open）。调用方可提供确定性 id，供 deferred promotion 在一个事务内串联 successor/outbox。
	 */
	public Mono<DisputeCase> createWithId(String id, String engagementRef, String organizationId, String openedBy,
			String role, String reason, String kind) {
		return createWithId(id, engagementRef, organizationId, openedBy, role, reason, kind, false);
	}

	/** 开案时固化 accept 权益快照；premium 案进入客服队列的 100 优先级。 */
	public Mono<DisputeCase> createWithId(String id, String engagementRef, String organizationId, String openedBy,
			String role, String reason, String kind, boolean premiumSupport) {
		return createCase(id, engagementRef, organizationId, openedBy, role, reason, kind, premiumSupport, null, null,
				null, null);
	}

	/**
	 * 任务书 #74 卡 A/B：带通道受理。
	 *
	 * @param channel
	 *            court / cs_direct（null → court 存量语义）
	 * @param csDueAt
	 *            cs_direct 受理时刻 + SLA；court 恒 null
	 * @param evidenceDeadline
	 *            质证截止（standard+court：受理时刻 + 质证窗）；其余 null
	 * @param respondentAccountId
	 *            被诉方账号（merchant 开争议时 = marketplace 授权响应的推荐官；其余 null）
	 */
	public Mono<DisputeCase> createCase(String id, String engagementRef, String organizationId, String openedBy,
			String role, String reason, String kind, boolean premiumSupport, String channel, Instant csDueAt,
			Instant evidenceDeadline, String respondentAccountId) {
		String effectiveKind = (kind == null || kind.isBlank()) ? "standard" : kind;
		String effectiveChannel = (channel == null || channel.isBlank()) ? "court" : channel;
		// 受理态：merchant_rejection 恒 open（D-03 不吃通道）；standard+cs_direct 停 open（等客服，不质证）；
		// standard+court 直入 evidence 质证期（卡 B）。
		String status = "merchant_rejection".equals(effectiveKind) || "cs_direct".equals(effectiveChannel)
				? "open"
				: "evidence";
		var spec = db.sql("""
				INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id, opened_by_role,
				                         status, reason, kind, premium_support, support_priority,
				                         channel, cs_due_at, evidence_deadline, respondent_account_id)
				VALUES (CAST(:id AS uuid), :ref, CAST(:org AS uuid), CAST(:by AS uuid), :role, :status, :reason,
				        :kind, :premium, :priority, :channel, :csDue, :evidenceDeadline, CAST(:respondent AS uuid))
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("ref", engagementRef).bind("org", organizationId)
				.bind("by", openedBy).bind("role", role).bind("kind", effectiveKind).bind("premium", premiumSupport)
				.bind("priority", premiumSupport ? 100 : 0).bind("status", status).bind("channel", effectiveChannel);
		spec = bindNullableTime(spec, "csDue", csDueAt);
		spec = bindNullableTime(spec, "evidenceDeadline", evidenceDeadline);
		spec = bindNullable(spec, "reason", reason);
		spec = respondentAccountId == null
				? spec.bindNull("respondent", String.class)
				: spec.bind("respondent", respondentAccountId);
		return spec.map(DisputeCaseRepository::map).one().onErrorResume(DisputeCaseRepository::isDuplicateKey,
				e -> Mono.empty());
	}

	/**
	 * 方案 α（/api/trust/disputes/me）：该账号参与的争议（created_at 降序，活跃优先分段由前端处理）。 三路并集：我开启的 /
	 * 我所在商家组织被诉的（org 维度，openedBy≠我）/ 我是落库被诉推荐官的 （merchant 开争议时固化，recommender 无 org
	 * 故必须靠此列）。limit 上限防拉全表。
	 */
	public Flux<DisputeCase> findForAccount(String accountId, String organizationId, int limit) {
		// 注意一：不能把 "SELECT " 写进 text block 再拼 SELECT_COLS——text block 会剥行尾空格，
		// 拼出 "SELECTid::text" 坏语法（#74 修复批次引入的潜伏 bug，/disputes/me 恒 500）。
		// 注意二：org 为 null（推荐官视角）必须 bindNull——bind(name, null) 直接抛
		// IllegalArgumentException，端点恒 400（同批次的第二个潜伏 bug，商家视角 org 非空掩盖了它）。
		var spec = db.sql("SELECT " + SELECT_COLS + """
				 FROM dispute_case
				 WHERE opened_by_account_id = CAST(:account AS uuid)
				    OR respondent_account_id = CAST(:account AS uuid)
				    OR (CAST(:org AS uuid) IS NOT NULL AND organization_id = CAST(:org AS uuid)
				        AND opened_by_account_id <> CAST(:account AS uuid))
				 ORDER BY created_at DESC
				 LIMIT :limit
				""").bind("account", accountId).bind("limit", Math.max(1, Math.min(limit, 200)));
		spec = organizationId == null ? spec.bindNull("org", String.class) : spec.bind("org", organizationId);
		return spec.map(DisputeCaseRepository::map).all();
	}

	/**
	 * 开争议（status=open）。新列取默认（round=0, version=1, appeal_state='none'）。partial
	 * unique 违例 → empty（幂等：已有活跃）。 D-03 slice 2：{@code kind} 区分 standard（普通，走面板）/
	 * merchant_rejection（商家拒绝核实通过履约，直送客服）。
	 */
	public Mono<DisputeCase> create(String engagementRef, String organizationId, String openedBy, String role,
			String reason, String kind) {
		return createWithId(UUID.randomUUID().toString(), engagementRef, organizationId, openedBy, role, reason, kind);
	}

	public Mono<DisputeCase> create(String engagementRef, String organizationId, String openedBy, String role,
			String reason, String kind, boolean premiumSupport) {
		return createWithId(UUID.randomUUID().toString(), engagementRef, organizationId, openedBy, role, reason, kind,
				premiumSupport);
	}

	/**
	 * 并发竞态下 partial-unique 违例兜底（常规幂等由 controller 预查 findActive 处理）。按消息判定 duplicate
	 * key（robust）。
	 */
	private static boolean isDuplicateKey(Throwable e) {
		return e != null && e.getMessage() != null && e.getMessage().contains("duplicate key");
	}

	public Mono<DisputeCase> findById(String id) {
		return db.sql("SELECT " + SELECT_COLS + " FROM dispute_case WHERE id = CAST(:id AS uuid)").bind("id", id)
				.map(DisputeCaseRepository::map).one();
	}

	/** 锁定争议行，供投票写入与轮次关闭在同一事务中串行化。 */
	public Mono<DisputeCase> findByIdForUpdate(String id) {
		return db.sql("SELECT " + SELECT_COLS + " FROM dispute_case WHERE id = CAST(:id AS uuid) FOR UPDATE")
				.bind("id", id).map(DisputeCaseRepository::map).one();
	}

	/**
	 * 更新脱敏证据句柄（GL-P2-TRUST-001 T1）：首次提交证据时把死字段 evidence_ref 点亮，指向证据集。 仅写句柄（非
	 * raw），调用方在提交证据的<b>同一事务</b>内调用。
	 */
	public Mono<Integer> updateEvidenceRef(String id, String evidenceRef) {
		var spec = db
				.sql("UPDATE dispute_case SET evidence_ref = :ref, updated_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("id", id);
		spec = (evidenceRef == null || evidenceRef.isBlank())
				? spec.bindNull("ref", String.class)
				: spec.bind("ref", evidenceRef);
		return spec.fetch().rowsUpdated().map(Long::intValue).defaultIfEmpty(0);
	}

	/**
	 * 某 engagement 的<b>活跃</b>（未终局，status<>'final'）争议（DisputeChecker + 开争议幂等用）。无 →
	 * empty。
	 */
	public Mono<DisputeCase> findActiveByEngagementRef(String engagementRef) {
		return db.sql("SELECT " + SELECT_COLS + " FROM dispute_case WHERE engagement_ref = :ref AND status <> 'final'")
				.bind("ref", engagementRef).map(DisputeCaseRepository::map).one();
	}

	/**
	 * 某 engagement 的<b>最近终局</b>争议（GL-P2-TRUST-001：冷却期检查）。 按 decided_at 降序取第一条 final
	 * 态争议。无 → empty（调用方用 {@code defaultIfEmpty(true)} 兜底）。
	 *
	 * <p>
	 * 历史上结尾挂 {@code .defaultIfEmpty(null)}——modern Reactor 的 {@code defaultIfEmpty}
	 * 会 {@code Objects.requireNonNull} 直接抛 NPE，但冷却期在所有既有 IT 里恒被禁用，这条路径从未被执行，bug
	 * 一直潜伏（T5 启用冷却后暴露）。
	 */
	public Mono<DisputeCase> findLastFinalizedByEngagementRef(String engagementRef) {
		return db
				.sql("SELECT " + SELECT_COLS + " FROM dispute_case WHERE engagement_ref = :ref AND status = 'final'"
						+ " ORDER BY decided_at DESC NULLS LAST LIMIT 1")
				.bind("ref", engagementRef).map(DisputeCaseRepository::map).one();
	}

	/**
	 * 客服活跃争议队列：premium first；cs_direct（即将/已超 SLA）排前（任务书 #74 卡 A，按 cs_due_at 升序=
	 * 最先到期优先）；同优先级 oldest first。keyset 游标 (priority, csRank, csDueKey, createdAt,
	 * id) 与 ORDER BY 严格一致，避免翻页重复；cs_due_key 用远期哨兵替 NULL，排序/比较全程非空。
	 */
	public static final Instant CS_DUE_SENTINEL = Instant.parse("9999-12-31T00:00:00Z");

	public Flux<DisputeCase> listForSupport(int limit, Integer afterPriority, Integer afterCsRank,
			Instant afterCsDueKey, Instant afterCreatedAt, String afterId) {
		// cs_rank：cs_direct 活跃案=0（排前），其余=1。
		String base = """
				SELECT %s FROM (
				    SELECT d.*,
				           CASE WHEN d.channel = 'cs_direct' THEN 0 ELSE 1 END AS cs_rank,
				           COALESCE(d.cs_due_at, CAST('9999-12-31T00:00:00+00' AS timestamptz)) AS cs_due_key
				    FROM dispute_case d WHERE d.status <> 'final'
				) t
				""".formatted(SELECT_COLS);
		String keyset = afterPriority == null
				? ""
				: """
						WHERE (t.support_priority < :priority
						       OR (t.support_priority = :priority AND t.cs_rank > :csRank)
						       OR (t.support_priority = :priority AND t.cs_rank = :csRank AND t.cs_due_key > :csDue)
						       OR (t.support_priority = :priority AND t.cs_rank = :csRank AND t.cs_due_key = :csDue AND t.created_at > :created)
						       OR (t.support_priority = :priority AND t.cs_rank = :csRank AND t.cs_due_key = :csDue AND t.created_at = :created AND t.id > CAST(:id AS uuid)))
						""";
		var spec = db
				.sql(base + keyset
						+ " ORDER BY t.support_priority DESC, t.cs_rank, t.cs_due_key, t.created_at, t.id LIMIT :limit")
				.bind("limit", limit);
		if (afterPriority != null) {
			spec = spec.bind("priority", afterPriority).bind("csRank", afterCsRank)
					.bind("csDue", OffsetDateTime.ofInstant(afterCsDueKey, java.time.ZoneOffset.UTC))
					.bind("created", OffsetDateTime.ofInstant(afterCreatedAt, java.time.ZoneOffset.UTC))
					.bind("id", afterId);
		}
		return spec.map(DisputeCaseRepository::map).all();
	}

	/**
	 * 手动裁决（终局）：open→final，记 decision + decided_at + final_decision + version+1。0
	 * 行（非 open）→ empty。
	 */
	public Mono<DisputeCase> decide(String id, String decision) {
		return db.sql("""
				UPDATE dispute_case SET status = 'final', decision = :decision, final_decision = :decision,
				        decided_at = now(), version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'open'
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("decision", decision).map(DisputeCaseRepository::map)
				.one();
	}

	// ---------- 审判 adjudication 状态机（草场 Epic 6 Slice 6C + 任务书 #74）----------
	// 6 态 open→(evidence)→voting→decided→(appealed→)final；平票按 round
	// 重开（voting→voting 下一轮）。
	// 全部 guarded-UPDATE-with-RETURNING（风格同 decide）：仅符合前置状态时迁移并 version+1，否则 0 行 →
	// empty
	// （调用方/workflow activity 据此判幂等短路）。终态 final 解除 settlement hold（partial unique
	// 释放活跃槽）。

	/**
	 * 启动审判（open|evidence→voting，置 round，version+1；open 为存量兼容视同 evidence）。0 行 →
	 * empty。
	 */
	public Mono<DisputeCase> startAdjudication(String id, int round) {
		return db.sql("""
				UPDATE dispute_case SET status = 'voting', round = :round, version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status IN ('open', 'evidence')
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("round", round).map(DisputeCaseRepository::map).one();
	}

	/** 平票重开（voting→voting 下一轮，round+1，version+1）。0 行（非 voting）→ empty。 */
	public Mono<DisputeCase> reopen(String id, int nextRound) {
		return db.sql("""
				UPDATE dispute_case SET round = :round, version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'voting'
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("round", nextRound).map(DisputeCaseRepository::map)
				.one();
	}

	/** 记面板多数判决（voting→decided，记 decision，version+1）。0 行（非 voting）→ empty。 */
	public Mono<DisputeCase> recordDecision(String id, String decision) {
		return db.sql("""
				UPDATE dispute_case SET status = 'decided', decision = :decision, version = version + 1,
				        decided_at = now(), updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'voting'
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("decision", decision).map(DisputeCaseRepository::map)
				.one();
	}

	/** 当事方上诉（decided→appealed，appeal_state='filed'）。0 行（非 decided）→ empty。 */
	public Mono<DisputeCase> markAppealed(String id) {
		return db
				.sql("""
						UPDATE dispute_case SET status = 'appealed', appeal_state = 'filed', version = version + 1, updated_at = now()
						WHERE id = CAST(:id AS uuid) AND status = 'decided'
						RETURNING %s
						"""
						.formatted(SELECT_COLS))
				.bind("id", id).map(DisputeCaseRepository::map).one();
	}

	/**
	 * 任务书 #74 卡 F：客服发回重审（appealed→voting，round=nextRound，appeal_state 重置 none）。
	 * 资金继续 hold（回到「非 final 占槽」语义，D-06 自然延续）。0 行（非 appealed）→ empty。
	 */
	public Mono<DisputeCase> reopenForRetrial(String id, int nextRound) {
		return db.sql("""
				UPDATE dispute_case SET status = 'voting', round = :round, appeal_state = 'none',
				        version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'appealed'
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("round", nextRound).map(DisputeCaseRepository::map)
				.one();
	}

	/**
	 * 任务书 #74 卡 F：发回重审时把 dispute_appeal 落 decided + final_decision='retrial'。0 行（非
	 * filed）→ 0。
	 */
	public Mono<Integer> closeAppealForRetrial(String id) {
		return db.sql("""
				UPDATE dispute_appeal SET status = 'decided', final_decision = 'retrial', decided_at = now()
				WHERE dispute_id = CAST(:id AS uuid) AND status = 'filed'
				""").bind("id", id).fetch().rowsUpdated().map(Long::intValue).defaultIfEmpty(0);
	}

	/**
	 * 标记升级客服终审（超 maxRounds 无判决；dispute 保持 voting，appeal_state='escalated'）。0 行（非
	 * voting）→ empty。
	 */
	public Mono<DisputeCase> markEscalated(String id) {
		return db.sql("""
				UPDATE dispute_case SET appeal_state = 'escalated', version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status = 'voting'
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).map(DisputeCaseRepository::map).one();
	}

	// ---------- 质证期（任务书 #74 卡 B）----------

	/** 当事方「质证完毕」（幂等：各标志只写一次；仅在受理/质证期可标）。side=claimant/respondent。 */
	public Mono<DisputeCase> markEvidenceDone(String id, String side) {
		String column = "claimant".equals(side) ? "claimant_done_at" : "respondent_done_at";
		return db.sql("""
				UPDATE dispute_case SET %s = now(), updated_at = now()
				WHERE id = CAST(:id AS uuid) AND %s IS NULL AND status IN ('open', 'evidence')
				RETURNING %s
				""".formatted(column, column, SELECT_COLS)).bind("id", id).map(DisputeCaseRepository::map).one();
	}

	/** 被诉方答辩落库后置位（幂等）。 */
	public Mono<Void> markRespondentAnswered(String id) {
		return db.sql("""
				UPDATE dispute_case SET respondent_answered = true, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND respondent_answered = false
				""").bind("id", id).then();
	}

	/** 卡 D：落库涉案任务平台（开争议授权响应补齐时）。 */
	public Mono<Void> updateTaskPlatform(String id, String taskPlatform) {
		if (taskPlatform == null || taskPlatform.isBlank()) {
			return Mono.empty();
		}
		return db
				.sql("UPDATE dispute_case SET task_platform = :platform WHERE id = CAST(:id AS uuid)"
						+ " AND (task_platform IS NULL OR task_platform <> :platform)")
				.bind("id", id).bind("platform", taskPlatform).then();
	}

	/** dispute_appeal 是否已记录（Phase C 上诉/升级判定用）。 */
	public Mono<Boolean> hasAppeal(String id) {
		return db.sql("SELECT EXISTS(SELECT 1 FROM dispute_appeal WHERE dispute_id = CAST(:id AS uuid)) AS present")
				.bind("id", id).map(r -> r.get("present", Boolean.class)).one().defaultIfEmpty(false);
	}

	/**
	 * 任务书 #74 卡 G：dispute_appeal 终值（final_decision 为 NULL/无上诉行 → empty）。判例
	 * final_via 判定用。 mapper 不可返回 null（Reactor requireNonNull），故 COALESCE 成空串。
	 */
	public Mono<String> appealFinalDecision(String id) {
		return db.sql("SELECT COALESCE(final_decision, '') AS final_decision"
				+ " FROM dispute_appeal WHERE dispute_id = CAST(:id AS uuid)").bind("id", id).map(r -> {
					String v = r.get("final_decision", String.class);
					return v == null ? "" : v;
				}).one().filter(v -> !v.isEmpty());
	}

	/** 记录上诉（dispute_appeal，dispute_id PK 幂等：已存在 → 返回 false）。 */
	public Mono<Boolean> fileAppeal(String id, String appealedBy) {
		return db.sql("""
				INSERT INTO dispute_appeal(dispute_id, appealed_by, status)
				VALUES (CAST(:id AS uuid), CAST(:by AS uuid), 'filed')
				ON CONFLICT (dispute_id) DO NOTHING
				""").bind("id", id).bind("by", appealedBy).fetch().rowsUpdated().map(n -> n > 0).defaultIfEmpty(false);
	}

	/** 客服强制终局（CS 终审覆盖，HLD §11.2）：任意非 final 态 → final。0 行（已 final）→ empty。 */
	public Mono<DisputeCase> forceFinalize(String id, String finalDecision, String decidedBy) {
		var spec = db.sql("""
				UPDATE dispute_case SET status = 'final', final_decision = :decision,
				        final_decided_by = CAST(:by AS uuid), decided_at = now(),
				        version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status <> 'final'
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id);
		spec = bindNullable(spec, "decision", finalDecision);
		spec = bindNullableAccountId(spec, "by", decidedBy);
		return spec.map(DisputeCaseRepository::map).one();
	}

	/**
	 * 终局（decided/appealed→final，记 final_decision +
	 * final_decided_by，version+1）。客服终审/上诉窗口平淡落定。 {@code finalDecidedBy}
	 * 可空（上诉窗口平淡终局无客服）。0 行（已 final 或未到 decided/appealed）→ empty。
	 */
	public Mono<DisputeCase> finalize(String id, String finalDecision, String finalDecidedBy) {
		var spec = db.sql("""
				UPDATE dispute_case SET status = 'final', final_decision = :decision,
				        final_decided_by = CAST(:by AS uuid), decided_at = now(),
				        version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND status IN ('decided', 'appealed')
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id);
		spec = bindNullable(spec, "decision", finalDecision);
		spec = bindNullableAccountId(spec, "by", finalDecidedBy);
		return spec.map(DisputeCaseRepository::map).one();
	}

	private static DisputeCase map(Readable row) {
		return new DisputeCase(row.get("id", String.class), row.get("engagement_ref", String.class),
				row.get("organization_id", String.class), row.get("opened_by_account_id", String.class),
				row.get("opened_by_role", String.class), row.get("status", String.class),
				row.get("reason", String.class), row.get("decision", String.class),
				toInstant(row.get("decided_at", OffsetDateTime.class)),
				toInstant(row.get("created_at", OffsetDateTime.class)),
				toInstant(row.get("updated_at", OffsetDateTime.class)), row.get("round", Integer.class),
				row.get("version", Long.class), row.get("appeal_state", String.class),
				row.get("final_decision", String.class), row.get("final_decided_by", String.class),
				row.get("evidence_ref", String.class), row.get("kind", String.class),
				Boolean.TRUE.equals(row.get("premium_support", Boolean.class)),
				intValue(row.get("support_priority", Integer.class)), row.get("channel", String.class),
				toInstant(row.get("cs_due_at", OffsetDateTime.class)), row.get("task_platform", String.class),
				toInstant(row.get("claimant_done_at", OffsetDateTime.class)),
				toInstant(row.get("respondent_done_at", OffsetDateTime.class)),
				Boolean.TRUE.equals(row.get("respondent_answered", Boolean.class)),
				toInstant(row.get("evidence_deadline", OffsetDateTime.class)),
				row.get("respondent_account_id", String.class));
	}

	private static int intValue(Integer value) {
		return value == null ? 0 : value;
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}

	private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
		return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
	}

	/** timestamptz 可空绑定（null → SQL NULL）。 */
	private static GenericExecuteSpec bindNullableTime(GenericExecuteSpec spec, String name, Instant value) {
		return value == null
				? spec.bindNull(name, OffsetDateTime.class)
				: spec.bind(name, OffsetDateTime.ofInstant(value, java.time.ZoneOffset.UTC));
	}

	/** uuid 账号列可空绑定（null/blank → SQL NULL，SQL 侧 CAST(:name AS uuid)）。 */
	private static GenericExecuteSpec bindNullableAccountId(GenericExecuteSpec spec, String name, String value) {
		return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
	}
}
