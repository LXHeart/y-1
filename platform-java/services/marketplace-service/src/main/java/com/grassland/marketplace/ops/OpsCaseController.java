package com.grassland.marketplace.ops;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import com.grassland.marketplace.security.MarketplaceException;
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
    private final MarketplaceCallerResolver callers;
    private final TransactionalOperator transactions;

    public OpsCaseController(OpsCaseRepository cases, OpsCaseAuditRepository audits,
                             MarketplaceCallerResolver callers, TransactionalOperator transactions) {
        this.cases = cases;
        this.audits = audits;
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
                .then(cases.list(status, capped).map(OpsCaseController::toBody).collectList())
                .map(items -> ResponseEntity.ok(Map.of("success", true, "data", items)));
    }

    /** 详情 + 审计时间线。不存在 → 404。 */
    @GetMapping("/api/ops/cases/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> detail(@PathVariable String id, ServerHttpRequest request) {
        return callers.requireOpsOperator(request)
                .then(cases.findById(id))
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "处置单不存在")))
                .flatMap(opsCase -> audits.listByCase(id).map(OpsCaseController::toAuditBody).collectList()
                        .map(timeline -> ResponseEntity.ok(Map.of("success", true,
                                "data", Map.of("case", toBody(opsCase), "audits", timeline)))));
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
