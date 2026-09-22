package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanPreflightService.Snapshot;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Session;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionBilling;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionSnapshot;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionState;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 会话创建（任务书 #105C C105C-01 / K04、K13.4）：预检消费、容量锁、原子建 session。
 *
 * <p>
 * 锁序：account gate（V88 触发器 FOR SHARE）→ dh_catalog singleton FOR UPDATE（容量/队列锁）→
 * dh_session。 总槽由同一 catalog 行锁保证（不 COUNT 后无锁 INSERT）；满槽按全局队列上限 10 排队，队满 429
 * {@code dh_capacity_full} + Retry-After。owner 活动唯一与 preflight 一次性由 DB UNIQUE
 * 最终裁定。 提交后经 runtime client create（C01 用可替换 transport/fake HTTP 验证协议；mTLS 归
 * D02），失败标 failed+cleanup_pending，不二次创建。
 */
@Component
public class DigitalHumanSessionService {

	private static final int QUEUE_MAX = 10;
	private static final String RETRY_AFTER_SECONDS = "3";
	private static final String COLUMNS = "id::text AS id, owner_account_id AS owner,"
			+ " profile_id::text AS profileId, profile_revision, state, lease_epoch, media_epoch,"
			+ " controller_id::text AS controllerId, created_at, ready_at, expires_at, paused_until,"
			+ " lease_expires_at, last_seq, save_transcript, transcript_version, content_epoch";

	private final DatabaseClient db;
	private final TransactionalOperator transactions;
	private final DigitalHumanOperations operations;
	private final DigitalHumanPreflightService preflights;
	private final DigitalHumanPolicy policy;
	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanRuntimeClient runtime;

	public DigitalHumanSessionService(DatabaseClient db, TransactionalOperator transactions,
			DigitalHumanOperations operations, DigitalHumanPreflightService preflights, DigitalHumanPolicy policy,
			DigitalHumanAuthorization authorization, DigitalHumanRuntimeClient runtime) {
		this.db = db;
		this.transactions = transactions;
		this.operations = operations;
		this.preflights = preflights;
		this.policy = policy;
		this.authorization = authorization;
		this.runtime = runtime;
	}

	public record CreateResult(SessionRowView row, String backendId, boolean createdNow) {
	}

	record SessionRowView(String id, String profileId, int profileRevision, String state, long leaseEpoch,
			long mediaEpoch, String controllerId, Instant createdAt, Instant readyAt, Instant expiresAt,
			Instant pausedUntil, Instant leaseExpiresAt, long lastSeq, boolean saveTranscript, int transcriptVersion,
			long contentEpoch) {
	}

	/**
	 * API08：同键重放回原 session（createdNow=false）；幂等键 (owner, session_create,
	 * requestId)。
	 */
	public Mono<CreateResult> create(PersonalActor actor, UUID preflightId, UUID requestId, boolean saveTranscript) {
		Mono<CreateResult> transactional = operations
				.reserve(actor, OperationKind.session_create, requestId,
						DigitalHumanOperations.canonicalHash(
								Map.of("preflightId", preflightId.toString(), "saveTranscript", saveTranscript)),
						null)
				.flatMap(operation -> {
					if (operation.state() == OperationState.succeeded && operation.resultRef() != null) {
						return findSession(actor, UUID.fromString(operation.resultRef()))
								.map(row -> new CreateResult(row, "replay", false));
					}
					return policy.requireNewSessionsAllowed().then(authorization.requireActiveAccount(actor))
							.then(preflights.consume(actor, preflightId))
							.flatMap(snapshot -> verifySnapshotFresh(actor, snapshot)
									.then(allocateAndInsert(actor, snapshot, saveTranscript, operation)));
				}).onErrorMap(DigitalHumanSessionService::mapIntegrity);
		return transactions.transactional(transactional).flatMap(result -> {
			if (!result.createdNow() || !SessionState.preparing.name().equals(result.row().state())) {
				return Mono.just(result);
			}
			// 提交后派发 runtime：成功 → connecting；失败/超时 → failed+cleanup_pending（503 语义）。
			return runtime.createSession(result.row().id(), result.backendId())
					.then(casState(result.row().id(), actor, SessionState.preparing, SessionState.connecting))
					.map(updated -> new CreateResult(updated, result.backendId(), true)).onErrorResume(
							failure -> markInitFailed(result.row().id(), actor).then(Mono.error(translate(failure))));
		});
	}

	private static IntelligenceException translate(Throwable failure) {
		if (failure instanceof IntelligenceException exception) {
			return exception;
		}
		return new IntelligenceException(503, "dh_runtime_unavailable", "数字人服务暂不可用，请稍后重试。");
	}

	private Mono<Void> verifySnapshotFresh(PersonalActor actor, Snapshot snapshot) {
		return db
				.sql("SELECT version FROM dh_profile WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner"
						+ " AND status = 'active'")
				.bind("id", snapshot.profileId()).bind("owner", actor.accountId())
				.map(row -> row.get("version", Integer.class)).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "dh_configuration_changed", "角色已变更，请重新预检。")))
				.flatMap(version -> version == snapshot.profileVersion()
						? Mono.empty()
						: Mono.error(new IntelligenceException(409, "dh_configuration_changed", "角色已更新，请重新预检。")))
				.then();
	}

	private Mono<CreateResult> allocateAndInsert(PersonalActor actor, Snapshot snapshot, boolean saveTranscript,
			OperationRow operation) {
		return db
				.sql("SELECT singleton_id, config_json::text AS config FROM dh_catalog WHERE singleton_id = 1"
						+ " FOR UPDATE")
				.map((row, meta) -> row.get("config", String.class)).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_feature_disabled", "数字人功能暂未开放。")))
				.flatMap(configJson -> {
					int maxSessions = readMaxSessions(configJson);
					return db
							.sql("SELECT count(*) FILTER (WHERE state <> 'queued'"
									+ " AND state NOT IN ('ended','failed')) AS active,"
									+ " count(*) FILTER (WHERE state = 'queued') AS queued FROM dh_session")
							.map((row,
									meta) -> new long[]{row.get("active", Long.class), row.get("queued", Long.class)})
							.one().flatMap(counts -> decideState(counts[0], counts[1], maxSessions))
							.flatMap(state -> insertSession(actor, snapshot, saveTranscript, state)
									.flatMap(inserted -> completeOperation(operation.id(), inserted.id())
											.thenReturn(new CreateResult(inserted, snapshot.backendId(), true))));
				});
	}

	private static int readMaxSessions(String configJson) {
		try {
			int value = new com.fasterxml.jackson.databind.ObjectMapper().readTree(configJson).path("maxSessionsGlobal")
					.asInt(1);
			return Math.max(1, Math.min(100, value));
		} catch (Exception invalid) {
			return 1;
		}
	}

	private Mono<SessionState> decideState(long active, long queued, int maxSessions) {
		if (active < maxSessions) {
			return Mono.just(SessionState.preparing);
		}
		if (queued >= QUEUE_MAX) {
			return Mono.error(new CapacityFullException(RETRY_AFTER_SECONDS));
		}
		return Mono.just(SessionState.queued);
	}

	/** 队列/容量满 → 429 dh_capacity_full（带 Retry-After 秒数）。 */
	static final class CapacityFullException extends IntelligenceException {

		private final String retryAfterSeconds;

		CapacityFullException(String retryAfterSeconds) {
			super(429, "dh_capacity_full", "当前使用人数较多，请稍后重试。");
			this.retryAfterSeconds = retryAfterSeconds;
		}

		String retryAfterSeconds() {
			return retryAfterSeconds;
		}
	}

	/** DB 最终裁定的唯一冲突 → 域错误（与 B01 仓储同口径）。 */
	private static Throwable mapIntegrity(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof io.r2dbc.spi.R2dbcException r && "23505".equals(r.getSqlState())) {
				String message = String.valueOf(r.getMessage());
				if (message.contains("uq_dh_session_owner_active")) {
					return new IntelligenceException(409, "dh_session_active", "已有进行中的数字人会话，请先结束当前会话。");
				}
				if (message.contains("dh_session_preflight_id_key")) {
					return new IntelligenceException(409, "dh_request_conflict", "该预检已用于会话创建，请重新预检。");
				}
			}
			current = current.getCause();
		}
		return error;
	}

	/** 操作回执终态（session id 回填 result_ref；同事务）。 */
	private Mono<Void> completeOperation(String operationId, String sessionId) {
		return db.sql("UPDATE dh_operation SET state = 'succeeded', result_ref = CAST(:r AS uuid),"
				+ " resource_id = CAST(:r AS uuid), version = version + 1, updated_at = now()"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", operationId).bind("r", sessionId).then();
	}

	private Mono<SessionRowView> insertSession(PersonalActor actor, Snapshot snapshot, boolean saveTranscript,
			SessionState state) {
		String id = UUID.randomUUID().toString();
		String configSnapshot = "{\"v\":1,\"llm\":\"" + snapshot.llmModel() + "\",\"stt\":\"" + snapshot.sttModel()
				+ "\",\"tts\":\"" + snapshot.ttsModel() + "\",\"render\":\"" + snapshot.renderModel()
				+ "\",\"priceTableVersion\":\"" + snapshot.priceTableVersion() + "\"}";
		return db.sql("""
				INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision,
				    profile_name_at_creation, backend_id, preflight_id, controller_id, config_snapshot,
				    state, state_entered_at, save_transcript)
				VALUES (CAST(:id AS uuid), :owner, CAST(:profileId AS uuid),
				    (SELECT active_revision FROM dh_profile WHERE id = CAST(:profileId AS uuid)),
				    (SELECT name FROM dh_profile WHERE id = CAST(:profileId AS uuid)), :backendId,
				    CAST(:preflightId AS uuid), CAST(:controllerId AS uuid), CAST(:config AS jsonb),
				    :state, now(), :saveTranscript)
				""").bind("id", id).bind("owner", actor.accountId()).bind("profileId", snapshot.profileId())
				.bind("backendId", snapshot.backendId()).bind("preflightId", snapshot.id())
				.bind("controllerId", snapshot.controllerId()).bind("config", configSnapshot)
				.bind("state", state.name()).bind("saveTranscript", saveTranscript).then()
				.then(findSession(actor, UUID.fromString(id)));
	}

	private Mono<SessionRowView> findSession(PersonalActor actor, UUID sessionId) {
		return db
				.sql("SELECT " + COLUMNS + " FROM dh_session WHERE id = CAST(:id AS uuid)"
						+ " AND owner_account_id = :owner")
				.bind("id", sessionId.toString()).bind("owner", actor.accountId())
				.map(DigitalHumanSessionService::mapRow).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。")));
	}

	private Mono<String> markInitFailed(String sessionId, PersonalActor actor) {
		return db
				.sql("UPDATE dh_session SET state = 'failed', error_code = 'dh_runtime_unavailable',"
						+ " cleanup_pending = true, state_entered_at = now(), version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner"
						+ " AND state IN ('preparing','queued')")
				.bind("id", sessionId).bind("owner", actor.accountId()).then().thenReturn(sessionId);
	}

	private Mono<SessionRowView> casState(String sessionId, PersonalActor actor, SessionState expected,
			SessionState target) {
		return db
				.sql("UPDATE dh_session SET state = :target, state_entered_at = now(), version = version + 1,"
						+ " updated_at = now() WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner"
						+ " AND state = :expected RETURNING " + COLUMNS)
				.bind("id", sessionId).bind("owner", actor.accountId()).bind("expected", expected.name())
				.bind("target", target.name()).map(DigitalHumanSessionService::mapRow).one()
				.switchIfEmpty(Mono.error(new IntelligenceException(409, "dh_state_conflict", "会话状态已变化。")));
	}

	// API14：end（owner 可结束；CAS 单向收尾；重复幂等同终态）。
	public Mono<EndResult> end(PersonalActor actor, UUID sessionId, UUID requestId, String reason) {
		Mono<EndResult> body = operations
				.reserve(actor, OperationKind.session_end, requestId,
						DigitalHumanOperations.canonicalHash(Map.of("sessionId", sessionId.toString())), sessionId)
				.flatMap(operation -> operation.state() == OperationState.succeeded
						? findSession(actor, sessionId).map(row -> new EndResult(row.state(), false))
						: db.sql("""
								UPDATE dh_session SET state = 'ending', state_entered_at = now(),
								       version = version + 1, updated_at = now()
								WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner
								  AND state NOT IN ('ended','failed')
								RETURNING state
								""").bind("id", sessionId.toString()).bind("owner", actor.accountId())
								.map(row -> row.get("state", String.class)).one()
								.map(state -> new EndResult(state, true)).defaultIfEmpty(new EndResult("ended", false))
								.flatMap(result -> completeOperation(operation.id(), sessionId.toString())
										.thenReturn(result)));
		return transactions.transactional(body).flatMap(result -> {
			if (!result.endedNow()) {
				return Mono.just(result);
			}
			// 事务后通知 runtime 关闭（失败不回滚业务终态；reaper 兜底）。
			return runtime.end(sessionId.toString(), reason == null ? "user" : reason)
					.onErrorResume(ignored -> Mono.empty())
					.then(casState(sessionId.toString(), actor, SessionState.ending, SessionState.ended))
					.map(updated -> new EndResult(updated.state(), true)).onErrorResume(ignored -> Mono.just(result));
		});
	}

	public record EndResult(String state, boolean endedNow) {
	}

	// ---------- 读 ----------

	/**
	 * API10：owner 授权的 SessionSnapshot（扁平：Session 字段 + 恢复字段；普通 GET
	 * replayComplete=false）。
	 */
	public Mono<SessionSnapshot> get(PersonalActor actor, UUID sessionId) {
		return findSession(actor, sessionId).map(
				row -> new SessionSnapshot(toDto(row), allowedActions(SessionState.valueOf(row.state())), false, null));
	}

	static java.util.List<String> allowedActions(SessionState state) {
		return switch (state) {
			case ready, listening, responding -> java.util.List.of("startTurn", "startAudio", "interrupt", "pause",
					"end", "saveTranscript", "deleteTranscript");
			case paused, reconnecting -> java.util.List.of("resume", "end", "saveTranscript");
			case connecting, preparing, queued -> java.util.List.of("end");
			case ending -> java.util.List.of();
			case ended, failed -> java.util.List.of("saveTranscript", "deleteTranscript");
		};
	}

	static Session toDto(SessionRowView row) {
		return new Session(row.id(), row.profileId(), row.profileRevision(), SessionState.valueOf(row.state()),
				row.leaseEpoch(), row.mediaEpoch(), row.controllerId(), Instant.now(), row.createdAt(), row.readyAt(),
				row.expiresAt(), row.pausedUntil(), row.leaseExpiresAt(), row.lastSeq(), row.saveTranscript(),
				row.transcriptVersion(), row.contentEpoch(), new SessionBilling(0, 0, 0, 0, "pt"), null);
	}

	private static SessionRowView mapRow(io.r2dbc.spi.Readable r) {
		return new SessionRowView(r.get("id", String.class), r.get("profileId", String.class),
				r.get("profile_revision", Integer.class), r.get("state", String.class),
				r.get("lease_epoch", Long.class), r.get("media_epoch", Long.class), r.get("controllerId", String.class),
				toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("ready_at", OffsetDateTime.class)),
				toInstant(r.get("expires_at", OffsetDateTime.class)),
				toInstant(r.get("paused_until", OffsetDateTime.class)),
				toInstant(r.get("lease_expires_at", OffsetDateTime.class)), r.get("last_seq", Long.class),
				Boolean.TRUE.equals(r.get("save_transcript", Boolean.class)),
				r.get("transcript_version", Integer.class), r.get("content_epoch", Long.class));
	}

	private static Instant toInstant(OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}
}
