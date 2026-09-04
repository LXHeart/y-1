package com.grassland.trust.judge;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.trust.security.TrustCallerResolver;
import com.grassland.trust.security.TrustException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 任务书 #74 卡 E 治理台入口（PLATFORM_ADMIN）。
 *
 * <ul>
 *   <li>题库 CRUD（UPDATE 走乐观锁 version+1；DELETE=软删下线）；</li>
 *   <li>考试记录查询（attempt 留痕）；</li>
 *   <li>「审判官考核」看板：90 天窗口实时聚合（分配/实投/弃权率），弃权率 &gt;40% 且分配 ≥5 次标记
 *       「建议暂停」——v1 运营确认制，无自动挂起定时器（派生 4 降格，任务书卡 E 改动点 6）；</li>
 *   <li>挂起 30 天 / 恢复（suspended_until + audit 'suspended'/'reinstated' + JudgeSuspended/JudgeReinstated 通知）。</li>
 * </ul>
 */
@RestController
public class JudgeExamAdminController {

    private static final int MAX_LIST_LIMIT = 100;
    /** 考核看板窗口（任务书卡 E：90 天）。 */
    private static final Duration ASSESSMENT_WINDOW = Duration.ofDays(90);
    /** 建议暂停阈值：弃权率 >40% 且分配 ≥5 次。 */
    private static final double ABSTAIN_RATE_THRESHOLD = 0.40;
    private static final int MIN_ASSIGNMENTS_FOR_REVIEW = 5;
    /** 挂起时长（运营确认制一键执行）。 */
    private static final Duration SUSPENSION_DURATION = Duration.ofDays(30);

    private final TrustCallerResolver callers;
    private final JudgeExamRepository exams;
    private final JudgeRepository judges;
    private final JudgeAdmissionAuditRepository audits;
    private final OutboxRepository outbox;
    private final org.springframework.transaction.reactive.TransactionalOperator transactions;

    public JudgeExamAdminController(TrustCallerResolver callers, JudgeExamRepository exams,
                                    JudgeRepository judges, JudgeAdmissionAuditRepository audits,
                                    OutboxRepository outbox,
                                    org.springframework.transaction.reactive.TransactionalOperator transactions) {
        this.callers = callers;
        this.exams = exams;
        this.judges = judges;
        this.audits = audits;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    // ---------- 题库 ----------

    @GetMapping("/api/admin/trust/judge-exam/questions")
    public Mono<ResponseEntity<Map<String, Object>>> listQuestions(
            @RequestParam(defaultValue = "false") boolean activeOnly,
            @RequestParam(defaultValue = "100") int limit, ServerHttpRequest request) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .then(Mono.fromCallable(() -> bounded))
                .flatMap(bound -> exams.listQuestions(activeOnly, bound).collectList()
                        .map(rows -> ResponseEntity.ok(Map.of("success", true,
                                "data", Map.of("items", rows.stream().map(this::questionBody).toList())))));
    }

    @PostMapping("/api/admin/trust/judge-exam/questions")
    public Mono<ResponseEntity<Map<String, Object>>> createQuestion(@RequestBody QuestionUpsertRequest body,
                                                                    ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(admin -> {
                    QuestionUpsert validated = validate(body, true);
                    JudgeExamQuestion q = new JudgeExamQuestion(UUID.randomUUID().toString(),
                            validated.category(), validated.question(), validated.optionsJson(),
                            validated.answerIndex(), true, 0, Instant.now());
                    return exams.insertQuestion(q).map(created ->
                            ResponseEntity.ok(Map.of("success", true, "data", questionBody(created))));
                });
    }

    @PutMapping("/api/admin/trust/judge-exam/questions/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> updateQuestion(@PathVariable String id,
                                                                    @RequestBody QuestionUpsertRequest body,
                                                                    ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(admin -> {
                    QuestionUpsert validated = validate(body, false);
                    if (body.expectedVersion() == null || body.expectedVersion() < 0) {
                        return fail(400, "expectedVersion 必填（乐观锁）");
                    }
                    return exams.updateQuestion(id, validated.category(), validated.question(),
                                    validated.optionsJson(), validated.answerIndex(),
                                    body.active(), body.expectedVersion())
                            .map(this::okQuestion)
                            .switchIfEmpty(fail(409, "版本冲突或题目不存在，请刷新后重试"));
                });
    }

    @DeleteMapping("/api/admin/trust/judge-exam/questions/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> deleteQuestion(@PathVariable String id,
                                                                    ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(admin -> exams.deactivateQuestion(id)
                        .map(this::okQuestion)
                        .switchIfEmpty(fail(409, "题目不存在或已下线")));
    }

    // ---------- 考试记录 ----------

    @GetMapping("/api/admin/trust/judge-exam/attempts")
    public Mono<ResponseEntity<Map<String, Object>>> listAttempts(
            @RequestParam(defaultValue = "50") int limit, ServerHttpRequest request) {
        int bounded = Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .then(Mono.fromCallable(() -> bounded))
                .flatMap(bound -> exams.listAttempts(bound).collectList()
                        .map(rows -> ResponseEntity.ok(Map.of("success", true,
                                "data", Map.of("items", rows.stream().map(this::attemptBody).toList())))));
    }

    // ---------- 考核看板（实时聚合，不引入调度基建）----------

    @GetMapping("/api/admin/trust/judges/assessment")
    public Mono<ResponseEntity<Map<String, Object>>> assessment(ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .then(exams.listWorkloads(Instant.now().minus(ASSESSMENT_WINDOW), MAX_LIST_LIMIT).collectList())
                .map(rows -> {
                    java.util.List<Map<String, Object>> items = rows.stream()
                            .map(w -> {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("accountId", w.accountId());
                                m.put("assigned", w.assigned());
                                m.put("voted", w.voted());
                                m.put("abstained", w.abstained());
                                m.put("abstainRate", Math.round(w.abstainRate() * 1000.0) / 1000.0);
                                m.put("suggestSuspension",
                                        w.assigned() >= MIN_ASSIGNMENTS_FOR_REVIEW
                                                && w.abstainRate() > ABSTAIN_RATE_THRESHOLD);
                                return m;
                            })
                            .toList();
                    return ResponseEntity.ok(Map.of("success", true,
                            "data", Map.of("windowDays", ASSESSMENT_WINDOW.toDays(), "items", items)));
                });
    }

    // ---------- 挂起 / 恢复（运营确认制）----------

    @PostMapping("/api/admin/trust/judges/{accountId}/suspension")
    public Mono<ResponseEntity<Map<String, Object>>> suspension(@PathVariable String accountId,
                                                                @RequestBody SuspensionRequest body,
                                                                ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.PLATFORM_ADMIN)
                .flatMap(admin -> {
                    if (body == null || body.suspend() == null) {
                        return fail(400, "suspend 必填");
                    }
                    boolean suspend = body.suspend();
                    String reason = body.reason() == null ? "" : body.reason().trim();
                    if (suspend && reason.isEmpty()) {
                        return fail(400, "挂起须填写理由（1-500 字）");
                    }
                    return judges.findByAccountId(accountId)
                            .switchIfEmpty(fail(404, "审判官不存在"))
                            .flatMap(judge -> {
                                if (suspend && judge.suspendedNow()) {
                                    return fail(409, "该审判官已处于挂起期");
                                }
                                if (!suspend && !judge.suspendedNow()) {
                                    return fail(409, "该审判官不在挂起期");
                                }
                                Instant until = suspend ? Instant.now().plus(SUSPENSION_DURATION) : null;
                                return judges.updateSuspension(accountId, suspend, until, suspend ? reason : null)
                                        .flatMap(updated -> {
                                            String action = suspend ? "suspended" : "reinstated";
                                            return transactions
                                                    .transactional(audits.appendAction(updated.id(), action,
                                                                    admin.accountId(), reason,
                                                                    updated.version() - 1)
                                                            .then(outbox.append(suspensionEnvelope(action, accountId)))
                                                            .thenReturn(updated));
                                        })
                                        .map(updated -> {
                                            Map<String, Object> data = new LinkedHashMap<>();
                                            data.put("accountId", updated.accountId());
                                            data.put("suspendedNow", updated.suspendedNow());
                                            data.put("suspendedUntil", updated.suspendedUntil() == null ? null
                                                    : updated.suspendedUntil().toString());
                                            data.put("suspensionReason", updated.suspensionReason());
                                            return ResponseEntity.ok(Map.of("success", true, "data", data));
                                        });
                            });
                });
    }

    /** 挂起/恢复通知事件（identity：JudgeSuspended / JudgeReinstated，收件人=judgeAccountId）。 */
    private EventEnvelope suspensionEnvelope(String action, String judgeAccountId) {
        String eventId = UUID.nameUUIDFromBytes(("JudgeSuspension:" + action + ":" + judgeAccountId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("judgeAccountId", judgeAccountId);
        payload.put("action", action);
        return new EventEnvelope(eventId, "suspended".equals(action) ? "JudgeSuspended" : "JudgeReinstated",
                "Judge", judgeAccountId, 0, Instant.now(), null, payload);
    }

    private ResponseEntity<Map<String, Object>> okQuestion(JudgeExamQuestion q) {
        return ResponseEntity.ok(Map.of("success", true, "data", questionBody(q)));
    }

    private Map<String, Object> questionBody(JudgeExamQuestion q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", q.id());
        m.put("category", q.category());
        m.put("question", q.question());
        m.put("options", q.optionsJson());
        m.put("answerIndex", q.answerIndex());
        m.put("active", q.active());
        m.put("version", q.version());
        m.put("createdAt", q.createdAt() == null ? null : q.createdAt().toString());
        return m;
    }

    private Map<String, Object> attemptBody(JudgeExamAttempt attempt) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", attempt.id());
        m.put("accountId", attempt.accountId());
        m.put("score", attempt.score());
        m.put("passed", attempt.passed());
        m.put("answers", attempt.answersJson());
        m.put("createdAt", attempt.createdAt() == null ? null : attempt.createdAt().toString());
        return m;
    }

    private QuestionUpsert validate(QuestionUpsertRequest body, boolean requireAll) {
        if (body == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        String category = body.category() == null ? null : body.category().trim();
        String question = body.question() == null ? null : body.question().trim();
        String optionsJson = body.options() == null ? null : serializeOptions(body.options());
        Integer answerIndex = body.answerIndex();
        if (requireAll) {
            if (category == null || category.isEmpty() || category.length() > 32) {
                throw new IllegalArgumentException("category 必填（≤32 字）");
            }
            if (question == null || question.isEmpty()) {
                throw new IllegalArgumentException("question 必填");
            }
            if (optionsJson == null) {
                throw new IllegalArgumentException("options 必填（字符串数组 JSON）");
            }
            if (answerIndex == null) {
                throw new IllegalArgumentException("answerIndex 必填");
            }
        }
        if (optionsJson != null && body.options() != null && body.options().size() < 2) {
            throw new IllegalArgumentException("options 至少 2 项");
        }
        if (answerIndex != null && answerIndex < 0) {
            throw new IllegalArgumentException("answerIndex 不能为负");
        }
        return new QuestionUpsert(category, question, optionsJson, answerIndex);
    }

    private static String serializeOptions(java.util.List<String> options) {
        if (options.stream().anyMatch(o -> o == null || o.isBlank())) {
            throw new IllegalArgumentException("options 项不能为空");
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < options.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            String escaped = options.get(i).replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n");
            sb.append('"').append(escaped).append('"');
        }
        return sb.append(']').toString();
    }

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new TrustException(status, message));
    }

    private record QuestionUpsert(String category, String question, String optionsJson, Integer answerIndex) {}

    /** 题库增改请求体（options 为字符串数组，服务端序列化为 jsonb）；更新时 expectedVersion 必填。 */
    public record QuestionUpsertRequest(String category, String question, java.util.List<String> options,
                                        Integer answerIndex, Boolean active, Long expectedVersion) {}

    /** 挂起/恢复请求体。suspend=true 须带 reason。 */
    public record SuspensionRequest(Boolean suspend, String reason) {}
}
