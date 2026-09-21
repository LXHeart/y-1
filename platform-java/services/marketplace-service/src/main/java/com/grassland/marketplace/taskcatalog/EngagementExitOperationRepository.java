package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.taskcatalog.EngagementExitOperation.EngagementExitFundLeg;
import com.grassland.marketplace.taskcatalog.EngagementExitOperation.ExitAmounts;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 退出资金操作数据访问（任务书 #103 C103-02/C03）。插入发生在父行锁事务内（claim 的一部分）；
 * advance/requeue（C03）经租约推进。经济键 = Finance 原动作名 + engagementRef（applicationId），
 * 与 FinanceEscrowClient 现有幂等键同源，不为同一次 capture/release 发明第二个财务键。
 */
@Component
public class EngagementExitOperationRepository {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** 经济键三腿前缀：Finance 动作名 + engagementRef（§6.2）。 */
	private static String legKey(String legKind, String engagementRef) {
		return switch (legKind) {
			case "deposit_refund" -> "freebie-refund:" + engagementRef;
			case "bounty_capture" -> "reservation-capture:" + engagementRef;
			case "bounty_release" -> "reservation-release:" + engagementRef;
			default -> throw new IllegalArgumentException("unknown leg kind: " + legKind);
		};
	}

	private final DatabaseClient db;

	public EngagementExitOperationRepository(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * claim 事务内写入操作与三腿（含零腿 not_required）。同 application 已有操作时回读既有行，
	 * 并核对经济事实摘要（kind/task/组织/三腿金额）；不一致 → idempotency_conflict 语义错误 （唯一冲突不吞成成功）。
	 */
	public Mono<EngagementExitOperation> insertOrRead(String taskId, String applicationId, String organizationId,
			String kind, String exitRequestId, Integer contractVersion, Map<String, Object> settlementSnapshot,
			ExitAmounts amounts, String payeeAccountId) {
		String operationId = UUID.randomUUID().toString();
		Map<String, Object> snapshot = new LinkedHashMap<>(settlementSnapshot);
		snapshot.put("payeeAccountId", payeeAccountId);
		snapshot.put("depositRefundCents", amounts.depositRefundCents());
		snapshot.put("bountyCaptureCents", amounts.bountyCaptureCents());
		snapshot.put("bountyReleaseCents", amounts.bountyReleaseCents());
		String snapshotJson;
		try {
			snapshotJson = MAPPER.writeValueAsString(snapshot);
		} catch (Exception e) {
			return Mono.error(new IllegalStateException("exit settlement snapshot encode failed", e));
		}
		String depositKey = legKey("deposit_refund", applicationId);
		String captureKey = legKey("bounty_capture", applicationId);
		String releaseKey = legKey("bounty_release", applicationId);
		String captureParamsHash = Integer.toHexString(
				Map.of("payee", payeeAccountId == null ? "" : payeeAccountId, "capture", amounts.bountyCaptureCents())
						.hashCode());
		var spec = db
				.sql("""
						WITH inserted AS (
						    INSERT INTO engagement_exit_operation(id, application_id, task_id, organization_id, kind,
						        exit_request_id, contract_version, settlement_snapshot, state)
						    VALUES (CAST(:id AS uuid), CAST(:app AS uuid), CAST(:task AS uuid), CAST(:org AS uuid), :kind,
						        CAST(:exitRequest AS uuid), :contractVersion, CAST(:snapshot AS jsonb), 'pending')
						    ON CONFLICT (application_id) DO NOTHING
						    RETURNING id
						), legs AS (
						    INSERT INTO engagement_exit_fund_leg(operation_id, leg_kind, economic_key, amount_cents, state, params_hash)
						    SELECT (SELECT id FROM inserted), kind, key, amount,
						           CASE WHEN amount > 0 THEN 'pending' ELSE 'not_required' END, NULL
						    FROM (VALUES
						        ('deposit_refund', CAST(:depositKey AS varchar), CAST(:depositCents AS bigint)),
						        ('bounty_capture', CAST(:captureKey AS varchar), CAST(:captureCents AS bigint)),
						        ('bounty_release', CAST(:releaseKey AS varchar), CAST(:releaseCents AS bigint))
						    ) AS legs(kind, key, amount)
						    WHERE EXISTS (SELECT 1 FROM inserted)
						    ON CONFLICT (operation_id, leg_kind) DO NOTHING
						)
						SELECT 1
						""")
				.bind("id", operationId).bind("app", applicationId).bind("task", taskId).bind("org", organizationId)
				.bind("kind", kind).bind("snapshot", snapshotJson).bind("depositKey", depositKey)
				.bind("depositCents", amounts.depositRefundCents()).bind("captureKey", captureKey)
				.bind("captureCents", amounts.bountyCaptureCents()).bind("releaseKey", releaseKey)
				.bind("releaseCents", amounts.bountyReleaseCents());
		spec = exitRequestId == null
				? spec.bindNull("exitRequest", java.util.UUID.class)
				: spec.bind("exitRequest", java.util.UUID.fromString(exitRequestId));
		spec = contractVersion == null
				? spec.bindNull("contractVersion", Integer.class)
				: spec.bind("contractVersion", contractVersion);
		return spec.fetch().rowsUpdated().then(findByApplication(applicationId)).flatMap(existing -> {
			if (existing.id().equals(operationId)) {
				return Mono.just(existing);
			}
			return verifyEconomicFacts(existing, taskId, organizationId, kind, amounts);
		});
	}

	/** 既有操作的经济事实核对：同键不同事实 → 冲突（409 语义），同事实 → 幂等回读。 */
	private Mono<EngagementExitOperation> verifyEconomicFacts(EngagementExitOperation existing, String taskId,
			String organizationId, String kind, ExitAmounts amounts) {
		boolean sameFacts = existing.taskId().equals(taskId) && existing.organizationId().equals(organizationId)
				&& existing.kind().equals(kind)
				&& existing.legs().stream().filter(leg -> "deposit_refund".equals(leg.legKind()))
						.allMatch(leg -> leg.amountCents() == amounts.depositRefundCents())
				&& existing.legs().stream().filter(leg -> "bounty_capture".equals(leg.legKind()))
						.allMatch(leg -> leg.amountCents() == amounts.bountyCaptureCents())
				&& existing.legs().stream().filter(leg -> "bounty_release".equals(leg.legKind()))
						.allMatch(leg -> leg.amountCents() == amounts.bountyReleaseCents());
		return sameFacts
				? Mono.just(existing)
				: Mono.error(new IllegalStateException(
						"exit operation idempotency conflict for application " + existing.applicationId()));
	}

	public Mono<EngagementExitOperation> findByApplication(String applicationId) {
		return db.sql(SELECT + " WHERE o.application_id = CAST(:app AS uuid)").bind("app", applicationId)
				.map(this::mapOperation).one().flatMap(this::withLegs);
	}

	public Mono<EngagementExitOperation> findById(String operationId) {
		return db.sql(SELECT + " WHERE o.id = CAST(:id AS uuid)").bind("id", operationId).map(this::mapOperation).one()
				.flatMap(this::withLegs);
	}

	private Mono<EngagementExitOperation> withLegs(EngagementExitOperation operation) {
		return Flux
				.from(db.sql("SELECT operation_id::text, leg_kind, economic_key, amount_cents, state,"
						+ " finance_reference, verified_at FROM engagement_exit_fund_leg"
						+ " WHERE operation_id = CAST(:op AS uuid) ORDER BY leg_kind").bind("op", operation.id())
						.map(EngagementExitOperationRepository::mapLeg).all())
				.collectList()
				.map(legs -> new EngagementExitOperation(operation.id(), operation.applicationId(), operation.taskId(),
						operation.organizationId(), operation.kind(), operation.exitRequestId(),
						operation.businessVersion(), operation.contractVersion(), operation.settlementSnapshot(),
						operation.state(), operation.attempts(), operation.nextAttemptAt(), operation.version(),
						operation.lastErrorCode(), operation.leaseExpiresAt(), operation.createdAt(),
						operation.updatedAt(), operation.completedAt(), legs));
	}

	private static final String SELECT = """
			SELECT o.id::text, o.application_id::text, o.task_id::text, o.organization_id::text, o.kind,
			       o.exit_request_id::text, o.business_version, o.contract_version, o.settlement_snapshot,
			       o.state, o.attempts, o.next_attempt_at, o.version, o.last_error_code, o.lease_expires_at,
			       o.created_at, o.updated_at, o.completed_at
			FROM engagement_exit_operation o
			""";

	private EngagementExitOperation mapOperation(Readable r) {
		Map<String, Object> snapshot = Map.of();
		String raw = r.get("settlement_snapshot", String.class);
		if (raw != null) {
			try {
				snapshot = MAPPER.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
				});
			} catch (Exception ignored) {
				// 快照损坏按空读模型处理；权威金额在腿行上，展示层另行降级。
			}
		}
		return new EngagementExitOperation(r.get("id", String.class), r.get("application_id", String.class),
				r.get("task_id", String.class), r.get("organization_id", String.class), r.get("kind", String.class),
				r.get("exit_request_id", String.class),
				r.get("business_version", Long.class) == null ? 0 : r.get("business_version", Long.class),
				r.get("contract_version", Integer.class), snapshot, r.get("state", String.class),
				r.get("attempts", Integer.class) == null ? 0 : r.get("attempts", Integer.class),
				toInstant(r.get("next_attempt_at", OffsetDateTime.class)),
				r.get("version", Long.class) == null ? 0 : r.get("version", Long.class),
				r.get("last_error_code", String.class), toInstant(r.get("lease_expires_at", OffsetDateTime.class)),
				toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)),
				toInstant(r.get("completed_at", OffsetDateTime.class)), List.of());
	}

	private static EngagementExitFundLeg mapLeg(Readable r) {
		return new EngagementExitFundLeg(r.get("operation_id", String.class), r.get("leg_kind", String.class),
				r.get("economic_key", String.class),
				r.get("amount_cents", Long.class) == null ? 0 : r.get("amount_cents", Long.class),
				r.get("state", String.class), r.get("finance_reference", String.class),
				toInstant(r.get("verified_at", OffsetDateTime.class)));
	}

	private static Instant toInstant(OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}

	static List<String> legKeysFor(String applicationId) {
		List<String> keys = new ArrayList<>();
		for (String kind : List.of("deposit_refund", "bounty_capture", "bounty_release")) {
			keys.add(legKey(kind, applicationId));
		}
		return keys;
	}

	static String legKeyOf(String legKind, String applicationId) {
		return legKey(legKind, applicationId);
	}

	/** C03 恢复扫描：待推进操作（pending/到点 retry_wait）有界批量。 */
	public Flux<EngagementExitOperation> findRecoverable(Instant now, int limit) {
		return db
				.sql(SELECT + " WHERE (o.state IN ('pending', 'retry_wait')"
						+ " AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= :now))"
						+ " OR (o.state = 'processing' AND o.lease_expires_at <= :now)"
						+ " ORDER BY o.next_attempt_at NULLS FIRST, o.created_at, o.id" + " LIMIT :limit")
				.bind("now", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC)).bind("limit", Math.max(1, limit))
				.map(this::mapOperation).all().concatMap(this::withLegs);
	}

	/**
	 * 租约领取（C103-03 / §7.2）：pending/到点 retry_wait 且无有效租约 → processing + 租约字段 +
	 * version+1。 已被他人持有且未过期 → empty（其他 worker 不重复领取）。写失败必须匹配 lease_token。
	 */
	public Mono<EngagementExitOperation> claimLease(String operationId, String leaseOwner, UUID leaseToken, Instant now,
			int leaseSeconds, int maxAttempts) {
		OffsetDateTime nowDb = OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC);
		OffsetDateTime expiresAt = OffsetDateTime.ofInstant(now.plusSeconds(Math.max(1, leaseSeconds)),
				java.time.ZoneOffset.UTC);
		// 新领取：pending/到点 retry_wait 且无有效租约。
		Mono<Long> claim = db.sql("""
				UPDATE engagement_exit_operation
				SET state = 'processing', lease_owner = :owner, lease_token = :token,
				    lease_expires_at = :expiresAt, attempts = attempts + 1, version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid)
				  AND state IN ('pending', 'retry_wait')
				  AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
				  AND (lease_expires_at IS NULL OR lease_expires_at <= :now)
				  AND attempts < :maxAttempts
				""").bind("id", operationId).bind("owner", leaseOwner).bind("token", leaseToken)
				.bind("expiresAt", expiresAt).bind("now", nowDb).bind("maxAttempts", Math.max(1, maxAttempts)).fetch()
				.rowsUpdated();
		// 停机接管：processing 但租约已过期（原 worker 崩溃）。旧执行者的失败不能覆盖新租约（token 校验）。
		Mono<Long> takeover = db.sql("""
				UPDATE engagement_exit_operation
				SET lease_owner = :owner, lease_token = :token, lease_expires_at = :expiresAt,
				    attempts = attempts + 1, version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid)
				  AND state = 'processing'
				  AND lease_expires_at <= :now
				  AND attempts < :maxAttempts
				""").bind("id", operationId).bind("owner", leaseOwner).bind("token", leaseToken)
				.bind("expiresAt", expiresAt).bind("now", nowDb).bind("maxAttempts", Math.max(1, maxAttempts)).fetch()
				.rowsUpdated();
		Mono<Long> exhausted = db.sql("""
				UPDATE engagement_exit_operation
				SET state = 'needs_review', lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
				    next_attempt_at = NULL, last_error_code = 'attempts_exhausted',
				    version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND attempts >= :maxAttempts
				  AND state IN ('pending', 'retry_wait', 'processing')
				  AND (lease_expires_at IS NULL OR lease_expires_at <= :now)
				""").bind("id", operationId).bind("maxAttempts", Math.max(1, maxAttempts)).bind("now", nowDb).fetch()
				.rowsUpdated();
		return exhausted.flatMap(n -> n > 0
				? findById(operationId)
				: claim.flatMap(m -> m > 0
						? findById(operationId)
						: takeover.flatMap(t -> t > 0 ? findById(operationId) : Mono.empty())));
	}

	/** 腿成功落定（成功腿不可回退/不可变更金额——仅 pending/unknown 可写）。 */
	public Mono<Boolean> markLegSucceeded(String operationId, String legKind, String financeReference, Instant now) {
		return db.sql("""
				UPDATE engagement_exit_fund_leg
				SET state = 'succeeded', finance_reference = :ref, verified_at = :verifiedAt, updated_at = now()
				WHERE operation_id = CAST(:op AS uuid) AND leg_kind = :kind AND state IN ('pending', 'unknown')
				""").bind("op", operationId).bind("kind", legKind).bind("ref", financeReference)
				.bind("verifiedAt", OffsetDateTime.ofInstant(now, java.time.ZoneOffset.UTC)).fetch().rowsUpdated()
				.map(n -> n > 0).defaultIfEmpty(false);
	}

	/** 腿进入待核对（口径冲突/重试耗尽）：同事务置操作 needs_review，需 FINANCE 回读后重排。 */
	public Mono<Boolean> markNeedsReview(String operationId, String legKind, String errorCode, UUID leaseToken) {
		return db.sql("""
				UPDATE engagement_exit_fund_leg
				SET state = 'needs_review', updated_at = now()
				WHERE operation_id = CAST(:op AS uuid) AND leg_kind = :kind AND state <> 'succeeded'
				""").bind("op", operationId).bind("kind", legKind).fetch().rowsUpdated().then(db.sql("""
				UPDATE engagement_exit_operation
				SET state = 'needs_review', lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
				    last_error_code = :err, version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND lease_token = :token AND state = 'processing'
				""").bind("id", operationId).bind("err", errorCode).bind("token", leaseToken).fetch().rowsUpdated())
				.map(n -> n > 0).defaultIfEmpty(false);
	}

	/** 暂时失败/结果待核实：退避重试（§7.2：60s 起倍增至 3600s 封顶），释放租约。 */
	public Mono<Boolean> scheduleRetry(String operationId, String errorCode, int attempts, UUID leaseToken,
			long backoffBaseSeconds, long backoffMaxSeconds) {
		long delay = Math.min(backoffMaxSeconds, backoffBaseSeconds * (1L << Math.min(6, Math.max(0, attempts - 1))));
		return db.sql("""
				UPDATE engagement_exit_operation
				SET state = 'retry_wait', next_attempt_at = now() + (:delay * interval '1 second'),
				    lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
				    last_error_code = :err, version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND lease_token = :token AND state = 'processing'
				""").bind("id", operationId).bind("err", errorCode).bind("token", leaseToken)
				.bind("delay", Math.max(1, delay)).fetch().rowsUpdated().map(n -> n > 0).defaultIfEmpty(false);
	}

	/** 全部必需腿落定 → 操作成功收口（completed_at 一次写入）。 */
	public Mono<Boolean> completeSucceeded(String operationId, UUID leaseToken) {
		return db.sql("""
				UPDATE engagement_exit_operation
				SET state = 'succeeded', completed_at = now(), lease_owner = NULL, lease_token = NULL,
				    lease_expires_at = NULL, next_attempt_at = NULL, version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND lease_token = :token AND state = 'processing'
				  AND NOT EXISTS (SELECT 1 FROM engagement_exit_fund_leg l
				                  WHERE l.operation_id = engagement_exit_operation.id
				                    AND l.state NOT IN ('succeeded', 'not_required'))
				""").bind("id", operationId).bind("token", leaseToken).fetch().rowsUpdated().map(n -> n > 0)
				.defaultIfEmpty(false);
	}

	/** 运营重排（§6.2 retry）：expectedVersion 乐观校验，仅原键重新排队；已成功 → false（调用方回读 200）。 */
	public Mono<EngagementExitOperation> requeue(String operationId, long expectedVersion, String reason) {
		return db.sql("""
				UPDATE engagement_exit_operation
				SET state = CASE WHEN state = 'succeeded' THEN state ELSE 'pending' END,
				    next_attempt_at = NULL, lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
				    attempts = CASE WHEN state = 'needs_review' THEN 0 ELSE attempts END,
				    last_error_code = :reason, version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND version = :expectedVersion AND state <> 'succeeded'
				RETURNING id
				""").bind("id", operationId).bind("expectedVersion", expectedVersion).bind("reason", reason).fetch()
				.rowsUpdated().flatMap(n -> n > 0 ? findById(operationId) : Mono.empty());
	}

	/** 腿执行前先落 unknown（网络结果未知时保留核实态，不吞成失败/成功）。 */
	public Mono<Boolean> markLegUnknown(String operationId, String legKind) {
		return db.sql("""
				UPDATE engagement_exit_fund_leg
				SET state = 'unknown', updated_at = now()
				WHERE operation_id = CAST(:op AS uuid) AND leg_kind = :kind AND state = 'pending'
				""").bind("op", operationId).bind("kind", legKind).fetch().rowsUpdated().map(n -> n > 0)
				.defaultIfEmpty(false);
	}

	/** 治理台队列：state/applicationId 过滤 + (updated_at, id) keyset 游标，limit 1~100。 */
	public Flux<EngagementExitOperation> findPage(String state, String applicationId, String cursor, int limit) {
		StringBuilder sql = new StringBuilder(SELECT + " WHERE 1=1");
		java.util.List<Object> params = new java.util.ArrayList<>();
		if (state != null && !state.isBlank()) {
			sql.append(" AND o.state = :state");
		}
		if (applicationId != null && !applicationId.isBlank()) {
			sql.append(" AND o.application_id = CAST(:app AS uuid)");
		}
		if (cursor != null && !cursor.isBlank()) {
			int sep = cursor.indexOf('|');
			if (sep > 0) {
				sql.append(" AND (o.updated_at, o.id) < (CAST(:curTime AS timestamptz), CAST(:curId AS uuid))");
			}
		}
		sql.append(" ORDER BY o.updated_at DESC, o.id DESC LIMIT :limit");
		var spec = db.sql(sql.toString()).bind("limit", Math.max(1, Math.min(100, limit)));
		if (state != null && !state.isBlank()) {
			spec = spec.bind("state", state);
		}
		if (applicationId != null && !applicationId.isBlank()) {
			spec = spec.bind("app", applicationId);
		}
		if (cursor != null && !cursor.isBlank()) {
			int sep = cursor.indexOf('|');
			if (sep > 0) {
				try {
					spec = spec.bind("curTime", OffsetDateTime.parse(cursor.substring(0, sep)));
					spec = spec.bind("curId", UUID.fromString(cursor.substring(sep + 1)));
				} catch (Exception ignored) {
					// 非法游标按无游标处理（首页）
				}
			}
		}
		return spec.map(this::mapOperation).all().concatMap(this::withLegs);
	}
}
