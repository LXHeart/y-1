package com.grassland.trust.judge;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 审判官运营准入审计。仓储只提供 append/read，数据库触发器同时禁止 update/delete。 */
@Component
public class JudgeAdmissionAuditRepository {

    private final DatabaseClient db;

    public JudgeAdmissionAuditRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<JudgeAdmissionAudit> append(String judgeId, boolean admitted, String actorAccountId,
                                            String reason, long previousVersion) {
        return appendAction(judgeId, admitted ? "granted" : "revoked", actorAccountId, reason, previousVersion);
    }

    /** 任务书 #74 卡 E：扩展 action（probation/promoted/suspended/reinstated）的审计追加。 */
    public Mono<JudgeAdmissionAudit> appendAction(String judgeId, String action, String actorAccountId,
                                                  String reason, long previousVersion) {
        return db.sql("""
                INSERT INTO judge_admission_audit(
                    judge_id, action, actor_account_id, reason, previous_version, new_version)
                VALUES (CAST(:judge AS uuid), :action, CAST(:actor AS uuid), :reason, :previous, :next)
                RETURNING id, judge_id::text, action, actor_account_id::text, reason,
                          previous_version, new_version, created_at
                """)
                .bind("judge", judgeId)
                .bind("action", action)
                .bind("actor", actorAccountId)
                .bind("reason", reason)
                .bind("previous", previousVersion)
                .bind("next", previousVersion + 1)
                .map(JudgeAdmissionAuditRepository::map)
                .one();
    }

    public Flux<JudgeAdmissionAudit> listByJudge(String judgeId) {
        return db.sql("""
                SELECT id, judge_id::text, action, actor_account_id::text, reason,
                       previous_version, new_version, created_at
                FROM judge_admission_audit
                WHERE judge_id = CAST(:judge AS uuid)
                ORDER BY id
                """)
                .bind("judge", judgeId)
                .map(JudgeAdmissionAuditRepository::map)
                .all();
    }

    private static JudgeAdmissionAudit map(Readable row) {
        return new JudgeAdmissionAudit(
                row.get("id", Long.class),
                row.get("judge_id", String.class),
                row.get("action", String.class),
                row.get("actor_account_id", String.class),
                row.get("reason", String.class),
                row.get("previous_version", Long.class),
                row.get("new_version", Long.class),
                toInstant(row.get("created_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
