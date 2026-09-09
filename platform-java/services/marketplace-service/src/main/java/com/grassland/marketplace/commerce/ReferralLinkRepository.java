package com.grassland.marketplace.commerce;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 D98-01：不透明推广链接（rlid）数据访问。
 *
 * <p>
 * 读时生效状态：{@code status='active'} 且 {@code expires_at <= now()} 视为 expired（V60
 * 注释）； ended 仅由本人终止端点写入。所有查询返回 {@code effective_status}，调用方不重复推断。
 */
@Component
public class ReferralLinkRepository {

	private final DatabaseClient db;

	public ReferralLinkRepository(DatabaseClient db) {
		this.db = db;
	}

	/** 链接行（含任务挂靠套餐，用于拼购买页 URL 与列表展示）。 */
	public record ReferralLinkRow(String id, UUID recommenderAccountId, String taskId, String packageId,
			String policyVersion, String status, Instant createdAt, Instant expiresAt, Instant endedAt,
			String endedReason) {
		public String effectiveStatus() {
			return "active".equals(status) && expiresAt != null && !expiresAt.isAfter(Instant.now())
					? "expired"
					: status;
		}
	}

	private static final String SELECT_LINK = """
			SELECT l.id, l.recommender_account_id, l.task_id, t.commerce_package_id::text AS package_id,
			       l.policy_version, l.status, l.created_at, l.expires_at, l.ended_at, l.ended_reason
			FROM referral_link l LEFT JOIN task t ON t.id = l.task_id
			""";

	public Mono<ReferralLinkRow> insert(String id, UUID recommenderAccountId, String taskId, String policyVersion,
			Instant expiresAt) {
		return db
				.sql("INSERT INTO referral_link(id, recommender_account_id, task_id, policy_version, status,"
						+ " expires_at) VALUES (:id, :rec, CAST(:task AS uuid), :policy, 'active', :expires)"
						+ " RETURNING " + linkColumns())
				.bind("id", id).bind("rec", recommenderAccountId).bind("task", taskId).bind("policy", policyVersion)
				.bind("expires", expiresAt.atOffset(java.time.ZoneOffset.UTC)).map(this::mapRow).one();
	}

	/** 本人该任务的现行链接（active 且未过期）——发放幂等入口。 */
	public Mono<ReferralLinkRow> findCurrentByOwnerAndTask(UUID recommenderAccountId, String taskId) {
		return db
				.sql(SELECT_LINK + " WHERE l.recommender_account_id = :rec AND l.task_id = CAST(:task AS uuid)"
						+ " AND l.status = 'active' AND l.expires_at > now() ORDER BY l.created_at DESC LIMIT 1")
				.bind("rec", recommenderAccountId).bind("task", taskId).map(this::mapRow).one();
	}

	public Mono<ReferralLinkRow> findById(String id) {
		return db.sql(SELECT_LINK + " WHERE l.id = :id").bind("id", id).map(this::mapRow).one();
	}

	public Flux<ReferralLinkRow> listByOwner(UUID recommenderAccountId) {
		return db.sql(SELECT_LINK + " WHERE l.recommender_account_id = :rec ORDER BY l.created_at DESC")
				.bind("rec", recommenderAccountId).map(this::mapRow).all();
	}

	/** 本人终止（D98-01：链接可由本人失效，不可删改他人）：条件 UPDATE 单边胜出，0 行 → 调用方 409。 */
	public Mono<ReferralLinkRow> endByOwner(String id, UUID recommenderAccountId) {
		return db.sql("UPDATE referral_link SET status = 'ended', ended_at = now(), ended_reason = 'manual'"
				+ " WHERE id = :id AND recommender_account_id = :rec AND status = 'active' AND expires_at > now()"
				+ " RETURNING " + linkColumns()).bind("id", id).bind("rec", recommenderAccountId).map(this::mapRow)
				.one();
	}

	private static String linkColumns() {
		return "id, recommender_account_id, task_id, NULL::text AS package_id, policy_version, status,"
				+ " created_at, expires_at, ended_at, ended_reason";
	}

	private ReferralLinkRow mapRow(Readable row) {
		UUID taskId = row.get("task_id", UUID.class);
		return new ReferralLinkRow(row.get("id", String.class), row.get("recommender_account_id", UUID.class),
				taskId == null ? null : taskId.toString(), row.get("package_id", String.class),
				row.get("policy_version", String.class), row.get("status", String.class), instant(row, "created_at"),
				instant(row, "expires_at"), instant(row, "ended_at"), row.get("ended_reason", String.class));
	}

	private static Instant instant(Readable row, String name) {
		java.time.OffsetDateTime value = row.get(name, java.time.OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
