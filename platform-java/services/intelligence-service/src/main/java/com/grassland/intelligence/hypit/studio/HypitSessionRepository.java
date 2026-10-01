package com.grassland.intelligence.hypit.studio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

/**
 * 持久会话仓库（107-fix-2 C107F2-19；V92 hypit_session）。
 *
 * <p>
 * 会话事实（owner/kind/run/revision/readOnly/TTL/撤销）只在 PG：创建=先登记 starting 行再让 broker
 * 启动实例；票据 nonce 单槽（新票替换旧票，§7.1）； 核销 CAS（ WHERE ticket_nonce_hash = :expected
 * AND ticket_redeemed_at IS NULL）。
 */
@Repository
public class HypitSessionRepository {

	private final DatabaseClient db;

	public HypitSessionRepository(DatabaseClient db) {
		this.db = db;
	}

	public record SessionRow(String id, UUID projectId, String accountId, String kind, String runFile, long revision,
			boolean readOnly, String state, Instant expiresAt, Instant revokedAt, String ticketNonceHash,
			Instant ticketExpiresAt, Instant ticketRedeemedAt, long version) {
	}

	private static final String COLS = """
			id, project_id::text, account_id, kind, run_file, revision, read_only, state,
			expires_at, revoked_at, ticket_nonce_hash, ticket_expires_at, ticket_redeemed_at, version
			""";

	/** 先登记（starting）：broker 启动成功后再 markActive；失败标 failed。 */
	public Mono<SessionRow> insertStarting(String id, UUID projectId, String accountId, String kind, String runFile,
			long revision, boolean readOnly, Instant expiresAt) {
		return db.sql("INSERT INTO hypit_session(id, project_id, account_id, kind, run_file, revision, read_only,"
				+ " state, expires_at) VALUES (:id, CAST(:project AS uuid), :account, :kind, :runFile, :revision,"
				+ " :readOnly, 'starting', :expiresAt) RETURNING " + COLS).bind("id", id)
				.bind("project", projectId.toString()).bind("account", accountId).bind("kind", kind)
				.bind("runFile", runFile).bind("revision", revision).bind("readOnly", readOnly)
				.bind("expiresAt", expiresAt).map(HypitSessionRepository::mapRow).one();
	}

	public Mono<SessionRow> markActive(String id) {
		return updateState(id, "active", "starting");
	}

	public Mono<SessionRow> markFailed(String id) {
		return updateState(id, "failed", "starting");
	}

	public Mono<SessionRow> markClosed(String id) {
		return db
				.sql("UPDATE hypit_session SET state = 'closed', updated_at = now(), version = version + 1"
						+ " WHERE id = :id AND state IN ('starting','active') RETURNING " + COLS)
				.bind("id", id).map(HypitSessionRepository::mapRow).one();
	}

	public Mono<SessionRow> markRevoked(String id) {
		return db
				.sql("UPDATE hypit_session SET state = 'revoked', revoked_at = now(), updated_at = now(),"
						+ " version = version + 1 WHERE id = :id AND state IN ('starting','active') RETURNING " + COLS)
				.bind("id", id).map(HypitSessionRepository::mapRow).one();
	}

	private Mono<SessionRow> updateState(String id, String next, String expected) {
		return db
				.sql("UPDATE hypit_session SET state = :next, updated_at = now(), version = version + 1"
						+ " WHERE id = :id AND state = :expected RETURNING " + COLS)
				.bind("id", id).bind("next", next).bind("expected", expected).map(HypitSessionRepository::mapRow).one();
	}

	/** 撤销属主全部活跃会话（工程删除/账号注销，C107F2-20 的撤销矩阵入口）。 */
	public Mono<Long> revokeAllForOwner(String accountId, UUID projectId) {
		return db
				.sql("UPDATE hypit_session SET state = 'revoked', revoked_at = now(), updated_at = now(),"
						+ " version = version + 1 WHERE account_id = :account AND project_id = CAST(:project AS uuid)"
						+ " AND state IN ('starting','active') RETURNING id")
				.bind("account", accountId).bind("project", projectId.toString()).fetch().all().count();
	}

	public Mono<SessionRow> findById(String id) {
		return db.sql("SELECT " + COLS + " FROM hypit_session WHERE id = :id").bind("id", id)
				.map(HypitSessionRepository::mapRow).one();
	}

	/** 同 owner/project/kind/run/revision/readOnly 的活跃会话（复用键，C107F2-20）。 */
	public Mono<SessionRow> findReusable(String accountId, UUID projectId, String kind, String runFile, long revision,
			boolean readOnly) {
		return db
				.sql("SELECT " + COLS + " FROM hypit_session WHERE account_id = :account"
						+ " AND project_id = CAST(:project AS uuid) AND kind = :kind AND run_file = :runFile"
						+ " AND revision = :revision AND read_only = :readOnly AND state = 'active'"
						+ " AND expires_at > now() ORDER BY created_at DESC LIMIT 1")
				.bind("account", accountId).bind("project", projectId.toString()).bind("kind", kind)
				.bind("runFile", runFile).bind("revision", revision).bind("readOnly", readOnly)
				.map(HypitSessionRepository::mapRow).one();
	}

	/** 新票替换当前 nonce 槽：旧未核销票立即失效（§7.1 单槽策略）。 */
	public Mono<SessionRow> replaceTicket(String id, String nonceHash, Instant ticketExpiresAt) {
		return db
				.sql("UPDATE hypit_session SET ticket_nonce_hash = :nonce, ticket_expires_at = :expires,"
						+ " ticket_redeemed_at = NULL, updated_at = now(), version = version + 1"
						+ " WHERE id = :id AND state = 'active' RETURNING " + COLS)
				.bind("id", id).bind("nonce", nonceHash).bind("expires", ticketExpiresAt)
				.map(HypitSessionRepository::mapRow).one();
	}

	/** 一次性核销：nonce 槽匹配且未核销（CAS）；已核销/换票返回空。 */
	public Mono<SessionRow> redeemTicket(String id, String nonceHash) {
		return db
				.sql("UPDATE hypit_session SET ticket_redeemed_at = now(), updated_at = now(), version = version + 1"
						+ " WHERE id = :id AND state = 'active' AND ticket_nonce_hash = :nonce"
						+ " AND ticket_redeemed_at IS NULL AND ticket_expires_at > now() RETURNING " + COLS)
				.bind("id", id).bind("nonce", nonceHash).map(HypitSessionRepository::mapRow).one();
	}

	/** 重启自愈：PG active 但进程已丢（broker 无绑定）→ failed，UI 显式重开（§6.9）。 */
	public Mono<Long> failOrphaned(List<String> liveSessionIds) {
		String inList = liveSessionIds.isEmpty()
				? "''"
				: liveSessionIds.stream().map(id -> "'" + id.replace("'", "") + "'").reduce((a, b) -> a + "," + b)
						.orElse("''");
		return db.sql("UPDATE hypit_session SET state = CASE WHEN expires_at <= now() THEN 'expired' ELSE 'failed' END,"
				+ " updated_at = now(), version = version + 1 WHERE state = 'active' AND id NOT IN (" + inList + ")"
				+ " AND kind = 'studio'").fetch().rowsUpdated();
	}

	/** 访问时惰性转 expired（C107F2-19）：TTL 已过的 active 行收敛终态。 */
	public Mono<SessionRow> markExpired(String id) {
		return updateState(id, "expired", "active");
	}

	/** 写回成功后推进会话基线（CAS：仅前进，不回退）。 */
	public Mono<SessionRow> advanceRevision(String id, long revision) {
		return db
				.sql("UPDATE hypit_session SET revision = :revision, updated_at = now(), version = version + 1"
						+ " WHERE id = :id AND state = 'active' AND revision < :revision RETURNING " + COLS)
				.bind("id", id).bind("revision", revision).map(HypitSessionRepository::mapRow).one();
	}

	private static SessionRow mapRow(io.r2dbc.spi.Readable row) {
		return new SessionRow(row.get("id", String.class), UUID.fromString(row.get("project_id", String.class)),
				row.get("account_id", String.class), row.get("kind", String.class), row.get("run_file", String.class),
				row.get("revision", Long.class) == null ? 0L : row.get("revision", Long.class),
				Boolean.TRUE.equals(row.get("read_only", Boolean.class)), row.get("state", String.class),
				row.get("expires_at", Instant.class), row.get("revoked_at", Instant.class),
				row.get("ticket_nonce_hash", String.class), row.get("ticket_expires_at", Instant.class),
				row.get("ticket_redeemed_at", Instant.class),
				row.get("version", Long.class) == null ? 1L : row.get("version", Long.class));
	}
}
