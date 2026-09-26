package com.grassland.intelligence.hypit.project;

import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * hypit_revision 仓储（任务书 #107-1 C107-04 / K05）。
 *
 * <p>
 * UNIQUE(project_id, number) 防双写；command_id UNIQUE 保证一次变更集只产生一行修订。
 */
@Component
public class HypitRevisionRepository {

	private final DatabaseClient db;

	public HypitRevisionRepository(DatabaseClient db) {
		this.db = db;
	}

	public record RevisionRow(UUID id, UUID projectId, long number, Long parentNumber, String manifestHash,
			String snapshotHandle, UUID commandId, String createdBy, Instant createdAt) {
	}

	private static final String COLS = """
			id::text, project_id::text, number, parent_number, manifest_hash, snapshot_handle,
			command_id::text, created_by, created_at
			""";

	/** 幂等插入：同 (project, number) 已存在（command 重放）时读原行返回。 */
	public Mono<RevisionRow> insert(RevisionRow row) {
		var statement = db
				.sql("INSERT INTO hypit_revision(id, project_id, number, parent_number,"
						+ " manifest_hash, snapshot_handle, command_id, created_by) VALUES (CAST(:id AS uuid),"
						+ " CAST(:project AS uuid), :number, :parent, :hash, :snapshot, CAST(:command AS uuid),"
						+ " :createdBy) ON CONFLICT (project_id, number) DO NOTHING RETURNING " + COLS)
				.bind("id", row.id().toString()).bind("project", row.projectId().toString())
				.bind("number", row.number()).bind("hash", row.manifestHash()).bind("snapshot", row.snapshotHandle())
				.bind("createdBy", row.createdBy());
		statement = row.parentNumber() == null
				? statement.bindNull("parent", Long.class)
				: statement.bind("parent", row.parentNumber());
		statement = row.commandId() == null
				? statement.bindNull("command", String.class)
				: statement.bind("command", row.commandId().toString());
		return statement.map(HypitRevisionRepository::mapRow).one()
				.switchIfEmpty(Mono.defer(() -> findByNumber(row.projectId(), row.number())));
	}

	public Mono<RevisionRow> findByNumber(UUID projectId, long number) {
		return db
				.sql("SELECT " + COLS + " FROM hypit_revision WHERE project_id = CAST(:project AS uuid)"
						+ " AND number = :number")
				.bind("project", projectId.toString()).bind("number", number).map(HypitRevisionRepository::mapRow)
				.one();
	}

	private static RevisionRow mapRow(io.r2dbc.spi.Readable row) {
		String commandId = row.get("command_id", String.class);
		Long parent = row.get("parent_number", Long.class);
		return new RevisionRow(UUID.fromString(row.get("id", String.class)),
				UUID.fromString(row.get("project_id", String.class)), row.get("number", Long.class), parent,
				row.get("manifest_hash", String.class), row.get("snapshot_handle", String.class),
				commandId == null ? null : UUID.fromString(commandId), row.get("created_by", String.class),
				row.get("created_at", Instant.class));
	}
}
