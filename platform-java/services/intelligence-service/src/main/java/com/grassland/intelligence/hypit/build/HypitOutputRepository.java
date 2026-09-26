package com.grassland.intelligence.hypit.build;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * hypit_output 索引仓储（任务书 #107-2 C107-10 / K05）。
 *
 * <p>{@code indexResult} 可重入：UNIQUE(build_id, output_name) 命中即回读原行，
 * 绝不覆盖已归档的 mediaId / archive_state（重复 sync 只补新增 Output）。
 */
@Component
public class HypitOutputRepository {

	private final DatabaseClient db;

	public HypitOutputRepository(DatabaseClient db) {
		this.db = db;
	}

	public record OutputRow(UUID id, UUID buildId, String outputName, String kind, String mediaType,
			String resourceHandle, String valueSummaryJson, String archiveState, UUID mediaId,
			UUID archiveCommandId, String errorCode, Long sizeBytes, Instant createdAt, Instant updatedAt) {
	}

	private static final String COLS = """
			id::text, build_id::text, output_name, kind, media_type, resource_handle, value_summary::text,
			archive_state, media_id::text, archive_command_id::text, error_code, size_bytes, created_at, updated_at
			""";

	/** 可重入索引：冲突回读原行（不覆盖归档事实）。 */
	public Mono<OutputRow> insert(UUID id, UUID buildId, String outputName, String kind, String mediaType,
			Long sizeBytes, String valueSummaryJson) {
		var statement = db.sql("""
				INSERT INTO hypit_output(id, build_id, output_name, kind, media_type, size_bytes, value_summary)
				VALUES (CAST(:id AS uuid), CAST(:build AS uuid), :name, :kind, :mediaType, :size,
				        CAST(:summary AS jsonb))
				ON CONFLICT (build_id, output_name) DO NOTHING
				RETURNING """ + " " + COLS)
				.bind("id", id.toString()).bind("build", buildId.toString()).bind("name", outputName)
				.bind("kind", kind).bind("summary", valueSummaryJson);
		statement = sizeBytes == null ? statement.bindNull("size", Long.class)
				: statement.bind("size", sizeBytes);
		statement = mediaType == null ? statement.bindNull("mediaType", String.class)
				: statement.bind("mediaType", mediaType);
		return statement.map(HypitOutputRepository::map).one().switchIfEmpty(findByBuildAndName(buildId, outputName));
	}

	public Mono<OutputRow> findById(UUID id) {
		return db.sql("SELECT " + COLS + " FROM hypit_output WHERE id = CAST(:id AS uuid)").bind("id", id.toString())
				.map(HypitOutputRepository::map).one();
	}

	public Mono<OutputRow> findByBuildAndName(UUID buildId, String outputName) {
		return db.sql("SELECT " + COLS + " FROM hypit_output WHERE build_id = CAST(:b AS uuid) AND output_name = :n")
				.bind("b", buildId.toString()).bind("n", outputName).map(HypitOutputRepository::map).one();
	}

	public Flux<OutputRow> findByBuild(UUID buildId) {
		return db.sql("SELECT " + COLS + " FROM hypit_output WHERE build_id = CAST(:b AS uuid) ORDER BY output_name")
				.bind("b", buildId.toString()).map(HypitOutputRepository::map).all();
	}

	public Mono<List<OutputRow>> findByBuildList(UUID buildId) {
		return findByBuild(buildId).collectList();
	}

	/** 输出历史（工程维度，按 Output 名筛选，新→旧；跨 Build 去重由调用方按 (build, name) 完成）。 */
	public Flux<OutputRow> historyByProject(UUID projectId, String outputName, int limit) {
		var statement = db.sql("""
				SELECT o.id::text, o.build_id::text, o.output_name, o.kind, o.media_type, o.resource_handle,
				       o.value_summary::text, o.archive_state, o.media_id::text, o.archive_command_id::text,
				       o.error_code, o.size_bytes, o.created_at, o.updated_at
				FROM hypit_output o JOIN hypit_build b ON b.id = o.build_id
				WHERE b.project_id = CAST(:p AS uuid) AND o.output_name = :n
				ORDER BY b.created_at DESC, o.id
				LIMIT :limit
				""").bind("p", projectId.toString()).bind("n", outputName).bind("limit", limit);
		return statement.map(HypitOutputRepository::map).all();
	}

	/** 归档认领（CAS）：pending→archiving 恰有一家成功；失败/其他进程已认领返回 false。 */
	public Mono<Boolean> claimArchive(UUID id) {
		return db.sql("""
				UPDATE hypit_output SET archive_state = 'archiving', updated_at = now()
				WHERE id = CAST(:id AS uuid) AND archive_state = 'pending'
				""").bind("id", id.toString()).fetch().rowsUpdated().map(updated -> updated > 0);
	}

	/** 归档完成：只补空 mediaId（「不覆盖已归档 mediaId」红线），状态推进 archived。 */
	public Mono<Boolean> completeArchive(UUID id, UUID mediaId, String resourceHandle) {
		var statement = db.sql("""
				UPDATE hypit_output SET archive_state = 'archived', media_id = COALESCE(media_id, CAST(:media AS uuid)),
				       resource_handle = COALESCE(resource_handle, :handle), error_code = NULL, updated_at = now()
				WHERE id = CAST(:id AS uuid)
				""").bind("id", id.toString()).bind("media", mediaId.toString()).bind("handle", resourceHandle);
		return statement.fetch().rowsUpdated().map(updated -> updated > 0);
	}

	public Mono<Boolean> failArchive(UUID id, String errorCode) {
		return db.sql("""
				UPDATE hypit_output SET archive_state = 'failed', error_code = :code, updated_at = now()
				WHERE id = CAST(:id AS uuid) AND archive_state = 'archiving'
				""").bind("id", id.toString()).bind("code", errorCode).fetch().rowsUpdated().map(updated -> updated > 0);
	}

	public Mono<Long> countByBuild(UUID buildId) {
		return db.sql("SELECT count(*) AS n FROM hypit_output WHERE build_id = CAST(:b AS uuid)")
				.bind("b", buildId.toString()).map((row, meta) -> row.get("n", Long.class)).one()
				.defaultIfEmpty(0L);
	}

	private static OutputRow map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata metadata) {
		String mediaId = row.get("media_id", String.class);
		String archiveCommandId = row.get("archive_command_id", String.class);
		return new OutputRow(UUID.fromString(row.get("id", String.class)),
				UUID.fromString(row.get("build_id", String.class)), row.get("output_name", String.class),
				row.get("kind", String.class), row.get("media_type", String.class),
				row.get("resource_handle", String.class), row.get("value_summary", String.class),
				row.get("archive_state", String.class), mediaId == null ? null : UUID.fromString(mediaId),
				archiveCommandId == null ? null : UUID.fromString(archiveCommandId),
				row.get("error_code", String.class), row.get("size_bytes", Long.class),
				row.get("created_at", Instant.class), row.get("updated_at", Instant.class));
	}
}
