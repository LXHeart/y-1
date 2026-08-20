package com.grassland.marketplace.taskcatalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 履约自动核验引擎（Verification v1 + 任务书 #23 R4 互动分支）：链接可达性 + 平台一致性 +
 * AI 视觉/互动截图 → tri-state 聚合 → 原子落核验记录（7C 事务：upsert + outbox，任一失败零残留）。
 * 商家手动决策仍走 confirm（通过）/reject 退回，不在本服务。
 */
@Component
public class EngagementVerificationService {

    private final TaskApplicationRepository apps;
    private final SubmissionRepository submissions;
    private final SubmissionAttachmentRepository attachments;
    private final LinkReachabilityChecker linkChecker;
    private final IntelligenceVerificationClient verificationClient;
    private final EngagementVerificationRepository verifications;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final ObjectMapper mapper = new ObjectMapper();

    public EngagementVerificationService(TaskApplicationRepository apps,
                                         SubmissionRepository submissions,
                                         SubmissionAttachmentRepository attachments,
                                         LinkReachabilityChecker linkChecker,
                                         IntelligenceVerificationClient verificationClient,
                                         EngagementVerificationRepository verifications,
                                         OutboxRepository outbox,
                                         TransactionalOperator transactions) {
        this.apps = apps;
        this.submissions = submissions;
        this.attachments = attachments;
        this.linkChecker = linkChecker;
        this.verificationClient = verificationClient;
        this.verifications = verifications;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    /**
     * 商家手动触发核验（POST .../verification/checks）：定位交付物 → 跑自动核验并落记录 → 响应体。
     * 交付物不存在或不属于该 application → 404。
     */
    public Mono<Map<String, Object>> check(Task task, TaskApplication app, String appId,
                                           String submissionId, String triggeredBy) {
        return submissions.findByApplication(appId)
                .filter(s -> s.id().equals(submissionId))
                .next()
                .switchIfEmpty(fail(404, "交付物不存在"))
                .flatMap(submission -> runAndRecord(task, app, submission, triggeredBy)
                        .map(ApplicationBodies::verification));
    }

    /** Immutable task context for task-mode creation and verification replay（GET .../task-context）。 */
    public Mono<Map<String, Object>> taskContext(String appId) {
        return apps.findTaskContextSnapshot(appId)
                .switchIfEmpty(fail(409, "任务上下文快照尚未生成"))
                .map(ApplicationBodies::parsedJson);
    }

    /** 核验运行历史（GET .../verification/runs，最近 50 条）。交付物不存在 → 404。 */
    public Mono<List<Map<String, Object>>> runs(String appId, String submissionId) {
        return submissions.findByApplication(appId)
                .filter(s -> s.id().equals(submissionId))
                .hasElements()
                .flatMap(exists -> exists
                        ? verifications.findRuns(submissionId, 50).map(ApplicationBodies::run).collectList()
                        : fail(404, "交付物不存在"));
    }

    /** 跑自动核验并原子落记录（7C 事务：upsert + outbox，outbox 挂 upsert 之后；任一失败零残留）。 */
    public Mono<EngagementVerification> runAndRecord(Task task, TaskApplication app,
                                                     EngagementSubmission submission,
                                                     String triggeredBy) {
        return attachments.findBySubmissionIds(List.of(submission.id())).collectList()
                .flatMap(evidence -> runVerificationChecks(task, submission, evidence)
                .flatMap(outcomes -> transactions.transactional(
                        verifications.appendRun(
                                        submission.id(), aggregateVerificationStatus(outcomes), checksToJson(outcomes),
                                        toJson(VerificationTaskContext.capture(task, app, submission)),
                                        toJson(Map.of("mediaIds", evidence.stream()
                                                .map(EngagementSubmissionAttachment::mediaReferenceId).toList())),
                                        triggeredBy)
                                .flatMap(v -> outbox.append(verificationEnvelope(app, submission, v, outcomes, task.ownerAccountId()))
                                        .thenReturn(v)))));
    }

    /**
     * 跑自动核验：链接可达性 + AI 视觉核验（附件截图）。链接结论独立给出；AI 检查失败
     * （intelligence 不可用 / 4xx / 5xx）降级为单项 {@code inconclusive}，不拖垮整次核验。无附件 → 跳过 AI，仅 link。
     *
     * <p>任务书 #23 R4：互动任务（contentForm=interaction）换检查表——{@code link_reachability}/
     * {@code platform_identity} 复用零改动（contentUrl=目标链接）；{@code evidence_completeness} 走互动分支
     * （平台账号标识 + 截图 ≥1）；跳过 {@code ai_visual}（无原创作品），新增 {@code interaction_screenshot}
     * （多模态识别截图：目标匹配/动作状态可见/账号一致）。图文/视频任务行为零改动。
     */
    private Mono<List<CheckOutcome>> runVerificationChecks(
            Task task, EngagementSubmission submission, List<EngagementSubmissionAttachment> evidence) {
        Mono<CheckOutcome> link = linkChecker.check(submission.contentUrl())
                .map(r -> new CheckOutcome("link_reachability", r.status(), r.detail(), Instant.now()));
        CheckOutcome platform = platformIdentityCheck(task.platform(), submission.contentUrl());
        boolean interaction = TaskRequirements.isInteractionForm(task.contentForm());
        CheckOutcome completeness = interaction
                ? interactionCompletenessCheck(submission, evidence)
                : new CheckOutcome("evidence_completeness", "passed",
                        evidence.isEmpty() ? "已提交发布链接" : "发布链接 + " + evidence.size() + " 个附件", Instant.now());
        Mono<List<CheckOutcome>> ai = interaction
                ? interactionScreenshotCheck(task, submission, evidence)
                : aiVisualCheck(task, evidence);
        return link.flatMap(linkOutcome -> ai
                .map(aiOutcomes -> Stream.concat(Stream.of(linkOutcome, platform, completeness), aiOutcomes.stream())
                        .filter(java.util.Objects::nonNull).toList()));
    }

    /** 任务书 #23 R4：互动任务的凭证完整性——平台账号标识非空 + 动作截图 ≥1（核验检查，不在提交时硬拒）。 */
    private static CheckOutcome interactionCompletenessCheck(
            EngagementSubmission submission, List<EngagementSubmissionAttachment> evidence) {
        if (submission.platformHandle() == null || submission.platformHandle().isBlank()) {
            return new CheckOutcome("evidence_completeness", "failed", "缺平台账号标识", Instant.now());
        }
        if (evidence.isEmpty()) {
            return new CheckOutcome("evidence_completeness", "failed",
                    "互动任务需至少 1 张动作截图（点赞/收藏/关注界面）", Instant.now());
        }
        return new CheckOutcome("evidence_completeness", "passed",
                "平台账号标识 + " + evidence.size() + " 张动作截图", Instant.now());
    }

    /**
     * 互动截图核验（新检查键 {@code interaction_screenshot}）：复用 ai_visual 的 intelligence 多模态通道，
     * 换互动专用 prompt 与上下文（目标链接/动作类型/账号标识）。无截图 → 跳过（completeness 已 failed）；
     * intelligence 不可用 → {@code inconclusive} 进人工复核队列（不确定即人工）。
     */
    private Mono<List<CheckOutcome>> interactionScreenshotCheck(
            Task task, EngagementSubmission submission, List<EngagementSubmissionAttachment> evidence) {
        if (evidence.isEmpty()) {
            return Mono.just(List.of());
        }
        TaskRequirements.Interaction config = task.requirements() == null ? null : task.requirements().interaction();
        if (config == null) {
            // 防御：发布契约已拦「interaction 无配置块」，历史脏数据兜底为人工复核。
            return Mono.just(List.of(new CheckOutcome("interaction_screenshot",
                    "inconclusive", "任务缺少互动配置，请人工复核", Instant.now())));
        }
        List<UUID> mediaIds = evidence.stream()
                .map(r -> UUID.fromString(r.mediaReferenceId()))
                .distinct()
                .toList();
        return verificationClient.analyzeInteraction(task.organizationId(), mediaIds,
                        task.title(), task.description(), task.platform(),
                        config.targetUrl(), config.actionType(), submission.platformHandle())
                .map(a -> List.of(new CheckOutcome("interaction_screenshot", a.status(),
                        aiVisualDetail(a), Instant.now())))
                .onErrorResume(e -> Mono.just(List.of(new CheckOutcome("interaction_screenshot",
                        "inconclusive", "互动截图核验暂不可用", Instant.now()))));
    }

    /**
     * AI 视觉核验：取该 submission 已证挂接的附件 mediaIds（已限定本 submission，IDOR 安全）→ intelligence 视觉判断。
     * 无附件 → 空（跳过 AI，仅 link）；intelligence 不可用 / 非 200 → 单项 {@code inconclusive}，不 fail 整次核验。
     */
    private Mono<List<CheckOutcome>> aiVisualCheck(Task task, List<EngagementSubmissionAttachment> rows) {
        if (rows.isEmpty()) {
            return Mono.just(List.of());
        }
        List<UUID> mediaIds = rows.stream()
                            .map(r -> UUID.fromString(r.mediaReferenceId()))
                            .distinct()
                            .toList();
        return verificationClient.analyze(task.organizationId(), mediaIds,
                                    task.title(), task.description(), task.platform())
                            .map(a -> List.of(new CheckOutcome("ai_visual", a.status(),
                                    aiVisualDetail(a), Instant.now())))
                            .onErrorResume(e -> Mono.just(List.of(new CheckOutcome("ai_visual",
                                    "inconclusive", "AI 视觉核验暂不可用", Instant.now()))));
    }

    private static CheckOutcome platformIdentityCheck(String platform, String contentUrl) {
        if (platform == null || platform.isBlank()) return null;
        String host;
        try { host = java.net.URI.create(contentUrl).getHost(); }
        catch (Exception ignored) { host = null; }
        List<String> domains = switch (platform.toLowerCase()) {
            case "douyin" -> List.of("douyin.com", "iesdouyin.com");
            case "xiaohongshu", "xhs" -> List.of("xiaohongshu.com", "xhslink.com");
            case "bilibili" -> List.of("bilibili.com", "b23.tv");
            default -> List.of();
        };
        if (domains.isEmpty()) return null;
        String resolvedHost = host;
        boolean recognizedPlatformHost = resolvedHost != null && List.of(
                        "douyin.com", "iesdouyin.com", "xiaohongshu.com", "xhslink.com",
                        "bilibili.com", "b23.tv")
                .stream().anyMatch(d -> resolvedHost.equals(d) || resolvedHost.endsWith("." + d));
        // A generic/public URL remains governed by reachability until an official platform adapter is configured.
        if (!recognizedPlatformHost) return null;
        boolean matches = resolvedHost != null && domains.stream()
                .anyMatch(d -> resolvedHost.equals(d) || resolvedHost.endsWith("." + d));
        return new CheckOutcome("platform_identity", matches ? "passed" : "failed",
                matches ? "发布链接与任务平台一致" : "发布链接域名与任务平台不一致", Instant.now());
    }

    /** 汇总 per-media 明细为单项 ai_visual 的 detail（供商家面板展示）；无明细 → null。 */
    private static String aiVisualDetail(IntelligenceVerificationClient.VerificationAnalysis a) {
        List<IntelligenceVerificationClient.MediaResult> results = a.results();
        if (results.isEmpty()) {
            return null;
        }
        return results.stream()
                .map(r -> r.status() + (r.detail() == null || r.detail().isBlank() ? "" : "：" + r.detail()))
                .collect(Collectors.joining("；"));
    }

    /** 聚合 tri-state：failed > inconclusive > passed；无 check → inconclusive。 */
    private static String aggregateVerificationStatus(List<CheckOutcome> outcomes) {
        if (outcomes.isEmpty()) {
            return "inconclusive";
        }
        if (outcomes.stream().anyMatch(o -> "failed".equalsIgnoreCase(o.status()))) {
            return "failed";
        }
        if (outcomes.stream().anyMatch(o -> "inconclusive".equalsIgnoreCase(o.status()))) {
            return "inconclusive";
        }
        return "passed";
    }

    private List<Map<String, Object>> checksToMaps(List<CheckOutcome> outcomes) {
        return outcomes.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", o.type());
            m.put("status", o.status());
            m.put("detail", o.detail());
            m.put("checkedAt", o.checkedAt() == null ? null : o.checkedAt().toString());
            return m;
        }).toList();
    }

    private String checksToJson(List<CheckOutcome> outcomes) {
        try {
            return mapper.writeValueAsString(checksToMaps(outcomes));
        } catch (JsonProcessingException e) {
            return "[]";  // 不应发生；兜底空数组
        }
    }

    private String toJson(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("核验上下文序列化失败", e); }
    }

    /** 核验事件：确定性 event_id（type-3 UUID），保 outbox 重试 exactly-once（镜像 SettlementActivityImpl）。 */
    private EventEnvelope verificationEnvelope(TaskApplication app, EngagementSubmission submission,
                                               EngagementVerification v, List<CheckOutcome> outcomes, String taskOwnerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", app.taskId());
        payload.put("applicationId", app.id());
        payload.put("submissionId", submission.id());
        payload.put("recommenderAccountId", app.recommenderAccountId());
        if (taskOwnerId != null) {
            payload.put("taskOwnerId", taskOwnerId);
        }
        payload.put("status", v.status());
        payload.put("checks", checksToMaps(outcomes));
        String eventId = UUID.nameUUIDFromBytes(
                ("VerificationChecked:" + submission.id()).getBytes(StandardCharsets.UTF_8)).toString();
        return new EventEnvelope(eventId, "VerificationChecked", "EngagementSubmission",
                submission.id(), 1, Instant.now(), null, payload);
    }

    /** 单项核验明细（聚合前的原子结果）。 */
    private record CheckOutcome(String type, String status, String detail, Instant checkedAt) {}

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new MarketplaceException(status, message));
    }
}
