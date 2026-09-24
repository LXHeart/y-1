package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.PreviewRow;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.PreviewState;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * dh_preview 仓储（任务书 #105D C105D-04 / 共享契约 K05）：TTL 状态/owner/唯一 invocation。
 *
 * <p>
 * 音频只在易失 Redis（{@code dh:preview:{id}}，TTL 10 分钟）——DB 不存音频；预检成功但 Redis 丢失 →
 * 410， 不重新调用 TTS。operation.resource_id 定位 preview（preview 行 id=resourceId）。
 */
@Component
public class DigitalHumanPreviewRepository {

	private final DatabaseClient db;

	public DigitalHumanPreviewRepository(DatabaseClient db) {
		this.db = db;
	}

	private static final String COLS = """
			id::text, owner_account_id, voice_id, catalog_version, state, expires_at, invocation_id::text,
			error_code, version, created_at, updated_at
			""";

	public Mono<PreviewRow> insert(PreviewRow row) {
		var statement = db
				.sql("INSERT INTO dh_preview(id, owner_account_id, voice_id, catalog_version, state,"
						+ " expires_at, invocation_id, error_code) VALUES (CAST(:id AS uuid), :owner, :voice, :catalog,"
						+ " :state, :expiresAt, CAST(:invocation AS uuid), :errorCode) RETURNING " + COLS)
				.bind("id", row.id()).bind("owner", row.ownerAccountId()).bind("voice", row.voiceId())
				.bind("catalog", row.catalogVersion()).bind("state", row.state().name())
				.bind("expiresAt", row.expiresAt()).bindNull("errorCode", String.class);
		// invocation_id 建行时为 null（invocation 在其后登记）；收尾后回填由 markState 扩展使用。
		statement = row.invocationId() == null
				? statement.bindNull("invocation", String.class)
				: statement.bind("invocation", row.invocationId());
		return statement.map(DigitalHumanPreviewRepository::mapRow).one();
	}

	public Mono<PreviewRow> findById(String owner, UUID id) {
		return db.sql("SELECT " + COLS + " FROM dh_preview WHERE id = CAST(:id AS uuid) AND owner_account_id = :owner")
				.bind("id", id.toString()).bind("owner", owner).map(DigitalHumanPreviewRepository::mapRow).one();
	}

	/** 状态收尾（processing→ready/failed；ready→expired）。 */
	public Mono<PreviewRow> markState(UUID id, PreviewState state, String errorCode) {
		var statement = db
				.sql("UPDATE dh_preview SET state = :state, error_code = :errorCode, version = version + 1,"
						+ " updated_at = now() WHERE id = CAST(:id AS uuid) RETURNING " + COLS)
				.bind("id", id.toString()).bind("state", state.name());
		statement = errorCode == null
				? statement.bindNull("errorCode", String.class)
				: statement.bind("errorCode", errorCode);
		return statement.map(DigitalHumanPreviewRepository::mapRow).one();
	}

	/** 当日（UTC）已创建试听数（限流/补贴聚合口径的一部分）。 */
	public Mono<Long> countToday(String owner, Instant utcDayStart) {
		return db
				.sql("SELECT count(*) AS n FROM dh_preview WHERE owner_account_id = :owner"
						+ " AND created_at >= :since")
				.bind("owner", owner).bind("since", utcDayStart).map(row -> row.get("n", Long.class)).one();
	}

	/** 当日（UTC）按 preview invocation 关联的 ai_run 实际秒数合计（30 分钟补贴上限口径）。 */
	public Mono<Long> previewSecondsToday(String owner, Instant utcDayStart) {
		return db.sql("""
				SELECT COALESCE(SUM(run.video_seconds), 0) AS n
				FROM ai_run run JOIN dh_invocation inv ON inv.ai_run_id = run.id
				WHERE inv.owner_account_id = :owner AND inv.stage = 'preview'
				  AND inv.created_at >= :since
				""").bind("owner", owner).bind("since", utcDayStart).map(row -> row.get("n", Long.class)).one();
	}

	private static PreviewRow mapRow(io.r2dbc.spi.Readable r) {
		return new PreviewRow(r.get("id", String.class), r.get("owner_account_id", String.class),
				r.get("voice_id", String.class), r.get("catalog_version", Integer.class),
				PreviewState.valueOf(r.get("state", String.class)),
				toInstant(r.get("expires_at", OffsetDateTime.class)), r.get("invocation_id", String.class),
				r.get("error_code", String.class), r.get("version", Integer.class),
				toInstant(r.get("created_at", OffsetDateTime.class)),
				toInstant(r.get("updated_at", OffsetDateTime.class)));
	}

	private static Instant toInstant(OffsetDateTime time) {
		return time == null ? null : time.toInstant();
	}
}
