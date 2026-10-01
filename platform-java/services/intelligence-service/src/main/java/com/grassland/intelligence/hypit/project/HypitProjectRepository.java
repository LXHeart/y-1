package com.grassland.intelligence.hypit.project;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * hypit_project 仓储（任务书 #107-1 C107-04 / K05）。归属过滤一律进 SQL： 非本人资源返回空（controller
 * 映射 404，不泄漏存在性）。
 */
@Component
public class HypitProjectRepository {

	private final DatabaseClient db;

	public HypitProjectRepository(DatabaseClient db) {
		this.db = db;
	}

	public record ProjectRow(UUID id, String accountId, UUID workspaceId, String title, String mode, String status,
			long revision, long version, String headManifestHash, String selectedRun, Instant deletedAt,
			Instant createdAt, Instant updatedAt) {
	}

	private static final String COLS = """
			id::text, account_id, workspace_id::text, title, mode, status, revision, version,
			head_manifest_hash, selected_run, deleted_at, created_at, updated_at
			""";

	/** 同事务插入（与 command/job 一起由 service 编排）。 */
	public Mono<ProjectRow> insert(ProjectRow row) {
		// bind() 拒 null：provisioning 期 head/selected 可空，走 bindNull。
		var statement = db
				.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status,"
						+ " revision, head_manifest_hash, selected_run) VALUES (CAST(:id AS uuid), :account,"
						+ " CAST(:workspace AS uuid), :title, :mode, :status, :revision, :head, :selected)"
						+ " RETURNING " + COLS)
				.bind("id", row.id().toString()).bind("account", row.accountId())
				.bind("workspace", row.workspaceId().toString()).bind("title", row.title()).bind("mode", row.mode())
				.bind("status", row.status()).bind("revision", row.revision());
		statement = row.headManifestHash() == null
				? statement.bindNull("head", String.class)
				: statement.bind("head", row.headManifestHash());
		statement = row.selectedRun() == null
				? statement.bindNull("selected", String.class)
				: statement.bind("selected", row.selectedRun());
		return statement.map(HypitProjectRepository::mapRow).one();
	}

	/** 归属过滤读取：非 owner 一律空。 */
	public Mono<ProjectRow> findOwned(String accountId, UUID id) {
		return db
				.sql("SELECT " + COLS + " FROM hypit_project WHERE id = CAST(:id AS uuid)"
						+ " AND account_id = :account")
				.bind("id", id.toString()).bind("account", accountId).map(HypitProjectRepository::mapRow).one();
	}

	/** 归属+状态探测（权限层消费；不存在/非本人/已删除统一为空或 deleted 判定）。 */
	public Mono<String> findOwnerStatus(String accountId, UUID id) {
		return db.sql("SELECT status FROM hypit_project WHERE id = CAST(:id AS uuid) AND account_id = :account")
				.bind("id", id.toString()).bind("account", accountId).map(row -> row.get("status", String.class)).one();
	}

	public Flux<ProjectRow> listOwned(String accountId, int limit) {
		return db
				.sql("SELECT " + COLS + " FROM hypit_project WHERE account_id = :account"
						+ " AND status <> 'deleted' ORDER BY updated_at DESC, id LIMIT :limit")
				.bind("account", accountId).bind("limit", limit).map(HypitProjectRepository::mapRow).all();
	}

	/** PATCH title 按 metadata version CAS（04.8）。 */
	public Mono<ProjectRow> patchTitle(UUID id, String accountId, String title, long baseVersion) {
		return db
				.sql("UPDATE hypit_project SET title = :title, version = version + 1, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) AND account_id = :account AND version = :baseVersion"
						+ " RETURNING " + COLS)
				.bind("id", id.toString()).bind("account", accountId).bind("title", title)
				.bind("baseVersion", baseVersion).map(HypitProjectRepository::mapRow).one();
	}

	/** provision 成功回执：status→ready + revision/headManifestHash。 */
	public Mono<ProjectRow> markReady(UUID id, long revision, String headManifestHash) {
		var statement = db
				.sql("UPDATE hypit_project SET status = 'ready', revision = :revision,"
						+ " head_manifest_hash = :head, updated_at = now() WHERE id = CAST(:id AS uuid)"
						+ " AND status IN ('provisioning', 'provisioning_failed') RETURNING " + COLS)
				.bind("id", id.toString()).bind("revision", revision);
		statement = headManifestHash == null
				? statement.bindNull("head", String.class)
				: statement.bind("head", headManifestHash);
		return statement.map(HypitProjectRepository::mapRow).one();
	}

	/**
	 * C107F2-05（TC-F2-05-04）：存量 revision0 工程的幂等首版晋升——只在 revision=0 且 ready 时推进到
	 * revision1（并发双触发仅一方生效，另一方读到 已推进行返回 null 由调用方回读）。不改 owner/title/sourceContext。
	 */
	public Mono<ProjectRow> promoteZeroRevision(UUID id, String manifestHash) {
		var statement = db.sql("UPDATE hypit_project SET revision = 1, head_manifest_hash = :head,"
				+ " updated_at = now() WHERE id = CAST(:id AS uuid) AND revision = 0 AND status = 'ready'"
				+ " RETURNING " + COLS).bind("id", id.toString());
		statement = manifestHash == null
				? statement.bindNull("head", String.class)
				: statement.bind("head", manifestHash);
		return statement.map(HypitProjectRepository::mapRow).one();
	}

	public Mono<Long> markStatus(UUID id, String status) {
		return db.sql("UPDATE hypit_project SET status = :status, updated_at = now()" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString()).bind("status", status).fetch().rowsUpdated();
	}

	/**
	 * C107F2-29：导入收敛——ready + revision/head/selected_run 一次落定（只在
	 * provisioning/provisioning_failed 态生效；重放幂等，碰撞返回空由调用方回读）。
	 */
	public Mono<ProjectRow> markReadyImported(UUID id, long revision, String headManifestHash, String selectedRun) {
		var statement = db
				.sql("UPDATE hypit_project SET status = 'ready', revision = :revision,"
						+ " head_manifest_hash = :head, selected_run = :selected, updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid)"
						+ " AND status IN ('provisioning', 'provisioning_failed') RETURNING " + COLS)
				.bind("id", id.toString()).bind("revision", revision);
		statement = headManifestHash == null
				? statement.bindNull("head", String.class)
				: statement.bind("head", headManifestHash);
		statement = selectedRun == null
				? statement.bindNull("selected", String.class)
				: statement.bind("selected", selectedRun);
		return statement.map(HypitProjectRepository::mapRow).one();
	}

	/** 应用修订：revision/head 推进（仅 ready 态工程；service 层已做 CAS）。 */
	public Mono<ProjectRow> advanceRevision(UUID id, long revision, String headManifestHash) {
		var statement = db
				.sql("UPDATE hypit_project SET revision = :revision, head_manifest_hash = :head,"
						+ " version = version + 1, updated_at = now() WHERE id = CAST(:id AS uuid)"
						+ " AND status = 'ready' RETURNING " + COLS)
				.bind("id", id.toString()).bind("revision", revision);
		statement = headManifestHash == null
				? statement.bindNull("head", String.class)
				: statement.bind("head", headManifestHash);
		return statement.map(HypitProjectRepository::mapRow).one();
	}

	public Mono<List<ProjectRow>> listOwnedPage(String accountId, int limit) {
		return listOwned(accountId, limit).collectList();
	}

	/**
	 * C107F2-11（§5.3）：createdAt DESC、id DESC 稳定 keyset 分页。cursor 为
	 * base64url("createdAt#id") 的 opaque 值；null/空 = 首页。行数 < limit 即末页
	 * （nextCursor=null）；恰好等于 limit 时给出游标，末页空 items 自然返回 null。
	 */
	public record CursorAnchor(Instant createdAt, UUID id) {
	}

	public record OwnedPage(List<ProjectRow> rows, String nextCursor) {
	}

	public Mono<OwnedPage> listOwnedBefore(String accountId, int limit, CursorAnchor anchor) {
		var spec = db.sql("SELECT " + COLS + " FROM hypit_project WHERE account_id = :account"
				+ " AND status <> 'deleted'"
				+ (anchor == null
						? ""
						: " AND (created_at < :afterAt OR (created_at = :afterAt AND id < CAST(:afterId AS uuid)))")
				+ " ORDER BY created_at DESC, id DESC LIMIT :limit").bind("account", accountId).bind("limit", limit);
		if (anchor != null) {
			spec = spec.bind("afterAt", anchor.createdAt()).bind("afterId", anchor.id().toString());
		}
		return spec.map(HypitProjectRepository::mapRow).all().collectList().map(rows -> {
			if (rows.size() < limit) {
				return new OwnedPage(rows, null);
			}
			ProjectRow last = rows.get(rows.size() - 1);
			return new OwnedPage(rows, encodeCursor(new CursorAnchor(last.createdAt(), last.id())));
		});
	}

	public static String encodeCursor(CursorAnchor anchor) {
		return Base64.getUrlEncoder().withoutPadding()
				.encodeToString((anchor.createdAt() + "#" + anchor.id()).getBytes(StandardCharsets.UTF_8));
	}

	/** 游标解码：格式非法抛 IllegalArgumentException（上层映射 400 hypit_invalid_input）。 */
	public static CursorAnchor decodeCursor(String cursor) {
		try {
			String text = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			int separator = text.lastIndexOf('#');
			return new CursorAnchor(Instant.parse(text.substring(0, separator)),
					UUID.fromString(text.substring(separator + 1)));
		} catch (RuntimeException error) {
			throw new IllegalArgumentException("cursor 非法");
		}
	}

	private static ProjectRow mapRow(io.r2dbc.spi.Readable row) {
		return new ProjectRow(UUID.fromString(row.get("id", String.class)), row.get("account_id", String.class),
				UUID.fromString(row.get("workspace_id", String.class)), row.get("title", String.class),
				row.get("mode", String.class), row.get("status", String.class),
				row.get("revision", Long.class) == null ? 0L : row.get("revision", Long.class),
				row.get("version", Long.class) == null ? 1L : row.get("version", Long.class),
				row.get("head_manifest_hash", String.class), row.get("selected_run", String.class),
				row.get("deleted_at", Instant.class), row.get("created_at", Instant.class),
				row.get("updated_at", Instant.class));
	}
}
