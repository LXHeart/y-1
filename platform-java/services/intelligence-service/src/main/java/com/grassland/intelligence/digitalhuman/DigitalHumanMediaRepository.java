package com.grassland.intelligence.digitalhuman;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 数字人媒体仓储（任务书 #105F C105F-01 / 共享契约 K05、K09、K14.3）。
 *
 * <p>
 * dh_avatar / dh_recording / dh_asset_attachment / dh_cleanup 的 SQL
 * 载体：引用/唯一约束/清理登记都 落在真实 DB 行（不内存伪造）；派生对象「先登记再写」由 {@link #registerCleanup}
 * 的调用序保证。 生命周期注销批次（registry 登记的真实 handler）：媒体行按 owner 删除（DELETE 不经 dh_guard
 * 触发器， V85 口径），未收口 cleanup 计入活动计数——「清理未收口不当空闲」（BR-15）。
 */
@Component
public class DigitalHumanMediaRepository {

	private final DatabaseClient db;

	public DigitalHumanMediaRepository(DatabaseClient db) {
		this.db = db;
	}

	// ---------- 行类型（K05 dh_avatar/dh_cleanup 列一一对应） ----------

	public record AvatarRow(String id, String ownerAccountId, String sourceMediaId, String source, String status,
			int revision, String bundleManifest, String rightsVersion, Instant rightsAcceptedAt,
			String licenseEvidenceRef, String providerResourceRefs, String errorCode, int version, Instant createdAt,
			Instant updatedAt) {
	}

	public record CleanupRow(String id, String ownerAccountId, String resourceKind, String resourceId, String objectRef,
			String state, String reason, int attempts, Instant nextAttemptAt, String lastErrorCode, int version,
			Instant createdAt, Instant updatedAt) {
	}

	private static final String AVATAR_COLUMNS = """
			id::text AS id, owner_account_id, source_media_id::text AS source_media_id, source, status,
			revision, bundle_manifest::text AS bundle_manifest, rights_version,
			rights_accepted_at, license_evidence_ref,
			provider_resource_refs::text AS provider_resource_refs, error_code, version,
			created_at, updated_at
			""";

	private static final String CLEANUP_COLUMNS = """
			id::text AS id, owner_account_id, resource_kind, resource_id::text AS resource_id, object_ref,
			state, reason, attempts, next_attempt_at, last_error_code, version, created_at, updated_at
			""";

	// ---------- avatar ----------

	public Mono<AvatarRow> insertAvatar(AvatarRow row) {
		var spec = db.sql("INSERT INTO dh_avatar(id, owner_account_id, source_media_id, source, status, revision,"
				+ " rights_version, rights_accepted_at, license_evidence_ref, provider_resource_refs, error_code)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:media AS uuid), 'personal', :status, :revision,"
				+ " :rightsVersion, :acceptedAt, :evidenceRef, CAST(:refs AS jsonb), CAST(:errorCode AS text))")
				.bind("id", row.id()).bind("owner", row.ownerAccountId()).bind("media", row.sourceMediaId())
				.bind("status", row.status()).bind("revision", row.revision())
				.bind("rightsVersion", row.rightsVersion())
				.bind("acceptedAt", row.rightsAcceptedAt().atOffset(java.time.ZoneOffset.UTC))
				.bind("evidenceRef", row.licenseEvidenceRef());
		spec = row.providerResourceRefs() == null
				? spec.bindNull("refs", String.class)
				: spec.bind("refs", row.providerResourceRefs());
		spec = row.errorCode() == null
				? spec.bindNull("errorCode", String.class)
				: spec.bind("errorCode", row.errorCode());
		return spec.then().then(findAvatarById(UUID.fromString(row.id())));
	}

	public Mono<AvatarRow> findAvatarById(UUID id) {
		return db.sql("SELECT " + AVATAR_COLUMNS + " FROM dh_avatar WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString()).map(DigitalHumanMediaRepository::mapAvatar).one();
	}

	public Mono<AvatarRow> findAvatarByOperationResource(UUID avatarId, String owner) {
		return db
				.sql("SELECT " + AVATAR_COLUMNS + " FROM dh_avatar WHERE id = CAST(:id AS uuid)"
						+ " AND owner_account_id = :owner")
				.bind("id", avatarId.toString()).bind("owner", owner).map(DigitalHumanMediaRepository::mapAvatar).one();
	}

	/** 状态收尾（单向：processing→ready/failed；任意非终态→revoked；清理完成→deleted）。 */
	public Mono<Void> updateAvatarStatus(UUID id, String status, String errorCode, String bundleManifest,
			String providerResourceRefs) {
		var spec = db
				.sql("UPDATE dh_avatar SET status = :status, error_code = :errorCode,"
						+ " bundle_manifest = COALESCE(CAST(:manifest AS jsonb), bundle_manifest),"
						+ " provider_resource_refs = COALESCE(CAST(:refs AS jsonb), provider_resource_refs),"
						+ " version = version + 1, updated_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString()).bind("status", status);
		spec = errorCode == null ? spec.bindNull("errorCode", String.class) : spec.bind("errorCode", errorCode);
		spec = bundleManifest == null ? spec.bindNull("manifest", String.class) : spec.bind("manifest", bundleManifest);
		spec = providerResourceRefs == null
				? spec.bindNull("refs", String.class)
				: spec.bind("refs", providerResourceRefs);
		return spec.then();
	}

	/** API32 活动引用检查：活动会话（含 cleanup_pending）的冻结 profileRevision 指向该形象。 */
	public Mono<Boolean> hasActiveSessionReference(UUID avatarId, String owner) {
		return db.sql("""
				SELECT count(*) AS n FROM dh_session s
				JOIN dh_profile_revision r ON r.profile_id = s.profile_id AND r.revision = s.profile_revision
				WHERE r.avatar_id = CAST(:avatar AS uuid) AND s.owner_account_id = :owner
				  AND (s.state NOT IN ('ended', 'failed') OR s.cleanup_pending)
				""").bind("avatar", avatarId.toString()).bind("owner", owner).map(row -> row.get("n", Long.class)).one()
				.map(count -> count != null && count > 0).defaultIfEmpty(false);
	}

	/** dh_catalog.customAvatarEnabled（无配置行=false，K10 默认关）。 */
	public Mono<Boolean> customAvatarEnabled() {
		return db
				.sql("SELECT (config_json ->> 'customAvatarEnabled')::boolean AS enabled FROM dh_catalog"
						+ " WHERE singleton_id = 1")
				.map(row -> row.get("enabled", Boolean.class)).one().map(enabled -> enabled != null && enabled)
				.defaultIfEmpty(false);
	}

	// ----------
	// cleanup（K05：先登记再写对象；UNIQUE(resource_kind,resource_id,COALESCE(object_ref,''))）
	// ----------

	/** 幂等登记（同资源同句柄不重复；reason 保留首次）。 */
	public Mono<Void> registerCleanup(String owner, String resourceKind, UUID resourceId, String objectRef,
			String reason) {
		var spec = db
				.sql("INSERT INTO dh_cleanup(id, owner_account_id, resource_kind, resource_id, object_ref,"
						+ " state, reason) VALUES (CAST(:id AS uuid), :owner, :kind, CAST(:rid AS uuid), :oref,"
						+ " 'pending', :reason) ON CONFLICT DO NOTHING")
				.bind("id", UUID.randomUUID().toString()).bind("owner", owner).bind("kind", resourceKind)
				.bind("rid", resourceId.toString()).bind("reason", reason);
		spec = objectRef == null ? spec.bindNull("oref", String.class) : spec.bind("oref", objectRef);
		return spec.then();
	}

	public Mono<List<CleanupRow>> listCleanupForResource(String resourceKind, UUID resourceId) {
		return db
				.sql("SELECT " + CLEANUP_COLUMNS + " FROM dh_cleanup WHERE resource_kind = :kind"
						+ " AND resource_id = CAST(:rid AS uuid) ORDER BY created_at")
				.bind("kind", resourceKind).bind("rid", resourceId.toString())
				.map(DigitalHumanMediaRepository::mapCleanup).all().collectList().defaultIfEmpty(List.of());
	}

	public Mono<Void> markCleanupDeleted(List<String> ids) {
		return markCleanup(ids, "deleted", null, null);
	}

	public Mono<Void> markCleanupFailed(List<String> ids, String errorCode, Instant nextAttemptAt) {
		return markCleanup(ids, "failed", errorCode, nextAttemptAt);
	}

	private Mono<Void> markCleanup(List<String> ids, String state, String errorCode, Instant nextAttemptAt) {
		if (ids.isEmpty()) {
			return Mono.empty();
		}
		OffsetDateTime nextAt = nextAttemptAt == null ? null : nextAttemptAt.atOffset(java.time.ZoneOffset.UTC);
		var spec = db.sql("UPDATE dh_cleanup SET state = :state, last_error_code = :errorCode,"
				+ " attempts = attempts + 1, next_attempt_at = :nextAt, version = version + 1, updated_at = now()"
				+ " WHERE id = ANY(CAST(:ids AS text[])::uuid[])").bind("state", state)
				.bind("ids", ids.toArray(String[]::new));
		spec = errorCode == null ? spec.bindNull("errorCode", String.class) : spec.bind("errorCode", errorCode);
		spec = nextAt == null ? spec.bindNull("nextAt", OffsetDateTime.class) : spec.bind("nextAt", nextAt);
		return spec.then();
	}

	// ---------- 生命周期批次（registry eraseHandler 指向；调用方=注销编排/IT） ----------

	/** 活动计数：未收口 cleanup 行（pending/deleting/failed 待重试）不当空闲。 */
	public Mono<Map<String, Long>> countActiveMedia(String accountId) {
		Map<String, Long> counts = new LinkedHashMap<>();
		return db
				.sql("SELECT count(*) AS n FROM dh_cleanup WHERE owner_account_id = :owner"
						+ " AND state IN ('pending', 'deleting', 'failed')")
				.bind("owner", accountId).map(row -> row.get("n", Long.class)).one().doOnNext(n -> {
					if (n != null && n > 0) {
						counts.put("dh_cleanup", n);
					}
				}).thenReturn(counts);
	}

	/**
	 * 注销批次：删除本人媒体行（attachment/recording 随阶段补充进列表；DELETE 不经触发器）。 远端/沙箱派生对象仍需
	 * 逐句柄清理——由 dh_cleanup 残留计数兜底（G 编排消费）。
	 */
	public Mono<Map<String, Long>> eraseMediaForOwner(String accountId) {
		Map<String, Long> deleted = new LinkedHashMap<>();
		List<String> tables = new ArrayList<>(
				List.of("dh_asset_attachment", "dh_recording", "dh_avatar", "dh_cleanup"));
		Mono<Void> chain = Mono.empty();
		for (String table : tables) {
			chain = chain.then(db.sql("DELETE FROM " + table + " WHERE owner_account_id = :owner")
					.bind("owner", accountId).fetch().rowsUpdated()
					.doOnNext(rows -> deleted.put(table, rows == null ? 0L : rows.longValue())).then());
		}
		return chain.thenReturn(deleted);
	}

	// ---------- 映射 ----------

	private static AvatarRow mapAvatar(io.r2dbc.spi.Readable r) {
		return new AvatarRow(r.get("id", String.class), r.get("owner_account_id", String.class),
				r.get("source_media_id", String.class), r.get("source", String.class), r.get("status", String.class),
				value(r.get("revision", Integer.class), 1), r.get("bundle_manifest", String.class),
				r.get("rights_version", String.class), toInstant(r.get("rights_accepted_at", OffsetDateTime.class)),
				r.get("license_evidence_ref", String.class), r.get("provider_resource_refs", String.class),
				r.get("error_code", String.class), value(r.get("version", Integer.class), 1),
				toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	private static CleanupRow mapCleanup(io.r2dbc.spi.Readable r) {
		return new CleanupRow(r.get("id", String.class), r.get("owner_account_id", String.class),
				r.get("resource_kind", String.class), r.get("resource_id", String.class),
				r.get("object_ref", String.class), r.get("state", String.class), r.get("reason", String.class),
				value(r.get("attempts", Integer.class), 0), toInstant(r.get("next_attempt_at", OffsetDateTime.class)),
				r.get("last_error_code", String.class), value(r.get("version", Integer.class), 1),
				toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	private static int value(Integer value, int fallback) {
		return value == null ? fallback : value;
	}

	private static Instant toInstant(OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}
}
