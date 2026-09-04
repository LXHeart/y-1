package com.grassland.trust.precedent;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * precedent_case 数据访问（任务书 #74 卡 G）。UNIQUE(dispute_id) 幂等——retrial 多轮终局只一行。
 * JSONB 列以字符串读写（结构由服务层拼装与解析）。
 */
@Component
public class PrecedentRepository {

    private static final String COLS = "id::text, dispute_id::text, task_type, task_platform, dispute_kind,"
            + " focus, claims_summary, decision, final_via,"
            + " vote_summary::text, rationale_digest::text, created_at";

    private final DatabaseClient db;

    public PrecedentRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 终局即入库（幂等：UNIQUE(dispute_id) 冲突 → empty，调用方忽略）。 */
    public Mono<PrecedentCase> insert(PrecedentCase precedent) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO precedent_case(id, dispute_id, task_type, task_platform, dispute_kind,
                                           focus, claims_summary, decision, final_via,
                                           vote_summary, rationale_digest)
                VALUES (CAST(:id AS uuid), CAST(:dispute AS uuid), :taskType, :platform, :kind,
                        :focus, :claims, :decision, :finalVia,
                        CAST(:voteSummary AS jsonb), CAST(:rationaleDigest AS jsonb))
                ON CONFLICT (dispute_id) DO NOTHING
                RETURNING %s
                """.formatted(COLS))
                .bind("id", precedent.id())
                .bind("dispute", precedent.disputeId());
        GenericExecuteSpec spec2 = bindNullableString(spec, "taskType", precedent.taskType());
        spec2 = bindNullableString(spec2, "platform", precedent.taskPlatform());
        spec2 = bindNullableString(spec2, "kind", precedent.disputeKind());
        spec2 = bindNullableString(spec2, "focus", truncate(precedent.focus(), 200));
        spec2 = bindNullableString(spec2, "claims", precedent.claimsSummary());
        spec2 = bindNullableString(spec2, "decision", precedent.decision());
        spec2 = bindNullableString(spec2, "finalVia", precedent.finalVia());
        return spec2
                .bind("voteSummary", precedent.voteSummary() == null ? "{}" : precedent.voteSummary())
                .bind("rationaleDigest", precedent.rationaleDigest() == null ? "[]" : precedent.rationaleDigest())
                .map(PrecedentRepository::map).one();
    }

    /** 公开判例库列表（登录即可读，无 org 限定；filter + created_at 倒序 + offset 分页）。 */
    public Flux<PrecedentCase> list(String platform, String kind, String taskType, int limit, long offset) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (platform != null && !platform.isBlank()) {
            where.append(" AND task_platform = :platform");
        }
        if (kind != null && !kind.isBlank()) {
            where.append(" AND dispute_kind = :kind");
        }
        if (taskType != null && !taskType.isBlank()) {
            where.append(" AND task_type = :taskType");
        }
        var spec = db.sql("SELECT " + COLS + " FROM precedent_case" + where
                        + " ORDER BY created_at DESC, id LIMIT :limit OFFSET :offset")
                .bind("limit", limit)
                .bind("offset", offset);
        if (platform != null && !platform.isBlank()) {
            spec = spec.bind("platform", platform);
        }
        if (kind != null && !kind.isBlank()) {
            spec = spec.bind("kind", kind);
        }
        if (taskType != null && !taskType.isBlank()) {
            spec = spec.bind("taskType", taskType);
        }
        return spec.map(PrecedentRepository::map).all();
    }

    public Mono<Long> count(String platform, String kind, String taskType) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (platform != null && !platform.isBlank()) {
            where.append(" AND task_platform = :platform");
        }
        if (kind != null && !kind.isBlank()) {
            where.append(" AND dispute_kind = :kind");
        }
        if (taskType != null && !taskType.isBlank()) {
            where.append(" AND task_type = :taskType");
        }
        var spec = db.sql("SELECT COUNT(*)::bigint AS c FROM precedent_case" + where);
        if (platform != null && !platform.isBlank()) {
            spec = spec.bind("platform", platform);
        }
        if (kind != null && !kind.isBlank()) {
            spec = spec.bind("kind", kind);
        }
        if (taskType != null && !taskType.isBlank()) {
            spec = spec.bind("taskType", taskType);
        }
        return spec.map(r -> r.get("c", Long.class)).one().defaultIfEmpty(0L);
    }

    public Mono<PrecedentCase> findById(String id) {
        return db.sql("SELECT " + COLS + " FROM precedent_case WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map(PrecedentRepository::map).one();
    }

    private static GenericExecuteSpec bindNullableString(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static PrecedentCase map(Readable row) {
        return new PrecedentCase(
                row.get("id", String.class),
                row.get("dispute_id", String.class),
                row.get("task_type", String.class),
                row.get("task_platform", String.class),
                row.get("dispute_kind", String.class),
                row.get("focus", String.class),
                row.get("claims_summary", String.class),
                row.get("decision", String.class),
                row.get("final_via", String.class),
                row.get("vote_summary", String.class),
                row.get("rationale_digest", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
