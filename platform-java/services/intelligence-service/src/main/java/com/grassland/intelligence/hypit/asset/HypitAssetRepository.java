package com.grassland.intelligence.hypit.asset;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * hypit_asset / hypit_asset_reference 仓储（任务书 #107-1 C107-05 / K05/K09）。
 *
 * <p>
 * 归属过滤一律经 project owner（service 层已校验）；引用表按 (project, revision, path)
 * 锚定不可变引用，删除守卫消费 countReferencing。
 */
@Component
public class HypitAssetRepository {

	private final DatabaseClient db;

	public HypitAssetRepository(DatabaseClient db) {
		this.db = db;
	}

	public record AssetRow(UUID id, UUID projectId, UUID mediaId, String resourceHandle, String role, String originKind,
			String originUrl, String sha256, String mimeType, long sizeBytes, Integer width, Integer height,
			String probeJson, String status, long version, Instant createdAt, Instant updatedAt) {
	}

	private static final String COLS = """
			id::text, project_id::text, media_id::text, resource_handle, role, origin_kind,
			origin_url, sha256, mime_type, size_bytes, width, height, probe::text, status, version,
			created_at, updated_at
			""";

	/** importing 落库（probe 前先记账，失败可补偿为 failed；resource_handle 侧侧道持有）。 */
	public Mono<AssetRow> insert(AssetRow row) {
		var statement = db
				.sql("INSERT INTO hypit_asset(id, project_id, media_id, resource_handle, role,"
						+ " origin_kind, origin_url, sha256, mime_type, size_bytes, width, height, probe, status)"
						+ " VALUES (CAST(:id AS uuid), CAST(:project AS uuid), CAST(:media AS uuid), :handle, :role,"
						+ " :originKind, :originUrl, :hash, :mime, :size, :width, :height,"
						+ " CAST(:probe AS jsonb), :status) RETURNING " + COLS)
				.bind("id", row.id().toString()).bind("project", row.projectId().toString())
				.bind("handle", row.resourceHandle()).bind("role", row.role()).bind("originKind", row.originKind())
				.bind("hash", row.sha256()).bind("mime", row.mimeType()).bind("size", row.sizeBytes())
				.bind("status", row.status());
		statement = row.mediaId() == null
				? statement.bindNull("media", String.class)
				: statement.bind("media", row.mediaId().toString());
		statement = row.originUrl() == null
				? statement.bindNull("originUrl", String.class)
				: statement.bind("originUrl", row.originUrl());
		statement = row.width() == null
				? statement.bindNull("width", Integer.class)
				: statement.bind("width", row.width());
		statement = row.height() == null
				? statement.bindNull("height", Integer.class)
				: statement.bind("height", row.height());
		statement = row.probeJson() == null
				? statement.bindNull("probe", String.class)
				: statement.bind("probe", row.probeJson());
		return statement.map(HypitAssetRepository::mapRow).one();
	}

	/** project 范围读取（非 deleted）。 */
	public Flux<AssetRow> listByProject(UUID projectId) {
		return db
				.sql("SELECT " + COLS + " FROM hypit_asset WHERE project_id = CAST(:project AS uuid)"
						+ " AND status <> 'deleted' ORDER BY created_at DESC, id")
				.bind("project", projectId.toString()).map(HypitAssetRepository::mapRow).all();
	}

	public Mono<AssetRow> findById(UUID projectId, UUID assetId) {
		return db
				.sql("SELECT " + COLS + " FROM hypit_asset WHERE id = CAST(:id AS uuid)"
						+ " AND project_id = CAST(:project AS uuid)")
				.bind("id", assetId.toString()).bind("project", projectId.toString()).map(HypitAssetRepository::mapRow)
				.one();
	}

	/** probe 成功收敛：ready + 结构化事实。 */
	public Mono<AssetRow> markReady(UUID assetId, String mimeType, Integer width, Integer height, String probeJson) {
		var statement = db
				.sql("UPDATE hypit_asset SET status = 'ready', mime_type = :mime, width = :width,"
						+ " height = :height, probe = CAST(:probe AS jsonb), updated_at = now()"
						+ " WHERE id = CAST(:id AS uuid) RETURNING " + COLS)
				.bind("id", assetId.toString()).bind("mime", mimeType);
		statement = width == null ? statement.bindNull("width", Integer.class) : statement.bind("width", width);
		statement = height == null ? statement.bindNull("height", Integer.class) : statement.bind("height", height);
		statement = probeJson == null ? statement.bindNull("probe", String.class) : statement.bind("probe", probeJson);
		return statement.map(HypitAssetRepository::mapRow).one();
	}

	public Mono<Long> markStatus(UUID assetId, String status) {
		return db.sql("UPDATE hypit_asset SET status = :status, updated_at = now()" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", assetId.toString()).bind("status", status).fetch().rowsUpdated();
	}

	/** 软删守卫素材：有效引用（任意 revision）存在时拒绝。 */
	public Mono<Long> countReferencing(UUID assetId) {
		return db.sql("SELECT COUNT(*) AS c FROM hypit_asset_reference WHERE asset_id = CAST(:id AS uuid)")
				.bind("id", assetId.toString()).map((row, meta) -> row.get("c", Long.class)).one().defaultIfEmpty(0L);
	}

	/** revision 冻结时登记引用（apply 收敛路径消费，幂等主键）。 */
	public Mono<Long> referenceAsset(UUID projectId, long revision, UUID assetId, String relativePath, String sha256) {
		return db
				.sql("INSERT INTO hypit_asset_reference(project_id, revision, asset_id, relative_path,"
						+ " sha256) VALUES (CAST(:project AS uuid), :revision, CAST(:asset AS uuid), :path, :hash)"
						+ " ON CONFLICT (project_id, revision, relative_path) DO NOTHING")
				.bind("project", projectId.toString()).bind("revision", revision).bind("asset", assetId.toString())
				.bind("path", relativePath).bind("hash", sha256).fetch().rowsUpdated();
	}

	public Mono<List<AssetRow>> listReady(UUID projectId) {
		return listByProject(projectId).filter(row -> "ready".equals(row.status())).collectList();
	}

	private static AssetRow mapRow(io.r2dbc.spi.Readable row) {
		String media = row.get("media_id", String.class);
		return new AssetRow(UUID.fromString(row.get("id", String.class)),
				UUID.fromString(row.get("project_id", String.class)), media == null ? null : UUID.fromString(media),
				row.get("resource_handle", String.class), row.get("role", String.class),
				row.get("origin_kind", String.class), row.get("origin_url", String.class),
				row.get("sha256", String.class), row.get("mime_type", String.class),
				row.get("size_bytes", Long.class) == null ? 0L : row.get("size_bytes", Long.class),
				row.get("width", Integer.class), row.get("height", Integer.class), row.get("probe", String.class),
				row.get("status", String.class),
				row.get("version", Long.class) == null ? 1L : row.get("version", Long.class),
				row.get("created_at", Instant.class), row.get("updated_at", Instant.class));
	}
}
