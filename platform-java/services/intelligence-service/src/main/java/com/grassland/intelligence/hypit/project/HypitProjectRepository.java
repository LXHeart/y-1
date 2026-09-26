package com.grassland.intelligence.hypit.project;

import java.time.Instant;
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

	public Mono<Long> markStatus(UUID id, String status) {
		return db.sql("UPDATE hypit_project SET status = :status, updated_at = now()" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString()).bind("status", status).fetch().rowsUpdated();
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
