package com.grassland.trust.judge;

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
 * 准入考试题库 / 考试记录数据访问（任务书 #74 卡 E，V14）。
 * 题库 UPDATE 即 version+1（乐观锁，治理台维护）；考试记录只追加。
 */
@Component
public class JudgeExamRepository {

    private static final String QUESTION_COLS =
            "id::text, category, question, options::text, answer_index, active, version, created_at";
    private static final String ATTEMPT_COLS =
            "id::text, account_id::text, score, passed, answers::text, created_at";

    private final DatabaseClient db;

    public JudgeExamRepository(DatabaseClient db) {
        this.db = db;
    }

    // ---------- 题库（治理台 CRUD）----------

    public Mono<JudgeExamQuestion> insertQuestion(JudgeExamQuestion q) {
        return db.sql("""
                INSERT INTO judge_exam_question(id, category, question, options, answer_index, active)
                VALUES (CAST(:id AS uuid), :category, :question, CAST(:options AS jsonb), :answerIndex, :active)
                RETURNING %s
                """.formatted(QUESTION_COLS))
                .bind("id", q.id() == null ? UUID.randomUUID().toString() : q.id())
                .bind("category", q.category())
                .bind("question", q.question())
                .bind("options", q.optionsJson())
                .bind("answerIndex", q.answerIndex())
                .bind("active", q.active())
                .map(JudgeExamRepository::mapQuestion).one();
    }

    /** 更新（乐观锁）：version 匹配才更新并 version+1。0 行 → empty。 */
    public Mono<JudgeExamQuestion> updateQuestion(String id, String category, String question, String optionsJson,
                                                  Integer answerIndex, Boolean active, long expectedVersion) {
        StringBuilder sets = new StringBuilder(
                "version = version + 1");
        if (category != null) {
            sets.append(", category = :category");
        }
        if (question != null) {
            sets.append(", question = :question");
        }
        if (optionsJson != null) {
            sets.append(", options = CAST(:options AS jsonb)");
        }
        if (answerIndex != null) {
            sets.append(", answer_index = :answerIndex");
        }
        if (active != null) {
            sets.append(", active = :active");
        }
        var spec = db.sql("UPDATE judge_exam_question SET " + sets
                        + " WHERE id = CAST(:id AS uuid) AND version = :expected RETURNING " + QUESTION_COLS)
                .bind("id", id).bind("expected", expectedVersion);
        if (category != null) {
            spec = spec.bind("category", category);
        }
        if (question != null) {
            spec = spec.bind("question", question);
        }
        if (optionsJson != null) {
            spec = spec.bind("options", optionsJson);
        }
        if (answerIndex != null) {
            spec = spec.bind("answerIndex", answerIndex);
        }
        if (active != null) {
            spec = spec.bind("active", active);
        }
        return spec.map(JudgeExamRepository::mapQuestion).one();
    }

    /** 下线题（软删 active=false，保留历史 attempt 的可解释性）。 */
    public Mono<JudgeExamQuestion> deactivateQuestion(String id) {
        return db.sql("""
                UPDATE judge_exam_question SET active = false, version = version + 1
                WHERE id = CAST(:id AS uuid) AND active = true
                RETURNING %s
                """.formatted(QUESTION_COLS))
                .bind("id", id)
                .map(JudgeExamRepository::mapQuestion).one();
    }

    public Flux<JudgeExamQuestion> listQuestions(boolean activeOnly, int limit) {
        return db.sql("SELECT " + QUESTION_COLS + " FROM judge_exam_question"
                        + (activeOnly ? " WHERE active = true" : "")
                        + " ORDER BY created_at DESC LIMIT :limit")
                .bind("limit", limit)
                .map(JudgeExamRepository::mapQuestion).all();
    }

    /** 用户端出题：active 题库随机 N 题（<b>不含</b> answer_index——出题响应不泄答案）。 */
    public Flux<JudgeExamQuestion> drawQuestions(int count) {
        return db.sql("""
                SELECT %s FROM judge_exam_question WHERE active = true ORDER BY random() LIMIT :count
                """.formatted("id::text, category, question, options::text, -1 AS answer_index, active, version, created_at"))
                .bind("count", count)
                .map(JudgeExamRepository::mapQuestion).all();
    }

    public Mono<Long> countActiveQuestions() {
        return db.sql("SELECT COUNT(*)::bigint AS c FROM judge_exam_question WHERE active = true")
                .map(r -> r.get("c", Long.class)).one().defaultIfEmpty(0L);
    }

    // ---------- 考试记录 ----------

    public Mono<JudgeExamAttempt> insertAttempt(JudgeExamAttempt attempt) {
        return db.sql("""
                INSERT INTO judge_exam_attempt(id, account_id, score, passed, answers)
                VALUES (CAST(:id AS uuid), CAST(:account AS uuid), :score, :passed, CAST(:answers AS jsonb))
                RETURNING %s
                """.formatted(ATTEMPT_COLS))
                .bind("id", attempt.id())
                .bind("account", attempt.accountId())
                .bind("score", attempt.score())
                .bind("passed", attempt.passed())
                .bind("answers", attempt.answersJson())
                .map(JudgeExamRepository::mapAttempt).one();
    }

    /** 最近一次交卷（不及格 24h 冷却判定）。 */
    public Mono<JudgeExamAttempt> lastAttempt(String accountId) {
        return db.sql("SELECT " + ATTEMPT_COLS + " FROM judge_exam_attempt"
                        + " WHERE account_id = CAST(:account AS uuid) AND score >= 0"
                        + " ORDER BY created_at DESC LIMIT 1")
                .bind("account", accountId)
                .map(JudgeExamRepository::mapAttempt).one();
    }

    /** 治理台：考试记录（按时间倒序）。 */
    public Flux<JudgeExamAttempt> listAttempts(int limit) {
        return db.sql("SELECT " + ATTEMPT_COLS + " FROM judge_exam_attempt ORDER BY created_at DESC LIMIT :limit")
                .bind("limit", limit)
                .map(JudgeExamRepository::mapAttempt).all();
    }

    /** 卡 E 考核看板：90 天窗口聚合（分配面板数、实投数、弃权数）+ 当前挂起态（恢复入口）。 */
    public Flux<JudgeWorkload> listWorkloads(Instant since, int limit) {
        return db.sql("""
                SELECT j.account_id::text AS account_id,
                       (SELECT COUNT(*)::int FROM dispute_panel_assignment p
                         WHERE p.judge_account_id = j.account_id AND p.assigned_at >= :since) AS assigned,
                       (SELECT COUNT(*)::int FROM dispute_vote v
                         WHERE v.judge_account_id = j.account_id AND v.voted_at >= :since
                               AND v.vote <> 'abstain') AS voted,
                       (SELECT COUNT(*)::int FROM dispute_vote v
                         WHERE v.judge_account_id = j.account_id AND v.voted_at >= :since
                               AND v.vote = 'abstain') AS abstained,
                       (j.suspended_until IS NOT NULL AND j.suspended_until >= now()) AS suspended_now
                FROM judge j
                WHERE j.active = true
                ORDER BY assigned DESC, j.account_id
                LIMIT :limit
                """)
                .bind("since", OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC))
                .bind("limit", limit)
                .map(r -> new JudgeWorkload(
                        r.get("account_id", String.class),
                        nvl(r.get("assigned", Integer.class)),
                        nvl(r.get("voted", Integer.class)),
                        nvl(r.get("abstained", Integer.class)),
                        Boolean.TRUE.equals(r.get("suspended_now", Boolean.class)))).all();
    }

    /** 考核看板行：分配/实投/弃权（弃权率 = abstain/已分配，由服务层算）。 */
    public record JudgeWorkload(String accountId, int assigned, int voted, int abstained, boolean suspendedNow) {
        public double abstainRate() {
            return assigned <= 0 ? 0.0 : (double) abstained / assigned;
        }
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    private static JudgeExamQuestion mapQuestion(Readable row) {
        return new JudgeExamQuestion(
                row.get("id", String.class),
                row.get("category", String.class),
                row.get("question", String.class),
                row.get("options", String.class),
                row.get("answer_index", Integer.class) == null ? -1 : row.get("answer_index", Integer.class),
                Boolean.TRUE.equals(row.get("active", Boolean.class)),
                row.get("version", Long.class) == null ? 0L : row.get("version", Long.class),
                toInstant(row.get("created_at", OffsetDateTime.class)));
    }

    private static JudgeExamAttempt mapAttempt(Readable row) {
        return new JudgeExamAttempt(
                row.get("id", String.class),
                row.get("account_id", String.class),
                row.get("score", Integer.class),
                Boolean.TRUE.equals(row.get("passed", Boolean.class)),
                row.get("answers", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
