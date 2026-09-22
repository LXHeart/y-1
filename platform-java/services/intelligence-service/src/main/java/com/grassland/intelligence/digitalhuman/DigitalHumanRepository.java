package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationKind;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.OperationState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.ProfileRevisionRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.ProfileRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.ProfileStatus;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionState;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Tone;
import com.grassland.intelligence.security.IntelligenceException;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 数字人域事务原语（任务书 #105B C105B-01 / K05）：绑定参数、owner 复查、序号/CAS 原语。
 *
 * <p>
 * 读一律带 owner 条件（空=不存在；不把他人行返回给无权方）；写经参数绑定。唯一键冲突映射域错误： owner 活动会话重复 → 409
 * {@code dh_session_active}；幂等键/预检重复 → 409 {@code dh_request_conflict}。
 * timestamptz 读为 {@link OffsetDateTime} 再转 {@link Instant}，uuid 经
 * {@code ::text} 传出、{@code CAST} 传入。
 */
@Component
public class DigitalHumanRepository {

	private final DatabaseClient db;
	private final TransactionalOperator transactions;

	public DigitalHumanRepository(DatabaseClient db, TransactionalOperator transactions) {
		this.db = db;
		this.transactions = transactions;
	}

	// ---------- profile：create 与 revision1 同事务 ----------

	public record ProfileRevisionInput(String persona, String greeting, Tone tone, UUID avatarId, int avatarRevision,
			String voiceId, int catalogVersion) {
	}

	/**
	 * profile 行 + revision1 同事务落库；并发/重复经唯一键拒绝（dh 表 PK 冲突 → 409
	 * dh_request_conflict）。
	 */
	public Mono<ProfileRow> insertProfileWithRevision(UUID id, String owner, String name,
			ProfileRevisionInput revision) {
		Mono<ProfileRow> body = db.sql("""
				INSERT INTO dh_profile(id, owner_account_id, name, active_revision, status)
				VALUES (CAST(:id AS uuid), :owner, :name, 1, 'active')
				""").bind("id", id.toString()).bind("owner", owner).bind("name", name).then()
				.then(db.sql("""
						INSERT INTO dh_profile_revision(id, owner_account_id, profile_id, revision, persona, greeting,
						    tone, avatar_id, avatar_revision, voice_id, catalog_version)
						VALUES (CAST(:rid AS uuid), :owner, CAST(:id AS uuid), 1, :persona, :greeting, :tone,
						    CAST(:avatarId AS uuid), :avatarRevision, :voiceId, :catalogVersion)
						""").bind("rid", UUID.randomUUID().toString()).bind("owner", owner).bind("id", id.toString())
						.bind("persona", revision.persona()).bind("greeting", revision.greeting())
						.bind("tone", revision.tone().name()).bind("avatarId", revision.avatarId().toString())
						.bind("avatarRevision", revision.avatarRevision()).bind("voiceId", revision.voiceId())
						.bind("catalogVersion", revision.catalogVersion()).then())
				.then(findProfile(owner, id));
		return transactions.transactional(body).onErrorMap(this::mapIntegrity);
	}

	/** owner 复查：只命中本人行；空=不存在（K05 目标签名）。 */
	public Mono<ProfileRow> findProfile(String owner, UUID id) {
		return db
				.sql("SELECT id::text, owner_account_id, name, active_revision, status, deleted_at,"
						+ " version, created_at, updated_at FROM dh_profile WHERE id = CAST(:id AS uuid)"
						+ " AND owner_account_id = :owner")
				.bind("id", id.toString()).bind("owner", owner).map(this::mapProfile).one();
	}

	public Mono<ProfileRevisionRow> findProfileRevision(String owner, UUID profileId, int revision) {
		return db.sql("""
				SELECT id::text, owner_account_id, profile_id::text, revision, persona, greeting, tone,
				       avatar_id::text, avatar_revision, voice_id, catalog_version, version, created_at, updated_at
				FROM dh_profile_revision
				WHERE profile_id = CAST(:id AS uuid) AND revision = :revision AND owner_account_id = :owner
				""").bind("id", profileId.toString()).bind("revision", revision).bind("owner", owner)
				.map(this::mapRevision).one();
	}

	/** CAS：状态/版本原语。条件不符 → empty（调用方翻译 404/409）；禁止改 owner/id。 */
	public Mono<ProfileRow> updateProfileStatus(UUID id, String owner, ProfileStatus target, int expectedVersion) {
		Mono<ProfileRow> body = db.sql("""
				UPDATE dh_profile SET status = :status, deleted_at = CASE WHEN :status = 'deleted'
				           THEN now() ELSE deleted_at END,
				       version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner AND status <> :status
				  AND version = :expectedVersion
				RETURNING id::text, owner_account_id, name, active_revision, status, deleted_at, version,
				          created_at, updated_at
				""").bind("id", id.toString()).bind("owner", owner).bind("status", target.name())
				.bind("expectedVersion", expectedVersion).map(this::mapProfile).one();
		return body.onErrorMap(this::mapIntegrity);
	}

	// ---------- operation：幂等键 (owner, kind, requestId) ----------

	/** 预留操作：不先执行副作用；同键插入冲突 → 409（同 hash 重放走 findOperationByKey）。 */
	public Mono<OperationRow> reserveOperation(UUID id, String owner, OperationKind kind, UUID requestId,
			String payloadHash, UUID resourceId) {
		var spec = db.sql("""
				INSERT INTO dh_operation(id, owner_account_id, kind, request_id, payload_hash, resource_id, state)
				VALUES (CAST(:id AS uuid), :owner, :kind, CAST(:requestId AS uuid), :payloadHash,
				        CAST(:resourceId AS uuid), 'pending')
				""").bind("id", id.toString()).bind("owner", owner).bind("kind", kind.name())
				.bind("requestId", requestId.toString()).bind("payloadHash", payloadHash)
				.bindNull("resourceId", String.class);
		if (resourceId != null) {
			spec = spec.bind("resourceId", resourceId.toString());
		}
		Mono<OperationRow> body = spec.then().then(findOperationByKey(owner, kind, requestId));
		return transactions.transactional(body).onErrorMap(this::mapIntegrity);
	}

	public Mono<OperationRow> findOperationByKey(String owner, OperationKind kind, UUID requestId) {
		return db.sql("""
				SELECT id::text, owner_account_id, kind, request_id::text, payload_hash, resource_id::text,
				       state, result_ref::text, error_code, retry_at, lease_owner, lease_until, version,
				       created_at, updated_at
				FROM dh_operation WHERE owner_account_id = :owner AND kind = :kind
				  AND request_id = CAST(:requestId AS uuid)
				""").bind("owner", owner).bind("kind", kind.name()).bind("requestId", requestId.toString())
				.map(this::mapOperation).one();
	}

	/** 终态 CAS（succeeded/failed）；同键重放读原行。 */
	public Mono<OperationRow> completeOperation(UUID id, String owner, OperationState target, UUID resultRef,
			String errorCode) {
		var spec = db.sql("""
				UPDATE dh_operation SET state = :state, result_ref = CAST(:resultRef AS uuid),
				       error_code = :errorCode, version = version + 1, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner AND state IN ('pending', 'running')
				RETURNING id::text, owner_account_id, kind, request_id::text, payload_hash, resource_id::text,
				          state, result_ref::text, error_code, retry_at, lease_owner, lease_until, version,
				          created_at, updated_at
				""").bind("id", id.toString()).bind("owner", owner).bind("state", target.name())
				.bindNull("resultRef", String.class).bindNull("errorCode", String.class);
		if (resultRef != null) {
			spec = spec.bind("resultRef", resultRef.toString());
		}
		if (errorCode != null) {
			spec = spec.bind("errorCode", errorCode);
		}
		return spec.map(this::mapOperation).one().onErrorMap(this::mapIntegrity);
	}

	// ---------- session：owner 活动唯一 / 预检一次性 ----------

	public record SessionInsert(UUID id, String owner, UUID profileId, int profileRevision,
			String profileNameAtCreation, String backendId, UUID preflightId, UUID controllerId,
			String configSnapshot) {
	}

	/**
	 * 会话插入：owner 活动唯一索引与 preflight UNIQUE 是最终并发判断（K05）；冲突映射 409：
	 * uq_dh_session_owner_active → dh_session_active；dh_session_preflight_id_key →
	 * dh_request_conflict。
	 */
	public Mono<SessionRow> insertSession(SessionInsert insert) {
		Mono<SessionRow> body = db.sql("""
				INSERT INTO dh_session(id, owner_account_id, profile_id, profile_revision,
				    profile_name_at_creation, backend_id, preflight_id, controller_id, config_snapshot,
				    state, state_entered_at)
				VALUES (CAST(:id AS uuid), :owner, CAST(:profileId AS uuid), :profileRevision,
				    :profileName, :backendId, CAST(:preflightId AS uuid), CAST(:controllerId AS uuid),
				    CAST(:configSnapshot AS jsonb), 'preparing', now())
				""").bind("id", insert.id().toString()).bind("owner", insert.owner())
				.bind("profileId", insert.profileId().toString()).bind("profileRevision", insert.profileRevision())
				.bind("profileName", insert.profileNameAtCreation()).bind("backendId", insert.backendId())
				.bind("preflightId", insert.preflightId().toString())
				.bind("controllerId", insert.controllerId().toString()).bind("configSnapshot", insert.configSnapshot())
				.then().then(findSession(insert.owner(), insert.id()));
		return transactions.transactional(body).onErrorMap(this::mapIntegrity);
	}

	public Mono<SessionRow> findSession(String owner, UUID id) {
		return db.sql(sessionSelect() + " WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner")
				.bind("id", id.toString()).bind("owner", owner).map(this::mapSession).one();
	}

	/** 状态 CAS 原语：expected 不符 → empty；state_entered_at 仅合法迁移时变更（K13.4）。 */
	public Mono<SessionRow> compareAndSetSessionState(UUID id, String owner, SessionState expected,
			SessionState target) {
		if (expected == target) {
			return Mono.error(new IllegalArgumentException("state CAS needs distinct expected/target"));
		}
		return db
				.sql("UPDATE dh_session SET state = :target, state_entered_at = now(),"
						+ " version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner AND state = :expected"
						+ " RETURNING " + sessionColumns())
				.bind("id", id.toString()).bind("owner", owner).bind("expected", expected.name())
				.bind("target", target.name()).map(this::mapSession).one().onErrorMap(this::mapIntegrity);
	}

	private String sessionSelect() {
		return "SELECT " + sessionColumns() + " FROM dh_session";
	}

	private String sessionColumns() {
		return """
				id::text, owner_account_id, profile_id::text, profile_revision, profile_name_at_creation,
				deleted_at, backend_id, preflight_id::text, worker_id, state, state_entered_at,
				last_browser_heartbeat_at, worker_lease_expires_at, last_activity_at, next_turn_epoch,
				render_ms, config_snapshot::text, lease_epoch, media_epoch, controller_id::text,
				last_seq, ready_at, expires_at, paused_until, lease_expires_at, ended_at,
				save_transcript, transcript_version, content_epoch, content_deleted, cleanup_pending,
				error_code, version, created_at, updated_at""";
	}

	// ---------- 序号原语 ----------

	/** 事件 seq 分配：事务内 last_seq+1 返回（K06 同事务递增）。 */
	public Mono<Long> nextEventSeq(UUID sessionId) {
		return db
				.sql("UPDATE dh_session SET last_seq = last_seq + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) RETURNING last_seq")
				.bind("id", sessionId.toString()).map(row -> row.get("last_seq", Long.class)).one();
	}

	// ---------- 行映射 ----------

	private ProfileRow mapProfile(Readable r) {
		return new ProfileRow(r.get("id", String.class), r.get("owner_account_id", String.class),
				r.get("name", String.class), r.get("active_revision", Integer.class),
				ProfileStatus.valueOf(r.get("status", String.class)),
				toInstant(r.get("deleted_at", OffsetDateTime.class)), r.get("version", Integer.class),
				toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	private ProfileRevisionRow mapRevision(Readable r) {
		return new ProfileRevisionRow(r.get("id", String.class), r.get("owner_account_id", String.class),
				r.get("profile_id", String.class), r.get("revision", Integer.class), r.get("persona", String.class),
				r.get("greeting", String.class), Tone.valueOf(r.get("tone", String.class)),
				r.get("avatar_id", String.class), r.get("avatar_revision", Integer.class),
				r.get("voice_id", String.class), r.get("catalog_version", Integer.class),
				r.get("version", Integer.class), toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	private OperationRow mapOperation(Readable r) {
		return new OperationRow(r.get("id", String.class), r.get("owner_account_id", String.class),
				OperationKind.valueOf(r.get("kind", String.class)), r.get("request_id", String.class),
				r.get("payload_hash", String.class), r.get("resource_id", String.class),
				OperationState.valueOf(r.get("state", String.class)), r.get("result_ref", String.class),
				r.get("error_code", String.class), toInstant(r.get("retry_at", OffsetDateTime.class)),
				r.get("lease_owner", String.class), toInstant(r.get("lease_until", OffsetDateTime.class)),
				r.get("version", Integer.class), toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	private SessionRow mapSession(Readable r) {
		return new SessionRow(r.get("id", String.class), r.get("owner_account_id", String.class),
				r.get("profile_id", String.class), r.get("profile_revision", Integer.class),
				r.get("profile_name_at_creation", String.class), toInstant(r.get("deleted_at", OffsetDateTime.class)),
				r.get("backend_id", String.class), r.get("preflight_id", String.class),
				r.get("worker_id", String.class), SessionState.valueOf(r.get("state", String.class)),
				toInstant(r.get("state_entered_at", OffsetDateTime.class)),
				toInstant(r.get("last_browser_heartbeat_at", OffsetDateTime.class)),
				toInstant(r.get("worker_lease_expires_at", OffsetDateTime.class)),
				toInstant(r.get("last_activity_at", OffsetDateTime.class)), r.get("next_turn_epoch", Long.class),
				r.get("render_ms", Long.class), r.get("config_snapshot", String.class),
				r.get("lease_epoch", Long.class), r.get("media_epoch", Long.class),
				r.get("controller_id", String.class), r.get("last_seq", Long.class),
				toInstant(r.get("ready_at", OffsetDateTime.class)),
				toInstant(r.get("expires_at", OffsetDateTime.class)),
				toInstant(r.get("paused_until", OffsetDateTime.class)),
				toInstant(r.get("lease_expires_at", OffsetDateTime.class)),
				toInstant(r.get("ended_at", OffsetDateTime.class)),
				Boolean.TRUE.equals(r.get("save_transcript", Boolean.class)),
				r.get("transcript_version", Integer.class), r.get("content_epoch", Long.class),
				Boolean.TRUE.equals(r.get("content_deleted", Boolean.class)),
				Boolean.TRUE.equals(r.get("cleanup_pending", Boolean.class)), r.get("error_code", String.class),
				r.get("version", Integer.class), toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	private static Instant toInstant(OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}

	/**
	 * 唯一键冲突 → 域错误 409；其余违反约束原样抛出（DB 是最终裁判）。 Spring R2DBC 会把 23505 包成
	 * DataIntegrityViolationException，需解包到 R2dbcException（CreditsService 同款惯例）。
	 */
	private Throwable mapIntegrity(Throwable error) {
		String message = integrityMessage(error);
		if (message != null) {
			if (message.contains("uq_dh_session_owner_active")) {
				return new IntelligenceException(409, "dh_session_active", "已有进行中的数字人会话，请先结束当前会话。");
			}
			if (message.contains("uq_dh_operation_key") || message.contains("dh_session_preflight_id_key")) {
				return new IntelligenceException(409, "dh_request_conflict", "请求已受理，请勿重复提交不同内容。");
			}
		}
		return error;
	}

	private static String integrityMessage(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof io.r2dbc.spi.R2dbcException r && r.getSqlState() != null
					&& (r.getSqlState().startsWith("23") || r.getSqlState().startsWith("27"))) {
				return String.valueOf(r.getMessage());
			}
			current = current.getCause();
		}
		return error instanceof DataIntegrityViolationException ? String.valueOf(error.getMessage()) : null;
	}
}
