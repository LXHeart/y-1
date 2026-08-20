package com.grassland.marketplace.taskcatalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.reputation.ReputationSnapshot;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * application 聚合 HTTP 响应体装配（纯函数）：领域行 → 信封 {@code {success:true, data:...}}。
 * 202（reserving/settling）等带状态语义的响应也在此，供控制器与领域服务共用同一装配。
 */
final class ApplicationBodies {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApplicationBodies() {}

    static ResponseEntity<Map<String, Object>> ok(Map<String, Object> data) {
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** {@code {success:true, data:null}}——Map.of 不收 null 值，故手写。 */
    static Map<String, Object> nullData() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("data", null);
        return m;
    }

    static Map<String, Object> toBody(TaskApplication app) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", app.id());
        m.put("taskId", app.taskId());
        m.put("recommenderAccountId", app.recommenderAccountId());
        m.put("status", app.status());
        m.put("note", app.note());
        m.put("reviewedByAccountId", app.reviewedByAccountId());
        m.put("decidedAt", app.decidedAt() == null ? null : app.decidedAt().toString());
        m.put("createdAt", app.createdAt() == null ? null : app.createdAt().toString());
        m.put("reputationLevelAtAccept", app.reputationLevelAtAccept());
        m.put("reputationPolicyVersionAtAccept", app.reputationPolicyVersionAtAccept());
        m.put("settlementDelayDaysAtAccept", app.settlementDelayDaysAtAccept());
        m.put("commissionBonusBpsAtAccept", app.commissionBonusBpsAtAccept());
        m.put("premiumSupportAtAccept", app.premiumSupportAtAccept());
        m.put("confirmedMetricValue", app.confirmedMetricValue());
        return m;
    }

    /** 列表行：基础报名体 + 声誉快照三字段（owner 视图按权重排序展示用）。 */
    static Map<String, Object> ranked(TaskApplication app, ReputationSnapshot snapshot) {
        Map<String, Object> body = toBody(app);
        body.put("reputationLevel", snapshot.evaluation().effectiveLevel().number());
        body.put("reputationTitle", snapshot.policy()
                .ruleFor(snapshot.evaluation().effectiveLevel()).title());
        body.put("taskPriorityWeight", snapshot.evaluation().taskPriorityWeight());
        return body;
    }

    /** 预留结局响应体：status + 可选 reason + taskClosed（#26 D12）。 */
    static Map<String, Object> reservationBody(String status, String reason, boolean closed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status);
        if (reason != null) {
            m.put("reason", reason);
        }
        m.put("taskClosed", closed);
        return m;
    }

    /** 接受命令响应体：commandId/workflowId/applicationId/status（+ 可选 reason）。 */
    static ResponseEntity<Map<String, Object>> acceptanceResponse(
            AcceptanceCommand command, String status, HttpStatus httpStatus) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("commandId", command.id());
        data.put("workflowId", command.workflowId());
        data.put("applicationId", command.applicationId());
        data.put("status", status);
        if (command.failureReason() != null) {
            data.put("reason", command.failureReason());
        }
        return ResponseEntity.status(httpStatus).body(Map.of("success", true, "data", data));
    }

    static ResponseEntity<Map<String, Object>> contestedResponse(TaskApplication app) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", app.id());
        data.put("status", "contested");
        data.put("reason", app.rejectionReason());
        data.put("disputeId", app.merchantRejectionDisputeId());
        return ok(data);
    }

    static ResponseEntity<Map<String, Object>> confirmedResponse(TaskApplication app) {
        return ok(Map.of("applicationId", app.id(), "status", "confirmed"));
    }

    static Map<String, Object> toBody(EngagementSubmission submission) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", submission.id());
        m.put("applicationId", submission.applicationId());
        m.put("recommenderAccountId", submission.recommenderAccountId());
        m.put("contentUrl", submission.contentUrl());
        m.put("note", submission.note());
        m.put("status", submission.status());
        m.put("reviewNote", submission.reviewNote());
        m.put("reviewedAt", submission.reviewedAt() == null ? null : submission.reviewedAt().toString());
        m.put("createdAt", submission.createdAt() == null ? null : submission.createdAt().toString());
        m.put("platformHandle", submission.platformHandle());
        return m;
    }

    /** 交付物响应（提交回执）：带附件列表，附件元数据取自校验阶段的快照。 */
    static Map<String, Object> submissionWithInputs(EngagementSubmission submission, List<AttachmentInput> inputs) {
        Map<String, Object> m = toBody(submission);
        m.put("attachments", inputs.stream().map(ApplicationBodies::attachmentInput).toList());
        return m;
    }

    /** 交付物响应（列表）：带附件列表 + 核验记录（有则附）。附件元数据取自挂接时快照的 DB 行。 */
    static Map<String, Object> submissionWithRows(EngagementSubmission submission,
                                                  List<EngagementSubmissionAttachment> rows,
                                                  EngagementVerification verification) {
        Map<String, Object> m = toBody(submission);
        m.put("attachments", rows.stream().map(ApplicationBodies::attachmentRow).toList());
        if (verification != null) {
            m.put("verification", verification(verification));
        }
        return m;
    }

    private static Map<String, Object> attachmentInput(AttachmentInput a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mediaId", a.mediaId());
        m.put("mimeType", a.mimeType());
        m.put("sizeBytes", a.sizeBytes());
        m.put("domainType", a.domainType());
        m.put("domainId", a.domainId());
        m.put("checksum", a.checksum());
        m.put("mediaStatus", a.status());
        return m;
    }

    private static Map<String, Object> attachmentRow(EngagementSubmissionAttachment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mediaId", a.mediaReferenceId());
        m.put("mimeType", a.mimeType());
        m.put("sizeBytes", a.sizeBytes());
        m.put("domainType", a.mediaDomainType());
        m.put("domainId", a.mediaDomainId());
        m.put("checksum", a.mediaChecksum());
        m.put("mediaStatus", a.mediaStatusSnapshot());
        return m;
    }

    static Map<String, Object> download(IntelligenceMediaClient.MediaDownload dl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("downloadUrl", dl.downloadUrl().toString());
        m.put("expiresAt", dl.expiresAt().toString());
        return m;
    }

    static Map<String, Object> toBody(EngagementRating rating) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", rating.id());
        m.put("applicationId", rating.applicationId());
        m.put("taskId", rating.taskId());
        m.put("recommenderAccountId", rating.recommenderAccountId());
        m.put("ratedByAccountId", rating.ratedByAccountId());
        m.put("score", rating.score());
        m.put("comment", rating.comment());
        m.put("createdAt", rating.createdAt() == null ? null : rating.createdAt().toString());
        return m;
    }

    static Map<String, Object> verification(EngagementVerification v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("submissionId", v.submissionId());
        m.put("status", v.status());
        m.put("checks", parseChecks(v.checksJson()));
        m.put("runId", v.latestRunId());
        m.put("engineVersion", v.engineVersion());
        m.put("taskContext", v.taskContextSnapshotJson() == null ? null : parseChecks(v.taskContextSnapshotJson()));
        m.put("evidenceSnapshot", v.evidenceSnapshotJson() == null ? null : parseChecks(v.evidenceSnapshotJson()));
        m.put("lastCheckedAt", v.lastCheckedAt() == null ? null : v.lastCheckedAt().toString());
        return m;
    }

    static Map<String, Object> run(EngagementVerificationRun run) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", run.id()); body.put("runNumber", run.runNumber());
        body.put("engineVersion", run.engineVersion()); body.put("status", run.status());
        body.put("taskContext", parseChecks(run.taskContextJson()));
        body.put("evidenceSnapshot", parseChecks(run.evidenceJson()));
        body.put("checks", parseChecks(run.checksJson())); body.put("triggeredBy", run.triggeredBy());
        body.put("createdAt", run.createdAt() == null ? null : run.createdAt().toString());
        return body;
    }

    /** 任务上下文快照 JSON → 结构化对象。坏 JSON 按快照损坏处理（阻断，不静默）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parsedJson(String json) {
        try {
            return MAPPER.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("任务上下文快照损坏", e);
        }
    }

    /** checksJson 是 jsonb 读出的 JSON 文本；解析回结构化对象，避免响应里二次转义。坏 JSON → 原样字符串。 */
    private static Object parseChecks(String json) {
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return json;
        }
    }
}
