package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 履约交付物附件数据访问（草场 Slice 11 Stage 2，R2DBC 手写 SQL，house style）。
 *
 * <p>镜像 {@link SubmissionRepository}。附件跨服务引用 intelligence 的 media_reference（无 FK），
 * 挂接时快照 mime_type/size_bytes。三条查询：
 * <ul>
 *   <li>{@link #attach}：单条多行 INSERT（单语句原子，任一行冲突整批回滚）→ RETURNING 全量；
 *       唯一约束冲突（同一 submission 已挂同一 media）→ empty（调用方在事务内转 409，触发整事务回滚，零残留）。</li>
 *   <li>{@link #findBySubmissionIds}：商家查看交付物列表时按 submission 批量取附件（避免 N+1）。</li>
 *   <li>{@link #findOne}：下载端点证 media 确挂该 submission（JOIN submission 限定 application，防跨履约越权）。</li>
 * </ul>
 */
@Component
public class SubmissionAttachmentRepository {

    private static final String SELECT_COLS =
            "id::text, submission_id::text, media_reference_id::text, mime_type, size_bytes, created_at";

    private final DatabaseClient db;

    public SubmissionAttachmentRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 挂接附件（单条多行 INSERT，原子）。冲突（同一 submission 已挂同一 media）→ empty；
     * 调用方在事务内把 empty 转成 409 错误，使整事务（submission 创建 + 挂接 + outbox）回滚，不留孤儿。
     */
    public Mono<List<EngagementSubmissionAttachment>> attach(String submissionId, List<AttachmentInput> inputs) {
        if (inputs.isEmpty()) {
            return Mono.just(List.of());
        }
        StringBuilder sql = new StringBuilder("""
                INSERT INTO engagement_submission_attachment(id, submission_id, media_reference_id, mime_type, size_bytes)
                VALUES
                """);
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) {
                sql.append(",\n");
            }
            sql.append(" (CAST(:id").append(i).append(" AS uuid), CAST(:sub AS uuid), CAST(:mid")
                    .append(i).append(" AS uuid), :mime").append(i).append(", :size").append(i).append(")");
        }
        sql.append("\n RETURNING ").append(SELECT_COLS);

        GenericExecuteSpec spec = db.sql(sql.toString()).bind("sub", UUID.fromString(submissionId));
        for (int i = 0; i < inputs.size(); i++) {
            AttachmentInput in = inputs.get(i);
            spec = spec.bind("id" + i, UUID.randomUUID().toString())
                    .bind("mid" + i, in.mediaId().toString());
            spec = bindNullable(spec, "mime" + i, in.mimeType());
            spec = bindNullableLong(spec, "size" + i, in.sizeBytes());
        }
        return spec.map(SubmissionAttachmentRepository::map).all().collectList()
                .onErrorResume(DataIntegrityViolationException.class, e -> Mono.empty());
    }

    /** 按 submission 批量取附件（商家查看交付物列表时，一次查全避免 N+1）。空入参 → 空 Flux。 */
    public Flux<EngagementSubmissionAttachment> findBySubmissionIds(List<String> submissionIds) {
        if (submissionIds.isEmpty()) {
            return Flux.empty();
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLS)
                .append(" FROM engagement_submission_attachment WHERE submission_id IN (");
        for (int i = 0; i < submissionIds.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append(":sid").append(i);
        }
        sql.append(')');

        GenericExecuteSpec spec = db.sql(sql.toString());
        for (int i = 0; i < submissionIds.size(); i++) {
            spec = spec.bind("sid" + i, UUID.fromString(submissionIds.get(i)));
        }
        return spec.map(SubmissionAttachmentRepository::map).all();
    }

    /**
     * 取一条附件，并证其确属该 submission（JOIN engagement_submission 限定 application_id，
     * 防止调用方用别家履约的 submissionId 越权下载）。下载端点用。
     */
    public Mono<EngagementSubmissionAttachment> findOne(String appId, String submissionId, String mediaReferenceId) {
        return db.sql("""
                SELECT a.id::text, a.submission_id::text, a.media_reference_id::text,
                       a.mime_type, a.size_bytes, a.created_at
                FROM engagement_submission_attachment a
                JOIN engagement_submission s ON a.submission_id = s.id
                WHERE a.submission_id = CAST(:sid AS uuid)
                  AND a.media_reference_id = CAST(:mid AS uuid)
                  AND s.application_id = CAST(:app AS uuid)
                """)
                .bind("app", UUID.fromString(appId))
                .bind("sid", UUID.fromString(submissionId))
                .bind("mid", UUID.fromString(mediaReferenceId))
                .map(SubmissionAttachmentRepository::map).one();
    }

    private static EngagementSubmissionAttachment map(Readable row) {
        return new EngagementSubmissionAttachment(
                row.get("id", String.class),
                row.get("submission_id", String.class),
                row.get("media_reference_id", String.class),
                row.get("mime_type", String.class),
                row.get("size_bytes", Long.class),
                toInstant(row.get("created_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static GenericExecuteSpec bindNullableLong(GenericExecuteSpec spec, String name, Long value) {
        return (value == null) ? spec.bindNull(name, Long.class) : spec.bind(name, value);
    }
}
