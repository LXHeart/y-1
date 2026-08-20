package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import com.grassland.marketplace.workflow.saga.ConfirmationWorkflowStarter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 履约交付物领域服务（PRD 九 + 7C 事务 + D-03）：提交/列表/商家退回（补证上限）、附件校验（IDOR 守卫）、
 * 附件下载 URL 中转、商家确认窗口 Saga 启动。可见性守卫（owner 或提交人）由控制器完成后传入。
 */
@Component
public class EngagementSubmissionService {

    private final TaskApplicationRepository apps;
    private final SubmissionRepository submissions;
    private final SubmissionAttachmentRepository attachments;
    private final EngagementVerificationRepository verifications;
    private final IntelligenceMediaClient mediaClient;
    private final OutboxRepository outbox;
    private final ConfirmationWorkflowStarter confirmationWorkflows;
    private final TransactionalOperator transactions;
    private final long confirmationWindowSeconds;
    private final long confirmationReminderLeadSeconds;
    private final int supplementCap;

    public EngagementSubmissionService(TaskApplicationRepository apps,
                                       SubmissionRepository submissions,
                                       SubmissionAttachmentRepository attachments,
                                       EngagementVerificationRepository verifications,
                                       IntelligenceMediaClient mediaClient,
                                       OutboxRepository outbox,
                                       ConfirmationWorkflowStarter confirmationWorkflows,
                                       TransactionalOperator transactions,
                                       @Value("${marketplace.confirmation.window-seconds:5}") long confirmationWindowSeconds,
                                       @Value("${marketplace.confirmation.reminder-lead-seconds:86400}") long confirmationReminderLeadSeconds,
                                       @Value("${marketplace.confirmation.supplement-cap:2}") int supplementCap) {
        this.apps = apps;
        this.submissions = submissions;
        this.attachments = attachments;
        this.verifications = verifications;
        this.mediaClient = mediaClient;
        this.outbox = outbox;
        this.confirmationWorkflows = confirmationWorkflows;
        this.transactions = transactions;
        this.confirmationWindowSeconds = confirmationWindowSeconds;
        this.confirmationReminderLeadSeconds = Math.max(0, confirmationReminderLeadSeconds);
        this.supplementCap = Math.max(0, supplementCap);
    }

    /** 任务书 #23 R3：contentForm=interaction 的任务提交必须带 platformHandle（其余任务忽略该字段）。 */
    public static Mono<Void> requireInteractionHandle(Task task, String platformHandle) {
        if (TaskRequirements.isInteractionForm(task.contentForm())
                && (platformHandle == null || platformHandle.isBlank())) {
            return Mono.error(new MarketplaceException(400, "点赞互动任务必须填写平台账号标识"));
        }
        return Mono.empty();
    }

    /**
     * 缺口清偿之九：评论类互动的评论文本契约——actionType=comment 必填 ≤500 字；非 comment 任务
     * 携带该字段一律 400（评论文本是互动任务专属证据，不与其它内容形式混用）。
     */
    public static Mono<Void> requireCommentText(Task task, String commentText) {
        TaskRequirements.Interaction interaction =
                task.requirements() == null ? null : task.requirements().interaction();
        boolean commentTask = TaskRequirements.isInteractionForm(task.contentForm())
                && interaction != null && "comment".equals(interaction.actionType());
        if (commentTask && (commentText == null || commentText.isBlank())) {
            return Mono.error(new MarketplaceException(400, "评论互动任务必须填写评论内容"));
        }
        if (commentText != null && !commentText.isBlank()) {
            if (!commentTask) {
                return Mono.error(new MarketplaceException(400, "仅评论互动任务可提交评论内容"));
            }
            if (commentText.trim().length() > CreateSubmissionRequest.MAX_COMMENT_TEXT) {
                return Mono.error(new MarketplaceException(
                        400, "评论内容最多 " + CreateSubmissionRequest.MAX_COMMENT_TEXT + " 字"));
            }
        }
        return Mono.empty();
    }

    /**
     * 校验附件（事务外）：逐个 mediaId 经 intelligence 取 metadata。intelligence 已过滤
     * purpose=engagement_attachment && active && 未过期（不符→404→empty）；这里再做 IDOR 守卫——
     * owner 必须是提交人本人，否则 403。media 不可用→404。无附件→空列表。
     */
    public Mono<List<AttachmentInput>> validateAttachments(
            String orgId, String ownerAccountId, String applicationId, List<UUID> mediaIds) {
        if (mediaIds.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(mediaIds)
                .concatMap(mediaId -> mediaClient.metadata(
                                orgId, mediaId, "application", applicationId)
                        .switchIfEmpty(fail(404, "附件不存在或已不可用"))
                        .filter(m -> ownerAccountId.equals(m.ownerAccountId()))
                        .switchIfEmpty(fail(403, "不能挂接他人的附件"))
                        .filter(m -> "application".equals(m.domainType())
                                && applicationId.equals(m.domainId()))
                        .switchIfEmpty(fail(404, "附件不属于当前报名"))
                        .map(m -> new AttachmentInput(
                                mediaId, m.mimeType(), m.sizeBytes(), m.domainType(), m.domainId(),
                                m.checksum(), m.status(), 1)))
                .collectList();
    }

    /**
     * 原子提交交付物（7C 事务）：create + attach + outbox 同一 R2DBC 事务，outbox 挂 attach 之后。
     * 任一内层失败（含附件冲突 empty→409）→ 整事务回滚，零残留（无孤儿 submission/附件/事件）。
     */
    public Mono<EngagementSubmission> submit(TaskApplication app, String appId, Caller caller,
                                             String contentUrl, String note,
                                             List<AttachmentInput> attachmentInputs,
                                             String taskOwnerId, String platformHandle,
                                             String commentText) {
        String normalizedComment = commentText == null || commentText.isBlank() ? null : commentText.trim();
        return transactions.transactional(
                submissions.create(appId, caller.accountId(), contentUrl, note, platformHandle, normalizedComment)
                        .switchIfEmpty(fail(409, "已有待核验的交付物，请等待商家核验或修改后重新提交"))
                        .flatMap(created -> attachAll(created.id(), attachmentInputs).thenReturn(created))
                        .flatMap(created -> outbox
                                .append(ApplicationEvents.submissionEnvelope("DeliverableSubmitted", app, created, attachmentInputs, taskOwnerId))
                                .then(apps.setConfirmDeadline(app.id(), app.taskId(), confirmationWindowSeconds))
                                .then(outbox.append(ApplicationEvents.confirmationEnvelope(
                                        "ConfirmationWindowEntered", app, created.id(), taskOwnerId)))
                                .thenReturn(created)));
    }

    /**
     * 商家退回补交：submitted → rejected（带原因）。退回后推荐官可修改重交。
     * <p>D-03 规则 4：补证（退回）次数上限 {@code marketplace.confirmation.supplement-cap}（默认 2）。超出 → 409，
     * 不再允许补证，submission 留 submitted，确认窗口照常跑到自动结算（规则 1）。
     */
    public Mono<EngagementSubmission> reject(Task task, TaskApplication app, String submissionId, String note) {
        // 先校验 submissionId 存在且属于本 application，再判补证上限：
        // 顺序反了会对「不存在/不属于本履约」的 id 回 409「补证次数已达上限」，
        // 把调用方引向「去确认履约或开争议」，而真实原因只是 id 写错（应 404）。
        return submissions.findById(submissionId)
                .filter(s -> app.id().equals(s.applicationId()))
                .switchIfEmpty(fail(404, "交付物不存在"))
                .flatMap(target -> submissions.countRejectedByApplication(app.id()))
                .flatMap(rejectedCount -> rejectedCount >= supplementCap
                        ? Mono.<EngagementSubmission>error(new MarketplaceException(
                                409, "补证次数已达上限，请确认履约或发起争议"))
                        : submissions.review(submissionId, SubmissionStatus.REJECTED, note)
                                .switchIfEmpty(fail(409, "该交付物已处理"))
                                .flatMap(rejected -> outbox
                                        .append(ApplicationEvents.submissionEnvelope(
                                                "DeliverableRejected", app, rejected, List.of(), task.ownerAccountId()))
                                        .thenReturn(rejected)));
    }

    /** 列交付物（含历史）及其附件与核验记录；无交付物 → 空列表。 */
    public Mono<List<Map<String, Object>>> list(String appId) {
        return submissions.findByApplication(appId).collectList()
                .flatMap(list -> {
                    if (list.isEmpty()) {
                        return Mono.just(List.<Map<String, Object>>of());
                    }
                    List<String> submissionIds = list.stream().map(EngagementSubmission::id).toList();
                    Mono<List<EngagementSubmissionAttachment>> attsM =
                            attachments.findBySubmissionIds(submissionIds).collectList();
                    Mono<List<EngagementVerification>> verifsM =
                            verifications.findBySubmissions(submissionIds).collectList();
                    return Mono.zip(attsM, verifsM)
                            .map(t -> list.stream().map(s -> ApplicationBodies.submissionWithRows(s,
                                    t.getT1().stream().filter(a -> a.submissionId().equals(s.id())).toList(),
                                    t.getT2().stream().filter(v -> v.submissionId().equals(s.id()))
                                            .findFirst().orElse(null)))
                                    .toList());
                });
    }

    /**
     * 附件短时下载 URL（Slice 11 Stage 2）：证 media 确挂该 submission（JOIN 限定 application，防跨履约越权），
     * 再经 intelligence 取 presigned URL。media 已删/不可用 → 404。
     */
    public Mono<Map<String, Object>> downloadUrl(Task task, String appId, String submissionId, UUID mediaId) {
        return attachments.findOne(appId, submissionId, mediaId.toString())
                .switchIfEmpty(fail(404, "附件未挂接到该交付物"))
                .flatMap(att -> mediaClient.downloadUrl(task.organizationId(), mediaId, "application", appId)
                        .switchIfEmpty(fail(404, "附件已不可用"))
                        .map(ApplicationBodies::download));
    }

    /**
     * 启商家确认窗口 Saga（D-03）：推荐官提交履约即起，3 天（dev 5s）到期未操作 → 自动确认结算。
     *
     * <p>fire-and-forget；submitDeliverable 响应仍是 201（提交回执），窗口状态经 {@code GET .../confirmation}
     * 轮询。{@code workflowId = "confirm-" + appId}、双击去重（{@code WorkflowExecutionAlreadyStarted} → 复用）。
     */
    public Mono<String> startConfirmation(Task task, TaskApplication app, EngagementSubmission submission) {
        return confirmationWorkflows.start(
                        app.id(), submission.id(), task.organizationId(),
                        confirmationWindowSeconds, confirmationReminderLeadSeconds)
                .flatMap(workflowId -> submissions.markConfirmationWorkflowStarted(submission.id())
                        .thenReturn(workflowId));
    }

    private Mono<List<EngagementSubmissionAttachment>> attachAll(String submissionId, List<AttachmentInput> inputs) {
        if (inputs.isEmpty()) {
            return Mono.just(List.of());
        }
        return attachments.attach(submissionId, inputs)
                .switchIfEmpty(fail(409, "附件重复，请勿重复挂接相同附件"));
    }

    private static <T> Mono<T> fail(int status, String message) {
        return Mono.error(new MarketplaceException(status, message));
    }
}
