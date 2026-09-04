package com.grassland.trust.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.trust.security.TrustException;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 准入考试流（任务书 #74 卡 E，拍板 D4）。Lv4+完成 ≥20 任务的推荐官经考试及格获得见习审判官资格；
 * Lv5 直入（不走考试）。出题：active 题库随机 N=10（响应不含答案）；交卷：≥80 分及格 →
 * exam_passed_at + admission_level=probation + audit；不及格冷却 24h 再考，attempt 留痕。
 */
@Component
public class JudgeExamService {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    /** 考试题数（定死 N=10，任务书卡 E）。 */
    public static final int EXAM_QUESTION_COUNT = 10;
    /** 及格分（百分制）。 */
    public static final int PASS_SCORE = 80;
    /** 不及格再考冷却。 */
    private static final Duration RETAKE_COOLDOWN = Duration.ofHours(24);

    private static final String AUTO_ACTOR = "00000000-0000-0000-0000-000000000000";

    private final JudgeExamRepository exams;
    private final JudgeRepository judges;
    private final JudgeAdmissionAuditRepository audits;
    private final MarketplaceReputationClient reputationClient;
    private final com.grassland.messaging.outbox.OutboxRepository outbox;
    private final int minimumCompletedTasks;
    /** 考试通过事件开关（通知 identity JudgeExamPassed）。 */
    private final boolean notifyEnabled;

    public JudgeExamService(JudgeExamRepository exams, JudgeRepository judges,
                            JudgeAdmissionAuditRepository audits, MarketplaceReputationClient reputationClient,
                            com.grassland.messaging.outbox.OutboxRepository outbox,
                            @Value("${trust.adjudication.judge-exam-min-completed-tasks:20}") int minimumCompletedTasks,
                            @Value("${trust.adjudication.judge-exam-notify:true}") boolean notifyEnabled) {
        this.exams = exams;
        this.judges = judges;
        this.audits = audits;
        this.reputationClient = reputationClient;
        this.outbox = outbox;
        this.minimumCompletedTasks = minimumCompletedTasks;
        this.notifyEnabled = notifyEnabled;
    }

    /** 出题接口的题目（不含答案）。 */
    public record ExamQuestionView(String id, String category, String question, List<String> options) {}

    /** 交卷结果。 */
    public record ExamResult(int score, boolean passed, String admissionLevel, Instant cooldownUntil) {}

    /** 出题：随机 N 题（不含 answerIndex）。题库不足 N 按实际数量出。 */
    public Mono<List<ExamQuestionView>> draw() {
        return exams.countActiveQuestions()
                .flatMap(count -> count < EXAM_QUESTION_COUNT
                        ? Mono.error(new TrustException(409, "题库题目不足，请联系平台补充题库"))
                        : exams.drawQuestions(EXAM_QUESTION_COUNT).collectList())
                .map(questions -> questions.stream().map(q -> new ExamQuestionView(q.id(), q.category(),
                        q.question(), readOptions(q.optionsJson()))).toList());
    }

    /**
     * 交卷判分。{@code answersJson} 形如 {@code {"<questionId>": <choiceIndex>, ...}}。
     * 及格 → judge.exam_passed_at 落值 + 见习标记（Lv4）+ audit 'probation' + JudgeExamPassed 事件。
     */
    public Mono<ExamResult> grade(String accountId, String answersJson) {
        return exams.lastAttempt(accountId)
                .flatMap(last -> {
                    if (!last.passed() && last.createdAt() != null
                            && last.createdAt().plus(RETAKE_COOLDOWN).isAfter(Instant.now())) {
                        return Mono.error(new TrustException(409, "考试不及格需等待 24 小时后重考"));
                    }
                    return Mono.just(true);
                })
                .then(parseAnswers(answersJson))
                .flatMap(answerMap -> exams.drawQuestions(EXAM_QUESTION_COUNT).collectList()
                        .flatMap(questions -> {
                            if (questions.isEmpty()) {
                                return Mono.error(new TrustException(409, "题库题目不足，请联系平台补充题库"));
                            }
                            return judgeAndPersist(accountId, questions, answerMap);
                        }));
    }

    private Mono<ExamResult> judgeAndPersist(String accountId, List<JudgeExamQuestion> questions,
                                             java.util.Map<String, Integer> answerMap) {
        List<ExamQuestionView> rendered = questions.stream()
                .map(q -> new ExamQuestionView(q.id(), q.category(), q.question(), readOptions(q.optionsJson())))
                .toList();
        int correct = 0;
        for (JudgeExamQuestion q : questions) {
            Integer choice = answerMap.get(q.id());
            if (choice != null && choice == q.answerIndex()) {
                correct++;
            }
        }
        int score = Math.round(100f * correct / questions.size());
        boolean passed = score >= PASS_SCORE;
        String answersJson = buildAnswersJson(questions, answerMap);
        Mono<ExamResult> result = exams
                .insertAttempt(new JudgeExamAttempt(UUID.randomUUID().toString(), accountId, score, passed,
                        answersJson, Instant.now()))
                .then(passed ? promoteToProbation(accountId) : Mono.just("unchanged"))
                .map(level -> new ExamResult(score, passed, level,
                        passed ? null : Instant.now().plus(RETAKE_COOLDOWN)));
        return result;
    }

    /** 及格后置：exam_passed_at + Lv4 → probation；audit 'probation'；JudgeExamPassed 事件（best-effort）。 */
    private Mono<String> promoteToProbation(String accountId) {
        return judges.findByAccountId(accountId).flatMap(judge -> judges.markExamPassed(accountId)
                .flatMap(updated -> {
                    String level = updated.admissionLevel() == null ? "full" : updated.admissionLevel();
                    Mono<Void> audit = judge.examPassedAt() == null
                            ? audits.appendAction(updated.id(), "probation", updated.accountId(),
                                    "exam_passed", updated.version() - 1).then()
                            : Mono.empty();
                    Mono<Void> notify = notifyEnabled ? outbox.append(examPassedEnvelope(accountId)) : Mono.empty();
                    return audit.then(notify).thenReturn(level);
                }))
                .defaultIfEmpty("unchanged");
    }

    /** Lv4 报名门槛（任务书卡 E：完成 ≥20 任务经 reputation 端点校验；completedCount 缺失按不满足）。 */
    public Mono<Boolean> meetsEnrollmentThreshold(String accountId, int levelNumber) {
        if (levelNumber >= 5) {
            return Mono.just(true);
        }
        return reputationClient.getLevel(accountId)
                .map(level -> level.completedCount() >= minimumCompletedTasks)
                .defaultIfEmpty(false);
    }

    private Mono<java.util.Map<String, Integer>> parseAnswers(String answersJson) {
        try {
            java.util.Map<String, Integer> map = new java.util.LinkedHashMap<>();
            JsonNode root = MAPPER.readTree(answersJson == null || answersJson.isBlank() ? "{}" : answersJson);
            if (root != null && root.isObject()) {
                root.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isInt()) {
                        map.put(entry.getKey(), entry.getValue().asInt());
                    }
                });
            }
            return Mono.just(map);
        } catch (IOException invalid) {
            return Mono.error(new IllegalArgumentException("answers 必须是 {questionId: choiceIndex} 对象"));
        }
    }

    private List<String> readOptions(String optionsJson) {
        try {
            JsonNode root = MAPPER.readTree(optionsJson == null ? "[]" : optionsJson);
            List<String> options = new ArrayList<>();
            if (root != null && root.isArray()) {
                root.forEach(node -> options.add(node.asText()));
            }
            return options;
        } catch (IOException invalid) {
            return List.of();
        }
    }

    private static String buildAnswersJson(List<JudgeExamQuestion> questions, java.util.Map<String, Integer> answerMap) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < questions.size(); i++) {
            JudgeExamQuestion q = questions.get(i);
            Integer choice = answerMap.get(q.id());
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"questionId\":\"").append(q.id()).append("\",\"choiceIndex\":")
                    .append(choice == null ? "null" : choice)
                    .append(",\"correct\":").append(choice != null && choice == q.answerIndex()).append('}');
        }
        return sb.append(']').toString();
    }

    /** JudgeExamPassed 事件（DISPUTE 类；identity 通知收件人=judgeAccountId，深链审判台）。 */
    private com.grassland.messaging.EventEnvelope examPassedEnvelope(String accountId) {
        String eventId = UUID.nameUUIDFromBytes(("JudgeExamPassed:" + accountId).getBytes(
                java.nio.charset.StandardCharsets.UTF_8)).toString();
        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("judgeAccountId", accountId);
        payload.put("passed", true);
        return new com.grassland.messaging.EventEnvelope(eventId, "JudgeExamPassed", "Judge",
                accountId, 0, Instant.now(), null, payload);
    }
}
