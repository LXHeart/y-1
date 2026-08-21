package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code comment_safety_review} 仓储（缺口清偿之九遗留清偿 + V48 履约硬门槛）：履约提交自由文本
 * （评论 commentText / 备注 note）词库 advisory（low/medium）命中的人工复核队列， 每 提交×字段一行。
 * 提交链路落 open 行（失败 advisory——不影响提交本身）；运营复核 confirmed/violation；violation 经
 * {@link #findViolations} 在交付物列表透出 commentFlagged。 设计对齐 verification_override：不改自动词库
 * 检查真相，人工结论独立成表。
 */
@Component
public class CommentSafetyReviewRepository {

	/** 复核字段（V48）：评论正文 / 提交备注。 */
	public static final String FIELD_COMMENT = "comment";
	public static final String FIELD_NOTE = "note";

	private final DatabaseClient db;

	public CommentSafetyReviewRepository(DatabaseClient db) {
		this.db = db;
	}

	public record ReviewRow(UUID submissionId, String field, String commentText, String findingsJson,
			String lexiconVersion, String status, String reviewerAccountId, String reviewNote, Instant reviewedAt,
			Instant createdAt) {
	}

	/** 运营队列行：复核结论 + 任务/履约上下文（运营判定需要看任务与提交时间）。 */
	public record QueueRow(UUID submissionId, String field, String commentText, String findingsJson, String status,
			Instant createdAt, String taskId, String taskTitle, String platform, String recommenderAccountId,
			Instant submittedAt, String submissionStatus, String reviewerAccountId, String reviewNote,
			Instant reviewedAt) {
	}

	/** 提交链路落 open 行；同 submission×field 幂等（DO NOTHING——重放不覆盖既有复核结论）。 */
	public Mono<Void> recordOpen(String submissionId, String field, String commentText, String findingsJson,
			String lexiconVersion) {
		return db.sql("""
				INSERT INTO comment_safety_review (
				    submission_id, field, comment_text, findings, lexicon_version, status)
				VALUES (CAST(:submission AS uuid), :field, :commentText, CAST(:findings AS jsonb),
				        :lexiconVersion, 'open')
				ON CONFLICT (submission_id, field) DO NOTHING
				""").bind("submission", submissionId).bind("field", field).bind("commentText", commentText)
				.bind("findings", findingsJson)
				.bind("lexiconVersion", lexiconVersion == null || lexiconVersion.isBlank() ? null : lexiconVersion)
				.then();
	}

	/** 交付物列表 commentFlagged 数据源：violation 的 submission id 集合。 */
	public Flux<String> findViolations(List<String> submissionIds) {
		if (submissionIds == null || submissionIds.isEmpty()) {
			return Flux.empty();
		}
		// 编号 IN 绑定（对齐 SubmissionAttachmentRepository.findBySubmissionIds，避免数组绑定差异）
		StringBuilder sql = new StringBuilder("SELECT submission_id::text FROM comment_safety_review"
				+ " WHERE status = 'violation' AND submission_id IN (");
		for (int i = 0; i < submissionIds.size(); i++) {
			sql.append(i > 0 ? "," : "").append(":sid").append(i);
		}
		sql.append(')');
		var spec = db.sql(sql.toString());
		for (int i = 0; i < submissionIds.size(); i++) {
			spec = spec.bind("sid" + i, UUID.fromString(submissionIds.get(i)));
		}
		return spec.map(row -> row.get(0, String.class)).all();
	}

	/** 运营队列（默认 open；confirmed/violation 查复核史）。 */
	public Flux<QueueRow> listQueue(String status, int limit) {
		return db.sql("""
				SELECT r.submission_id::text, r.field, r.comment_text, r.findings::text AS findings_json,
				       r.status, r.created_at, t.id::text AS task_id, t.title AS task_title,
				       t.platform, s.recommender_account_id::text AS recommender_account_id,
				       s.created_at AS submitted_at, s.status AS submission_status,
				       r.reviewer_account_id, r.review_note, r.reviewed_at
				FROM comment_safety_review r
				JOIN engagement_submission s ON s.id = r.submission_id
				JOIN task_application a ON a.id = s.application_id
				JOIN task t ON t.id = a.task_id
				WHERE r.status = :status
				ORDER BY r.created_at DESC
				LIMIT :limit
				""").bind("status", status).bind("limit", limit).map(CommentSafetyReviewRepository::mapQueueRow).all();
	}

	public Mono<ReviewRow> find(String submissionId, String field) {
		return db.sql("""
				SELECT submission_id::text, field, comment_text, findings::text AS findings_json,
				       lexicon_version, status, reviewer_account_id, review_note, reviewed_at, created_at
				FROM comment_safety_review
				WHERE submission_id = CAST(:submission AS uuid) AND field = :field
				""").bind("submission", submissionId).bind("field", field)
				.map(CommentSafetyReviewRepository::mapRow).one();
	}

	/**
	 * 人工复核：confirmed（无问题）/ violation（违规）。可重判（upsert 修正，对齐 verification_override
	 * 口径）；行由提交链路创建，无行 = 该字段从未命中 advisory → 404。
	 */
	public Mono<ReviewRow> decide(String submissionId, String field, String status, String reviewerAccountId,
			String reviewNote) {
		var spec = db.sql("""
				UPDATE comment_safety_review SET
				    status = :status,
				    reviewer_account_id = :reviewer,
				    review_note = :note,
				    reviewed_at = now()
				WHERE submission_id = CAST(:submission AS uuid) AND field = :field
				RETURNING submission_id::text, field, comment_text, findings::text AS findings_json,
				          lexicon_version, status, reviewer_account_id, review_note,
				          reviewed_at, created_at
				""").bind("submission", submissionId).bind("field", field).bind("status", status)
				.bind("reviewer", reviewerAccountId);
		if (reviewNote == null || reviewNote.isBlank()) {
			spec = spec.bindNull("note", String.class);
		} else {
			spec = spec.bind("note", reviewNote.trim());
		}
		return spec.map(CommentSafetyReviewRepository::mapRow).one();
	}

	private static ReviewRow mapRow(Readable row) {
		return new ReviewRow(UUID.fromString(row.get("submission_id", String.class)), row.get("field", String.class),
				row.get("comment_text", String.class), row.get("findings_json", String.class),
				row.get("lexicon_version", String.class), row.get("status", String.class),
				row.get("reviewer_account_id", String.class), row.get("review_note", String.class),
				toInstant(row.get("reviewed_at", java.time.OffsetDateTime.class)),
				toInstant(row.get("created_at", java.time.OffsetDateTime.class)));
	}

	private static QueueRow mapQueueRow(Readable row) {
		return new QueueRow(UUID.fromString(row.get("submission_id", String.class)), row.get("field", String.class),
				row.get("comment_text", String.class), row.get("findings_json", String.class),
				row.get("status", String.class), toInstant(row.get("created_at", java.time.OffsetDateTime.class)),
				row.get("task_id", String.class), row.get("task_title", String.class),
				row.get("platform", String.class), row.get("recommender_account_id", String.class),
				toInstant(row.get("submitted_at", java.time.OffsetDateTime.class)),
				row.get("submission_status", String.class), row.get("reviewer_account_id", String.class),
				row.get("review_note", String.class),
				toInstant(row.get("reviewed_at", java.time.OffsetDateTime.class)));
	}

	private static Instant toInstant(java.time.OffsetDateTime at) {
		return at == null ? null : at.toInstant();
	}
}
