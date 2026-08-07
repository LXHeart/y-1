package com.grassland.marketplace.ops;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.EngagementVerificationRepository;
import com.grassland.marketplace.taskcatalog.VerificationOverride;
import com.grassland.marketplace.taskcatalog.VerificationOverrideRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 运营处置台 API（GL-P1-OPS-001 Stage 1）。全部端点要求平台角色 {@code customer_service} 或 {@code admin}
 * （{@link MarketplaceCallerResolver#requireOpsOperator}），商家/推荐官身份一律 403。
 *
 * <p>队列 {@code GET /api/ops/cases}（默认只列未终态）、详情 {@code GET /api/ops/cases/{id}}（含审计时间线）、
 * 提审 {@code POST .../submit}、审批 {@code POST .../decide}、收单 {@code POST .../resolve}。
 *
 * <p><b>每次状态流转都在同一事务内追加审计</b> —— 分开提交会出现「状态变了但没人记得是谁改的」。
 * 流转全部要求 {@code expectedVersion}（乐观锁），不符 → 409，避免两名运营并发处置同一单时后写覆盖前写。
 *
 * <p>Stage 1 只做 case 生命周期；真正的重试/补偿动作与 DLT replay 在 Stage 2 接入
 * （届时在 {@code approved} 态下执行，并写 {@code action_executed} 审计）。
 */
@RestController
public class OpsCaseController {

    /** 队列单页上限（运营队列不做游标分页：未终态数量本应很小，长期堆积本身就是要处理的信号）。 */
    private static final int MAX_LIMIT = 200;

    private final OpsCaseRepository cases;
    private final OpsCaseAuditRepository audits;
    private final OpsCaseActionRepository actionLog;
    private final OpsCaseActionService actionService;
    private final OpsDltMessageRepository dltMessages;
    private final OpsDltActionService dltActions;
    private final OpsPendingVerificationRepository pendingVerifications;
    private final VerificationOverrideRepository verificationOverrides;
    private final EngagementVerificationRepository verifications;
    private final MarketplaceCallerResolver callers;
    private final TransactionalOperator transactions;

    public OpsCaseController(OpsCaseRepository cases, OpsCaseAuditRepository audits,
                             OpsCaseActionRepository actionLog, OpsCaseActionService actionService,
                             OpsDltMessageRepository dltMessages, OpsDltActionService dltActions,
                             OpsPendingVerificationRepository pendingVerifications,
                             VerificationOverrideRepository verificationOverrides,
                             EngagementVerificationRepository verifications,
                             MarketplaceCallerResolver callers, TransactionalOperator transactions) {
        this.pendingVerifications = pendingVerifications;
        this.verificationOverrides = verificationOverrides;
        this.verifications = verifications;
        this.cases = cases;
        this.audits = audits;
        this.actionLog = actionLog;
        this.actionService = actionService;
        this.dltMessages = dltMessages;
        this.dltActions = dltActions;
        this.callers = callers;
        this.transactions = transactions;
    }

    /** 队列。{@code status} 省略 → 未终态（open/in_review/approved）；给定值则精确筛选（含终态，供回看）。 */
    @GetMapping("/api/ops/cases")
    public Mono<ResponseEntity<Map<String, Object>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "50") int limit,
            ServerHttpRequest request) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return callers.requireOpsOperator(request)
                .then(cases.listQueue(status, capped).map(OpsCaseController::toQueueBody).collectList())
                .map(items -> ResponseEntity.ok(Map.of("success", true, "data", items)));
    }

    /** 详情 + 审计时间线。不存在 → 404。 */
    @GetMapping("/api/ops/cases/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> detail(@PathVariable String id, ServerHttpRequest request) {
        return callers.requireOpsOperator(request)
                .then(cases.findById(id))
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "处置单不存在")))
                .flatMap(opsCase -> Mono.zip(
                                audits.listByCase(id).map(OpsCaseController::toAuditBody).collectList(),
                                actionLog.listByCase(id).map(OpsCaseController::toActionBody).collectList())
                        .map(both -> ResponseEntity.ok(Map.of("success", true,
                                "data", Map.of("case", toBody(opsCase),
                                        "audits", both.getT1(), "actions", both.getT2())))));
    }

    /** 提审（open→in_review）。非 open 或版本不符 → 409。 */
    @PostMapping(value = "/api/ops/cases/{id}/submit", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> submit(
            @PathVariable String id, @RequestBody OpsCaseActionRequest body, ServerHttpRequest request) {
        return callers.requireOpsOperator(request)
                .flatMap(caller -> transactions.transactional(
                        cases.submit(id, body.requireExpectedVersion(), caller.accountId(), body.note())
                                .switchIfEmpty(Mono.error(new MarketplaceException(409, "处置单状态已变更，请刷新后重试")))
                                .flatMap(updated -> audits.append(id, "submitted", caller.accountId(),
                                                caller.role(), "open", updated.status(), body.note())
                                        .thenReturn(updated))))
                .map(updated -> ResponseEntity.ok(Map.of("success", true, "data", toBody(updated))));
    }

    /**
     * 审批（in_review→approved|rejected）。非 in_review / 版本不符 / <b>审批人 == 提审人</b> → 409。
     *
     * <p>「自己审自己」在此平淡地映射成 409（仓储层 WHERE 排除），DB 的 {@code ck_ops_case_two_person}
     * 是第二道防线，只有绕过本端点的写入才会撞上它（届时是 500，属预期：那意味着有代码路径违反了四眼原则）。
     */
    @PostMapping(value = "/api/ops/cases/{id}/decide", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> decide(
            @PathVariable String id, @RequestBody OpsCaseDecisionRequest body, ServerHttpRequest request) {
        boolean approve = body.requireApprove();
        return callers.requireOpsOperator(request)
                .flatMap(caller -> transactions.transactional(
                        cases.decide(id, body.requireExpectedVersion(), caller.accountId(), approve, body.note())
                                .switchIfEmpty(Mono.error(new MarketplaceException(409,
                                        "处置单状态已变更，或审批人不能是提审人")))
                                .flatMap(updated -> audits.append(id, approve ? "approved" : "rejected",
                                                caller.accountId(), caller.role(), "in_review",
                                                updated.status(), body.note())
                                        .thenReturn(updated))))
                .map(updated -> ResponseEntity.ok(Map.of("success", true, "data", toBody(updated))));
    }

    /** 收单（approved→resolved，记处置结果）。非 approved / 版本不符 → 409。 */
    @PostMapping(value = "/api/ops/cases/{id}/resolve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> resolve(
            @PathVariable String id, @RequestBody OpsCaseResolveRequest body, ServerHttpRequest request) {
        return callers.requireOpsOperator(request)
                .flatMap(caller -> transactions.transactional(
                        cases.resolve(id, body.requireExpectedVersion(), body.resolution())
                                .switchIfEmpty(Mono.error(new MarketplaceException(409,
                                        "处置单未审批通过或状态已变更")))
                                .flatMap(updated -> audits.append(id, "resolved", caller.accountId(),
                                                caller.role(), "approved", updated.status(), body.note())
                                        .thenReturn(updated))))
                .map(updated -> ResponseEntity.ok(Map.of("success", true, "data", toBody(updated))));
    }

    /**
     * 执行受限处置动作（Stage 2）。须 case 已 {@code approved}，须带 {@code operationId} 幂等键。
     *
     * <p>动作集封闭：{@code retry_reconciliation} / {@code release_funds}。见
     * {@link OpsCaseActionService} —— 只复用 finance 既有原语，不新增资金原语，刻意不提供 capture。
     */
    @PostMapping(value = "/api/ops/cases/{id}/actions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> executeAction(
            @PathVariable String id, @RequestBody OpsCaseActionExecuteRequest body, ServerHttpRequest request) {
        String action = body.requireAction();
        String operationId = body.requireOperationId();
        return callers.requireOpsOperator(request)
                .flatMap(caller -> actionService.execute(id, action, operationId,
                        caller.accountId(), caller.role()))
                .map(executed -> ResponseEntity.ok(Map.of("success", true, "data", toActionBody(executed))));
    }

    /** 死信队列（Stage 2）。{@code status} 省略 → 仅 pending。 */
    @GetMapping("/api/ops/dlt")
    public Mono<ResponseEntity<Map<String, Object>>> listDlt(
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "50") int limit,
            ServerHttpRequest request) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return callers.requireOpsOperator(request)
                .then(dltMessages.list(status, capped).map(OpsCaseController::toDltBody).collectList())
                .map(items -> ResponseEntity.ok(Map.of("success", true, "data", items)));
    }

    /**
     * 死信重投（{@code replay=true}，回原 topic）或弃置（{@code replay=false}，只标记不删）。
     * 同样须对应 case 已 {@code approved} + {@code operationId} 幂等键。
     */
    @PostMapping(value = "/api/ops/dlt/{messageId}/actions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> executeDltAction(
            @PathVariable String messageId, @RequestBody OpsDltActionRequest body, ServerHttpRequest request) {
        boolean replay = body.requireReplay();
        String operationId = body.requireOperationId();
        return callers.requireOpsOperator(request)
                .flatMap(caller -> dltActions.execute(messageId, replay, operationId,
                        caller.accountId(), caller.role()))
                .map(executed -> ResponseEntity.ok(Map.of("success", true, "data", toActionBody(executed))));
    }

    /**
     * 「待判定」核验（Stage 3）：{@code inconclusive} 且交付物仍 {@code submitted} 的履约。
     *
     * <p><b>只读、无处置动作</b> —— 见 {@link OpsPendingVerification}：inconclusive 永不阻断结算，
     * 平台侧的决策权在商家的 confirm / reject，运营台只提供可见性。
     */
    @GetMapping("/api/ops/pending-verifications")
    public Mono<ResponseEntity<Map<String, Object>>> listPendingVerifications(
            @RequestParam(required = false, defaultValue = "50") int limit,
            ServerHttpRequest request) {
        int capped = Math.max(1, Math.min(limit, MAX_LIMIT));
        return callers.requireOpsOperator(request)
                .then(pendingVerifications.list(capped)
                        .map(OpsCaseController::toPendingVerificationBody).collectList())
                .map(items -> ResponseEntity.ok(Map.of("success", true, "data", items)));
    }

    /**
     * 对 inconclusive 交付物做人工改判（GL-P2-ADMIN-004）。
     *
     * <p>人工结论写入独立 {@code verification_override}，不覆盖自动核验真相；confirm/结算/队列读端
     * 统一通过 {@code findEffectiveStatus} 让 override 优先。只允许对自动 inconclusive 做改判，
     * 避免运营覆盖已经确定的自动 passed/failed；同 submission upsert，重复改判幂等且可修正。
     */
    @PostMapping(value = "/api/ops/pending-verifications/{submissionId}/override",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> overrideVerification(
            @PathVariable String submissionId,
            @RequestBody VerificationOverrideRequest body,
            ServerHttpRequest request) {
        String status = body.requireStatus();
        String note = body.requireNote();
        return callers.requireOpsOperator(request)
                .flatMap(caller -> verifications.findBySubmission(submissionId)
                        .switchIfEmpty(Mono.error(new MarketplaceException(404, "核验记录不存在")))
                        .flatMap(verification -> {
                            if (!"inconclusive".equalsIgnoreCase(verification.status())) {
                                return Mono.<VerificationOverride>error(new MarketplaceException(
                                        409, "仅可人工复核 inconclusive 核验"));
                            }
                            return transactions.transactional(
                                    verificationOverrides.upsert(submissionId, status, caller.accountId(), note));
                        }))
                .map(override -> ResponseEntity.ok(Map.of("success", true,
                        "data", toVerificationOverrideBody(override))));
    }

    private static Map<String, Object> toVerificationOverrideBody(VerificationOverride override) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", override.id());
        body.put("submissionId", override.submissionId());
        body.put("status", override.status());
        body.put("reviewerAccountId", override.reviewerAccountId());
        body.put("reviewNote", override.reviewNote());
        body.put("createdAt", override.createdAt());
        body.put("updatedAt", override.updatedAt());
        return body;
    }

    /** 人工改判请求：status 只能 passed/failed，note 必填且最多 500 字。 */
    public record VerificationOverrideRequest(String status, String note) {
        String requireStatus() {
            if (status == null || (!"passed".equalsIgnoreCase(status) && !"failed".equalsIgnoreCase(status))) {
                throw new MarketplaceException(400, "status 仅支持 passed/failed");
            }
            return status.toLowerCase(java.util.Locale.ROOT);
        }

        String requireNote() {
            String trimmed = note == null ? "" : note.trim();
            if (trimmed.isEmpty()) {
                throw new MarketplaceException(400, "人工复核必须填写原因");
            }
            if (trimmed.length() > 500) {
                throw new MarketplaceException(400, "人工复核原因过长（上限 500 字）");
            }
            return trimmed;
        }
    }

    private static Map<String, Object> toPendingVerificationBody(OpsPendingVerification v) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("verificationId", v.verificationId());
        body.put("submissionId", v.submissionId());
        body.put("applicationId", v.applicationId());
        body.put("taskId", v.taskId());
        body.put("taskTitle", v.taskTitle());
        body.put("organizationId", v.organizationId());
        body.put("recommenderAccountId", v.recommenderAccountId());
        body.put("contentUrl", v.contentUrl());
        body.put("checks", v.checksJson());
        body.put("lastCheckedAt", v.lastCheckedAt());
        body.put("submittedAt", v.submittedAt());
        return body;
    }

    private static Map<String, Object> toActionBody(OpsCaseAction a) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", a.id());
        body.put("caseId", a.caseId());
        body.put("operationId", a.operationId());
        body.put("action", a.action());
        body.put("status", a.status());
        body.put("requestedBy", a.requestedBy());
        body.put("outcome", a.outcome());
        body.put("error", a.error());
        body.put("createdAt", a.createdAt());
        body.put("completedAt", a.completedAt());
        return body;
    }

    private static Map<String, Object> toDltBody(OpsDltMessage m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", m.id());
        body.put("topic", m.topic());
        body.put("partition", m.partition());
        body.put("offset", m.offset());
        body.put("originalTopic", m.originalTopic());
        body.put("messageKey", m.messageKey());
        body.put("payload", m.payload());
        body.put("errorSummary", m.errorSummary());
        body.put("status", m.status());
        body.put("replayedAt", m.replayedAt());
        body.put("discardedAt", m.discardedAt());
        body.put("createdAt", m.createdAt());
        return body;
    }

    private static Map<String, Object> toBody(OpsCase c) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", c.id());
        body.put("sourceKind", c.sourceKind());
        body.put("sourceRef", c.sourceRef());
        body.put("organizationId", c.organizationId());
        body.put("applicationId", c.applicationId());
        body.put("reason", c.reason());
        body.put("severity", c.severity());
        body.put("status", c.status());
        body.put("version", c.version());
        body.put("submittedBy", c.submittedBy());
        body.put("submittedAt", c.submittedAt());
        body.put("submitNote", c.submitNote());
        body.put("approvedBy", c.approvedBy());
        body.put("approvedAt", c.approvedAt());
        body.put("approveNote", c.approveNote());
        body.put("resolvedAt", c.resolvedAt());
        body.put("resolution", c.resolution());
        body.put("createdAt", c.createdAt());
        body.put("updatedAt", c.updatedAt());
        return body;
    }

    private static Map<String, Object> toQueueBody(OpsCaseRepository.QueueItem item) {
        Map<String, Object> body = toBody(item.opsCase());
        body.put("premiumSupport", item.premiumSupport());
        body.put("supportPriority", item.supportPriority());
        body.put("supportBadge", item.premiumSupport() ? "premium" : "standard");
        return body;
    }

    private static Map<String, Object> toAuditBody(OpsCaseAudit a) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", a.id());
        body.put("action", a.action());
        body.put("actorAccountId", a.actorAccountId());
        body.put("actorRole", a.actorRole());
        body.put("fromStatus", a.fromStatus());
        body.put("toStatus", a.toStatus());
        body.put("note", a.note());
        body.put("createdAt", a.createdAt());
        return body;
    }
}
