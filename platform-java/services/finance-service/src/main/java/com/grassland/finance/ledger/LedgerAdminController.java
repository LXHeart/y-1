package com.grassland.finance.ledger;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.finance.admin.PageEnvelope;
import com.grassland.finance.security.FinanceCallerResolver;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 财务对账台（GL-P2-ADMIN-006）。财务人员 / 平台管理员视角的只读端点。
 *
 * <p>门闩 {@code requireRole(FINANCE, PLATFORM_ADMIN)}——finance 首个使用 RBAC {@code requireRole} 的 Controller。
 * 真实 PSP 接入前对账台只反映 sandbox 流水，但读端契约不变。
 *
 * <ul>
 *   <li>{@code GET /api/admin/finance/journals} — 账本流水（按 org + 时间窗 + 分页）。</li>
 *   <li>{@code GET /api/admin/finance/journals/{id}/postings} — 某笔 journal 的借贷明细。</li>
 *   <li>{@code GET /api/admin/finance/reconcile/escrow/{orgId}} — ESCROW 物化余额 vs 账本派生余额。</li>
 *   <li>{@code GET /api/admin/finance/reconcile/wallet/{accountId}} — WALLET 对账。</li>
 * </ul>
 */
@RestController
public class LedgerAdminController {

    private final LedgerRepository ledger;
    private final LedgerProjectionService projection;
    private final FinanceCallerResolver callers;

    public LedgerAdminController(LedgerRepository ledger, LedgerProjectionService projection,
                                 FinanceCallerResolver callers) {
        this.ledger = ledger;
        this.projection = projection;
        this.callers = callers;
    }

    @GetMapping("/api/admin/finance/journals")
    public Mono<ResponseEntity<Map<String, Object>>> listJournals(
            @RequestParam(required = false) String organizationId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer offset,
            ServerHttpRequest request) {
        int pageSize = PageEnvelope.limit(limit);
        int pageOffset = PageEnvelope.offset(offset);
        return callers.requireRole(request, BackendRole.FINANCE)
                .then(Mono.zip(
                        ledger.listJournals(organizationId, from, to, pageSize, pageOffset)
                                .map(LedgerAdminController::toJournalBody).collectList(),
                        ledger.countJournals(organizationId, from, to))
                        .map(tuple -> ResponseEntity.ok(Map.of("success", true, "data", PageEnvelope
                                .data(tuple.getT1(), tuple.getT2(), pageSize, pageOffset)))));
    }

    @GetMapping("/api/admin/finance/journals/{id}/postings")
    public Mono<ResponseEntity<Map<String, Object>>> journalPostings(
            @PathVariable String id, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.FINANCE)
                .then(ledger.findPostingsByJournal(parseUuid(id))
                        .flatMapMany(reactor.core.publisher.Flux::fromIterable)
                        .map(LedgerAdminController::toPostingBody).collectList()
                        .map(items -> ResponseEntity.ok(Map.of("success", true, "data", items))));
    }

    @GetMapping("/api/admin/finance/reconcile/escrow/{orgId}")
    public Mono<ResponseEntity<Map<String, Object>>> reconcileEscrow(
            @PathVariable String orgId, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.FINANCE)
                .then(ledger.sumBalance(LedgerAccount.Type.ESCROW, orgId)
                        .zipWith(projection.reconcileEscrow(orgId))
                        .map(tuple -> ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                                "organizationId", orgId,
                                "derivedBalanceCents", tuple.getT1(),
                                "reconciled", tuple.getT2())))));
    }

    @GetMapping("/api/admin/finance/reconcile/wallet/{accountId}")
    public Mono<ResponseEntity<Map<String, Object>>> reconcileWallet(
            @PathVariable String accountId, ServerHttpRequest request) {
        return callers.requireRole(request, BackendRole.FINANCE)
                .then(ledger.sumBalance(LedgerAccount.Type.WALLET, accountId)
                        .zipWith(projection.reconcileWallet(accountId))
                        .map(tuple -> ResponseEntity.ok(Map.of("success", true, "data", Map.of(
                                "accountId", accountId,
                                "derivedBalanceCents", tuple.getT1(),
                                "reconciled", tuple.getT2())))));
    }

    private static Map<String, Object> toJournalBody(JournalEntry journal) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", journal.id().toString());
        map.put("type", journal.type().name());
        map.put("operationId", journal.operationId());
        map.put("currency", journal.currency());
        map.put("organizationId", journal.organizationId());
        map.put("engagementRef", journal.engagementRef());
        map.put("memo", journal.memo());
        map.put("createdAt", journal.createdAt() == null ? null : journal.createdAt().toString());
        return map;
    }

    private static Map<String, Object> toPostingBody(Posting posting) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("accountType", posting.account().type().name());
        map.put("accountOwner", posting.account().owner());
        map.put("accountRef", posting.account().ref());
        map.put("direction", posting.direction().name());
        map.put("amountCents", posting.amountCents());
        return map;
    }

    private static java.util.UUID parseUuid(String value) {
        try {
            return java.util.UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("id 格式无效");
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", error.getMessage()));
    }
}
