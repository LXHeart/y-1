package com.grassland.identity.invitation;

import io.r2dbc.spi.Readable;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 组织邀请数据访问（R2DBC {@link DatabaseClient} 手写 SQL，与 membership/ 风格一致）。
 *
 * <p>
 * 状态迁移一律走 guarded UPDATE（{@code WHERE status='pending'}），返回受影响行数， 由调用方按 0
 * 行判定「已是终态/不存在」，避免读-改-写竞态。
 */
@Component
public class InvitationRepository {

	private static final String SELECT_COLS = "id::text, organization_id::text, store_id::text, email, role, status,"
			+ " invited_by_account_id::text, accepted_by_account_id::text, expires_at, created_at";

	private final DatabaseClient db;

	public InvitationRepository(DatabaseClient db) {
		this.db = db;
	}

	/**
	 * 新建待接受邀请（storeId 为 null = 组织级；非 null = 门店级，role 为 staff/manager）。 同 org 同邮箱已有
	 * pending 时触发 partial unique 冲突（由调用方转 409）。
	 */
	public Mono<Invitation> create(String organizationId, String storeId, String email, String role,
			String invitedByAccountId, Duration ttl) {
		String id = UUID.randomUUID().toString();
		var spec = db.sql("""
				INSERT INTO organization_invitation(
				    id, organization_id, store_id, email, role, status, invited_by_account_id, expires_at)
				VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:store AS uuid), :email, :role, 'pending',
				        CAST(:inviter AS uuid), now() + make_interval(secs => :ttl))
				RETURNING %s
				""".formatted(SELECT_COLS)).bind("id", id).bind("org", organizationId).bind("email", email)
				.bind("role", role).bind("inviter", invitedByAccountId).bind("ttl", (double) ttl.toSeconds());
		spec = storeId == null
				? spec.bindNull("store", java.util.UUID.class)
				: spec.bind("store", java.util.UUID.fromString(storeId));
		return spec.map(InvitationRepository::map).one();
	}

	/** 组织侧列表：全部邀请（含终态），新的在前。 */
	public Flux<Invitation> findByOrganization(String organizationId) {
		return db
				.sql("SELECT " + SELECT_COLS + " FROM organization_invitation"
						+ " WHERE organization_id = CAST(:org AS uuid) ORDER BY created_at DESC")
				.bind("org", organizationId).map(InvitationRepository::map).all();
	}

	public Mono<Invitation> findById(String id) {
		return db.sql("SELECT " + SELECT_COLS + " FROM organization_invitation WHERE id = CAST(:id AS uuid)")
				.bind("id", id).map(InvitationRepository::map).one();
	}

	/**
	 * 邀请二次提醒 claim（任务书 #41 尾巴）：条件 UPDATE 置位 {@code reminder_sent_at}—— pending +
	 * 未过期 + 创建早于阈值 + 从未提醒。与 outbox 事件同事务（由调用方编排）， 重复扫描 0 行天然幂等；与
	 * accept/decline/revoke 的状态迁移竞态由 {@code status='pending'} 单边胜出。
	 */
	public Flux<Invitation> claimPendingReminders(Instant createdBefore, int limit) {
		return db.sql("""
				WITH candidates AS (
				    SELECT id FROM organization_invitation
				     WHERE status = 'pending' AND reminder_sent_at IS NULL
				       AND expires_at > now() AND created_at <= :createdBefore
				     ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT :limit
				)
				UPDATE organization_invitation i
				   SET reminder_sent_at = now()
				  FROM candidates WHERE i.id = candidates.id
				RETURNING %s
				""".formatted(qualifiedCols()))
				.bind("createdBefore", OffsetDateTime.ofInstant(createdBefore, java.time.ZoneOffset.UTC))
				.bind("limit", Math.max(1, Math.min(limit, 500))).map(InvitationRepository::map).all();
	}

	/** UPDATE...FROM 下 RETURNING 的裸列名与 candidates.id 歧义——统一加 i. 前缀。 */
	private static String qualifiedCols() {
		return java.util.Arrays.stream(SELECT_COLS.split(",")).map(col -> "i." + col.strip())
				.collect(java.util.stream.Collectors.joining(", "));
	}

	/** 同 org 同邮箱的待接受邀请（预查，用于给出干净的 409 而非依赖唯一索引异常）。 */
	public Mono<Invitation> findPending(String organizationId, String email) {
		return db
				.sql("SELECT " + SELECT_COLS + " FROM organization_invitation"
						+ " WHERE organization_id = CAST(:org AS uuid) AND email = :email AND status = 'pending'")
				.bind("org", organizationId).bind("email", email).map(InvitationRepository::map).one();
	}

	/** 被邀请人视角：按邮箱列未过期的待接受邀请，带组织名。 */
	public Flux<PendingInvitationView> findPendingForEmail(String email) {
		return db.sql("""
				SELECT i.id::text AS id, i.organization_id::text AS organization_id, o.name AS organization_name,
				       i.store_id::text AS store_id, s.name AS store_name,
				       i.role, i.expires_at, i.created_at
				FROM organization_invitation i
				JOIN organization o ON o.id = i.organization_id
				LEFT JOIN store s ON s.id = i.store_id
				WHERE i.email = :email AND i.status = 'pending' AND i.expires_at > now()
				ORDER BY i.created_at DESC
				""").bind("email", email)
				.map(row -> new PendingInvitationView(row.get("id", String.class),
						row.get("organization_id", String.class), row.get("organization_name", String.class),
						row.get("store_id", String.class), row.get("store_name", String.class),
						row.get("role", String.class), toInstant(row.get("expires_at", OffsetDateTime.class)),
						toInstant(row.get("created_at", OffsetDateTime.class))))
				.all();
	}

	/** pending → accepted，并记录接受者。返回受影响行数（0=已是终态，竞态下的第二次接受）。 */
	public Mono<Long> accept(String id, String accountId) {
		return db
				.sql("UPDATE organization_invitation SET status = 'accepted',"
						+ " accepted_by_account_id = CAST(:acct AS uuid), updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND status = 'pending'")
				.bind("id", id).bind("acct", accountId).fetch().rowsUpdated();
	}

	/** pending → 指定终态（revoked / declined）。返回受影响行数。 */
	public Mono<Long> transitionFromPending(String id, InvitationStatus target) {
		return db
				.sql("UPDATE organization_invitation SET status = :status, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND status = 'pending'")
				.bind("id", id).bind("status", target.dbValue()).fetch().rowsUpdated();
	}

	private static Invitation map(Readable row) {
		return new Invitation(row.get("id", String.class), row.get("organization_id", String.class),
				row.get("store_id", String.class), row.get("email", String.class), row.get("role", String.class),
				row.get("status", String.class), row.get("invited_by_account_id", String.class),
				row.get("accepted_by_account_id", String.class), toInstant(row.get("expires_at", OffsetDateTime.class)),
				toInstant(row.get("created_at", OffsetDateTime.class)));
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
