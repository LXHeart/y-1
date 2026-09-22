package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationState;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 控制租约（任务书 #105C C105C-02 / K04、K13.4）：epoch 与截止时间。
 *
 * <p>
 * 心跳只续 {@code lease_expires_at}（last+30s），不延长 pausedUntil、不动
 * state_entered_at。pause 首次写 {@code paused_until=now()+30s} 并轮换 epoch；重复 pause
 * 幂等（不延期）。resume 锁内轮换 epoch+1、换 controllerId；他页接管须 takeover=true（否则 409
 * {@code dh_takeover_required}）；旧 epoch 一律 409
 * {@code dh_lease_stale}（K01：同文案可恢复语义）。
 */
@Component
public class DigitalHumanLeaseService {

	/** K01 固定：heartbeat 10s/租约 30s；resume 窗口 30s。 */
	static final Duration LEASE_TTL = Duration.ofSeconds(30);
	static final Duration RESUME_WINDOW = Duration.ofSeconds(30);

	public record HeartbeatResult(String serverNow, String leaseExpiresAt, String pausedUntil) {
	}

	private final DatabaseClient db;
	private final TransactionalOperator transactions;
	private final DigitalHumanOperations operations;
	private final java.time.Clock clock;

	@org.springframework.beans.factory.annotation.Autowired
	public DigitalHumanLeaseService(DatabaseClient db, TransactionalOperator transactions,
			DigitalHumanOperations operations) {
		this(db, transactions, operations, java.time.Clock.systemUTC());
	}

	DigitalHumanLeaseService(DatabaseClient db, TransactionalOperator transactions, DigitalHumanOperations operations,
			java.time.Clock clock) {
		this.db = db;
		this.transactions = transactions;
		this.operations = operations;
		this.clock = clock;
	}

	// API13
	public Mono<HeartbeatResult> heartbeat(PersonalActor actor, UUID sessionId, long leaseEpoch, UUID controllerId) {
		Mono<HeartbeatResult> body = db.sql("""
				UPDATE dh_session SET last_browser_heartbeat_at = now(),
				       lease_expires_at = now() + INTERVAL '30 seconds', updated_at = now()
				WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner AND lease_epoch = :epoch
				  AND controller_id = CAST(:controllerId AS uuid)
				  AND state IN ('connecting','ready','listening','responding')
				RETURNING to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') AS server_now,
				          lease_expires_at, paused_until
				""").bind("id", sessionId.toString()).bind("owner", actor.accountId()).bind("epoch", leaseEpoch)
				.bind("controllerId", controllerId.toString())
				.map(row -> new HeartbeatResult(row.get("server_now", String.class),
						String.valueOf(row.get("lease_expires_at", java.time.OffsetDateTime.class)),
						row.get("paused_until", java.time.OffsetDateTime.class) == null
								? null
								: String.valueOf(row.get("paused_until", java.time.OffsetDateTime.class))))
				.one().switchIfEmpty(Mono.error(staleOrMissing(sessionId)));
		return transactions.transactional(body);
	}

	// API11：pause 写固定 pausedUntil（首次），重复不延期；epoch 轮换。
	public Mono<Map<String, Object>> pause(PersonalActor actor, UUID sessionId, UUID requestId, long leaseEpoch,
			String reason) {
		Mono<Map<String, Object>> body = operations
				.reserve(actor, OperationKind.session_pause, requestId,
						DigitalHumanOperations.canonicalHash(
								Map.of("sessionId", sessionId.toString(), "leaseEpoch", leaseEpoch)),
						sessionId)
				.flatMap(operation -> operation.state() == OperationState.succeeded
						? currentTerminal(actor, sessionId)
						: db.sql(
								"""
										UPDATE dh_session SET state = 'paused',
										       paused_until = COALESCE(paused_until, now() + INTERVAL '30 seconds'),
										       lease_epoch = lease_epoch + 1, state_entered_at = now(),
										       version = version + 1, updated_at = now()
										WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner
										  AND lease_epoch = :epoch AND state IN ('connecting','ready','listening','responding','paused')
										RETURNING id::text
										""")
								.bind("id", sessionId.toString()).bind("owner", actor.accountId())
								.bind("epoch", leaseEpoch).map(row -> row.get("id", String.class)).one()
								.switchIfEmpty(Mono.error(staleOrMissing(sessionId)))
								.flatMap(id -> complete(operation.id(), sessionId)
										.thenReturn(Map.<String, Object>of("paused", true))));
		return transactions.transactional(body);
	}

	// API12：resume（锁内 epoch+1；他页需 takeover）。
	public Mono<Map<String, Object>> resume(PersonalActor actor, UUID sessionId, long expectedEpoch, UUID controllerId,
			boolean takeover, UUID requestId) {
		Mono<Map<String, Object>> body = operations
				.reserve(actor, OperationKind.session_resume, requestId,
						DigitalHumanOperations.canonicalHash(Map.of("sessionId", sessionId.toString(), "expectedEpoch",
								expectedEpoch, "takeover", takeover)),
						sessionId)
				.flatMap(operation -> operation.state() == OperationState.succeeded
						? currentTerminal(actor, sessionId)
						: db.sql("SELECT controller_id::text AS c, state, paused_until, lease_epoch FROM dh_session"
								+ " WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner FOR UPDATE")
								.bind("id", sessionId.toString()).bind("owner", actor.accountId())
								.map((row, meta) -> row).one()
								.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")))
								.flatMap(locked -> {
									String holder = locked.get("c", String.class);
									String state = locked.get("state", String.class);
									// 旧 epoch 一律 lease stale（先于状态判定：旧页对任何状态的动过作都失效）。
									Long currentEpoch = locked.get("lease_epoch", Long.class);
									if (currentEpoch == null || currentEpoch != expectedEpoch) {
										return Mono.error(staleOrMissing(sessionId));
									}
									if (holder != null && !holder.equals(controllerId.toString()) && !takeover) {
										return Mono.error(new IntelligenceException(409, "dh_takeover_required",
												"此会话已由另一页面接管，请重新获取会话状态。"));
									}
									if (!java.util.List.of("paused", "reconnecting").contains(state)) {
										return Mono.error(
												new IntelligenceException(409, "dh_state_conflict", "会话当前状态不支持恢复。"));
									}
									// 过期：paused_until/reconnect 窗口已过 → 409（reaper 会收尾）。
									java.time.OffsetDateTime pausedUntil = locked.get("paused_until",
											java.time.OffsetDateTime.class);
									if (pausedUntil != null
											&& clock.instant().isAfter(pausedUntil.toInstant().plus(RESUME_WINDOW))) {
										return Mono.error(
												new IntelligenceException(409, "dh_lease_stale", "恢复窗口已过期，请重新开始会话。"));
									}
									return db.sql("""
											UPDATE dh_session SET state = 'connecting', lease_epoch = lease_epoch + 1,
											       controller_id = CAST(:c AS uuid), paused_until = NULL,
											       state_entered_at = now(), version = version + 1,
											       updated_at = now()
											WHERE id = CAST(:id AS uuid) AND lease_epoch = :epoch
											  AND state IN ('paused','reconnecting')
											RETURNING id::text
											""").bind("id", sessionId.toString()).bind("epoch", expectedEpoch)
											.bind("c", controllerId.toString()).map(row -> row.get("id", String.class))
											.one().switchIfEmpty(Mono.error(staleOrMissing(sessionId)))
											.flatMap(id -> complete(operation.id(), sessionId)
													.thenReturn(Map.<String, Object>of("resumed", true)));
								}));
		return transactions.transactional(body);
	}

	private Mono<Map<String, Object>> currentTerminal(PersonalActor actor, UUID sessionId) {
		return db.sql("SELECT state FROM dh_session WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner")
				.bind("id", sessionId.toString()).bind("owner", actor.accountId())
				.map(row -> row.get("state", String.class)).one().map(state -> Map.<String, Object>of("state", state));
	}

	private Mono<Void> complete(String operationId, UUID resourceId) {
		return db
				.sql("UPDATE dh_operation SET state = 'succeeded', result_ref = CAST(:r AS uuid),"
						+ " version = version + 1, updated_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("id", operationId).bind("r", resourceId.toString()).then();
	}

	private static IntelligenceException staleOrMissing(UUID sessionId) {
		// 旧 epoch/状态不符/不存在统一 dh_lease_stale 文案（同恢复语义，K01）。
		return new IntelligenceException(409, "dh_lease_stale", "此会话已由另一页面接管，请重新获取会话状态。");
	}
}
