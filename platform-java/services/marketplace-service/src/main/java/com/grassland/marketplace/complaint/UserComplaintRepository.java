package com.grassland.marketplace.complaint;

import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code user_complaint} 仓储（PRD §11.8）：用户举报/投诉工单。提交链路 insert（同举报人同对象同原因的
 * 未办结投诉 → 409 由控制器经 {@link #findOpenDuplicate} 判定）；客服处置 {@link #handle} 幂等 UPDATE
 * 可重判修正。
 */
@Component
public class UserComplaintRepository {

	private final DatabaseClient db;

	public UserComplaintRepository(DatabaseClient db) {
		this.db = db;
	}

	public record ComplaintRow(UUID id, String reporterAccountId, String targetType, String targetId,
			String reason, String description, String status, String handlerAccountId, String resolutionNote,
			Instant handledAt, Instant createdAt, Instant updatedAt) {
	}

	private static final String COLS = """
			id::text, reporter_account_id, target_type, target_id, reason, description,
			status, handler_account_id, resolution_note, handled_at, created_at, updated_at
			""";

	public Mono<ComplaintRow> insert(String reporterAccountId, String targetType, String targetId,
			String reason, String description) {
		return db.sql("""
				INSERT INTO user_complaint (
				    reporter_account_id, target_type, target_id, reason, description)
				VALUES (:reporter, :targetType, :targetId, :reason, :description)
				RETURNING """ + " " + COLS)
				.bind("reporter", reporterAccountId)
				.bind("targetType", targetType)
				.bind("targetId", nullable(targetId))
				.bind("reason", reason)
				.bind("description", description)
				.map(UserComplaintRepository::map).one();
	}

	/** 同举报人 + 同对象 + 同原因的未办结投诉（防重复提交刷屏；target_id 为 null 时按 IS NOT DISTINCT 对齐）。 */
	public Mono<ComplaintRow> findOpenDuplicate(String reporterAccountId, String targetType, String targetId,
			String reason) {
		return db.sql("SELECT " + COLS + " FROM user_complaint"
				+ " WHERE reporter_account_id=:reporter AND target_type=:targetType"
				+ " AND target_id IS NOT DISTINCT FROM :targetId AND reason=:reason"
				+ " AND status IN ('open', 'processing')")
				.bind("reporter", reporterAccountId).bind("targetType", targetType)
				.bind("targetId", nullable(targetId)).bind("reason", reason)
				.map(UserComplaintRepository::map).one();
	}

	public Flux<ComplaintRow> findByReporter(String reporterAccountId, int limit) {
		return db.sql("SELECT " + COLS + " FROM user_complaint"
				+ " WHERE reporter_account_id=:reporter ORDER BY created_at DESC LIMIT :limit")
				.bind("reporter", reporterAccountId).bind("limit", limit)
				.map(UserComplaintRepository::map).all();
	}

	/** 客服处置队列（默认 open；resolved/dismissed 查办结史）。 */
	public Flux<ComplaintRow> listQueue(String status, int limit) {
		return db.sql("SELECT " + COLS + " FROM user_complaint"
				+ " WHERE status=:status ORDER BY created_at DESC LIMIT :limit")
				.bind("status", status).bind("limit", limit)
				.map(UserComplaintRepository::map).all();
	}

	public Mono<ComplaintRow> find(UUID id) {
		return db.sql("SELECT " + COLS + " FROM user_complaint WHERE id=CAST(:id AS uuid)")
				.bind("id", id.toString()).map(UserComplaintRepository::map).one();
	}

	/** 处置：processing（受理）/ resolved（办结）/ dismissed（不成立）；可重判修正（幂等 UPDATE）。 */
	public Mono<ComplaintRow> handle(UUID id, String status, String handlerAccountId, String note) {
		var spec = db.sql("""
				UPDATE user_complaint SET
				    status=:status,
				    handler_account_id=:handler,
				    resolution_note=:note,
				    handled_at=now(),
				    updated_at=now()
				WHERE id=CAST(:id AS uuid)
				RETURNING """ + " " + COLS)
				.bind("id", id.toString()).bind("status", status).bind("handler", handlerAccountId);
		if (note == null || note.isBlank()) {
			spec = spec.bindNull("note", String.class);
		} else {
			spec = spec.bind("note", note.trim());
		}
		return spec.map(UserComplaintRepository::map).one();
	}

	private static ComplaintRow map(Readable row) {
		return new ComplaintRow(UUID.fromString(row.get("id", String.class)),
				row.get("reporter_account_id", String.class), row.get("target_type", String.class),
				row.get("target_id", String.class), row.get("reason", String.class),
				row.get("description", String.class), row.get("status", String.class),
				row.get("handler_account_id", String.class), row.get("resolution_note", String.class),
				toInstant(row.get("handled_at", OffsetDateTime.class)),
				toInstant(row.get("created_at", OffsetDateTime.class)),
				toInstant(row.get("updated_at", OffsetDateTime.class)));
	}

	private static String nullable(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static Instant toInstant(OffsetDateTime at) {
		return at == null ? null : at.toInstant();
	}
}
