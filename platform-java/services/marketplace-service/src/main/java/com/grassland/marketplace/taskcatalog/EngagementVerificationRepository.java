package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 履约核验数据访问（草场 Verification v1，R2DBC 手写 SQL，house style）。
 *
 * <p>镜像 {@link SubmissionRepository} / {@link SubmissionAttachmentRepository}。keyed on submission_id：
 * 一份交付物一份核验记录，商家触发 / 重跑均 {@code ON CONFLICT (submission_id) DO UPDATE} 原地 upsert。
 *
 * <p>checks 存 jsonb：写用 {@code CAST(:checks AS jsonb)} 绑 JSON 字符串，读用 {@code checks::text}
 * （与 {@code OutboxRepository} 的 payload 同款）。
 */
@Component
public class EngagementVerificationRepository {

    private static final String SELECT_COLS =
            "id::text, submission_id::text, status, checks::text, last_checked_at, created_at, updated_at";

    private final DatabaseClient db;

    public EngagementVerificationRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 原地 upsert 一份交付物的核验记录（商家触发核验 / 重跑均走此）。{@code ON CONFLICT (submission_id)}
     * 命中既有行则更新 status/checks 并刷新 last_checked_at；否则插入新行。返回最新行。
     */
    public Mono<EngagementVerification> upsert(String submissionId, String status, String checksJson) {
        return db.sql("""
                INSERT INTO engagement_verification(id, submission_id, status, checks)
                VALUES (CAST(:id AS uuid), CAST(:sub AS uuid), :status, CAST(:checks AS jsonb))
                ON CONFLICT (submission_id) DO UPDATE
                    SET status = :status, checks = CAST(:checks AS jsonb),
                        last_checked_at = now(), updated_at = now()
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", UUID.randomUUID().toString())
                .bind("sub", submissionId)
                .bind("status", status)
                .bind("checks", checksJson)
                .map(EngagementVerificationRepository::map).one();
    }

    /** 取一份交付物的核验记录（confirm 前置闸门、capture 安全网闸门、详情用）。无 → empty。 */
    public Mono<EngagementVerification> findBySubmission(String submissionId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM engagement_verification"
                + " WHERE submission_id = CAST(:sub AS uuid)")
                .bind("sub", submissionId)
                .map(EngagementVerificationRepository::map).one();
    }

    /**
     * 取一份交付物的**生效**核验状态（GL-P2-ADMIN-004）：有人工改判（verification_override）则 override 优先，
     * 否则回落自动结论（engagement_verification.status）。两者皆无 → empty。
     *
     * <p>供 confirm 闸门 / 结算阻断 / 运营队列三处统一调用，避免各自处理 override 逻辑。
     */
    public Mono<String> findEffectiveStatus(String submissionId) {
        return db.sql("""
                SELECT COALESCE(
                    (SELECT vo.status FROM verification_override vo WHERE vo.submission_id = CAST(:sub AS uuid)),
                    (SELECT v.status FROM engagement_verification v WHERE v.submission_id = CAST(:sub AS uuid))
                ) AS status
                """)
                .bind("sub", submissionId)
                .map(row -> row.get("status", String.class))
                .one();
    }

    /** 按 submission 批量取核验记录（商家查看交付物列表时，一次查全避免 N+1）。空入参 → 空 Flux。 */
    public Flux<EngagementVerification> findBySubmissions(List<String> submissionIds) {
        if (submissionIds.isEmpty()) {
            return Flux.empty();
        }
        StringBuilder sql = new StringBuilder("SELECT ").append(SELECT_COLS)
                .append(" FROM engagement_verification WHERE submission_id IN (");
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
        return spec.map(EngagementVerificationRepository::map).all();
    }

    private static EngagementVerification map(Readable row) {
        return new EngagementVerification(
                row.get("id", String.class),
                row.get("submission_id", String.class),
                row.get("status", String.class),
                row.get("checks", String.class),
                toInstant(row.get("last_checked_at", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
