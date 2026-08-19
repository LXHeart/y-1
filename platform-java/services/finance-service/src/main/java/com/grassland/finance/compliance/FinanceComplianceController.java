package com.grassland.finance.compliance;

import com.grassland.finance.security.FinanceCallerResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class FinanceComplianceController {

    private final FinanceCallerResolver callers;
    private final FinanceComplianceRepository repository;

    public FinanceComplianceController(FinanceCallerResolver callers, FinanceComplianceRepository repository) {
        this.callers = callers;
        this.repository = repository;
    }

    @GetMapping("/internal/compliance/accounts/{accountId}/closure-check")
    public Mono<ResponseEntity<Map<String, Object>>> closureCheck(
            @PathVariable String accountId, ServerHttpRequest request) {
        return callers.requireService(request, FinanceCallerResolver.IDENTITY_SERVICE)
                .then(repository.closureSummary(accountId))
                .map(summary -> {
                    List<Map<String, Object>> blockers = new ArrayList<>();
                    if (summary.walletBalanceCents() > 0) {
                        blockers.add(blocker("WALLET_BALANCE", "钱包仍有可提现余额", 1,
                                summary.walletBalanceCents()));
                    }
                    if (summary.pendingSettlements() > 0) {
                        blockers.add(blocker("PENDING_SETTLEMENT", "仍有未完成的结算或应收", 
                                summary.pendingSettlements(), null));
                    }
                    return ResponseEntity.ok(success(Map.of("blockers", blockers)));
                });
    }

    @GetMapping("/internal/compliance/accounts/{accountId}/financial-records")
    public Mono<ResponseEntity<Map<String, Object>>> financialRecords(
            @PathVariable String accountId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "500") int limit,
            ServerHttpRequest request) {
        int pageSize = Math.max(1, Math.min(limit, 500));
        return callers.requireService(request, FinanceCallerResolver.IDENTITY_SERVICE)
                .thenMany(repository.financialRecords(accountId, offset, pageSize + 1))
                .collectList()
                .map(rows -> {
                    boolean hasMore = rows.size() > pageSize;
                    List<Map<String, Object>> records = rows.stream().limit(pageSize)
                            .map(FinanceComplianceController::recordBody).toList();
                    return ResponseEntity.ok(success(Map.of(
                            "records", records, "hasMore", hasMore, "offset", offset)));
                });
    }

    @PostMapping("/internal/compliance/accounts/{accountId}/erase")
    public Mono<ResponseEntity<Map<String, Object>>> erase(
            @PathVariable String accountId, ServerHttpRequest request) {
        return callers.requireService(request, FinanceCallerResolver.IDENTITY_SERVICE)
                .then(repository.erasePii(accountId))
                .map(counts -> ResponseEntity.ok(success(Map.of(
                        "erased", true, "counts", counts,
                        "retained", List.of("balances", "ledger", "payments", "settlements")))));
    }

    private static Map<String, Object> recordBody(FinanceComplianceRepository.FinancialRecord record) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", record.id());
        body.put("type", record.type());
        body.put("direction", record.direction());
        body.put("amountCents", record.amountCents());
        body.put("feeCents", record.feeCents());
        body.put("status", record.status());
        body.put("reference", record.reference());
        body.put("memo", record.memo());
        body.put("occurredAt", record.occurredAt() == null ? null : record.occurredAt().toString());
        return body;
    }

    private static Map<String, Object> blocker(
            String code, String message, long count, Long amountCents) {
        Map<String, Object> blocker = new LinkedHashMap<>();
        blocker.put("domain", "finance");
        blocker.put("code", code);
        blocker.put("message", message);
        blocker.put("count", count);
        blocker.put("amountCents", amountCents);
        return blocker;
    }

    private static Map<String, Object> success(Map<String, Object> data) {
        return Map.of("success", true, "data", data);
    }
}
