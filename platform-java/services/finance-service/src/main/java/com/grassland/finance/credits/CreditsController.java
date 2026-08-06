package com.grassland.finance.credits;

import com.grassland.finance.credits.CreditsRepository.CreditsAccount;
import com.grassland.finance.credits.CreditsRepository.CreditsTransaction;
import com.grassland.finance.credits.CreditsService.MutationResult;
import com.grassland.finance.credits.CreditsService.CompensationResult;
import com.grassland.finance.security.FinanceCallerResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 积分 HTTP 入口（GL-P3-AI-001 下属切片）。两类端点，两种鉴权：
 *
 * <p><b>公共读（经 edge-bff，用户断言）</b>：{@code GET /api/credits/{balance,history}}。
 * accountId 取自 {@link FinanceCallerResolver}（edge-bff 签发的 {@code X-Grassland-Identity}），不接受路径/请求体传入，
 * 故无越权维度。返回 legacy 同构响应：balance 裸对象、history 包 {@code {history:[…]}}。
 *
 * <p><b>内部写（容器直连，共享密钥）</b>：{@code POST /internal/credits/{consume,refund,award}}。
 * 鉴权由 {@link CreditsInternalAuthFilter}（{@code X-Internal-Key} + rejectForwarded）把关；
 * 响应保持 legacy 信封 {@code {success:true, data:{…,deduplicated}}}，402→{@code {success:false,error}}（经全局 advice）。
 * {@code operation_id} 原样透传给 {@link CreditsService}（调用方自行派生 {@code refund:<consumeId>}）。
 */
@RestController
public class CreditsController {

    private static final int HISTORY_LIMIT = 50;
    private static final int MAX_FEATURE_LENGTH = 64;
    private static final int MAX_OPERATION_ID_LENGTH = 256;
    private static final int MAX_NOTE_LENGTH = 512;

    private final FinanceCallerResolver callers;
    private final CreditsService credits;

    public CreditsController(FinanceCallerResolver callers, CreditsService credits) {
        this.callers = callers;
        this.credits = credits;
    }

    // ---------------- 公共读 ----------------

    @GetMapping("/api/credits/balance")
    public Mono<Map<String, Object>> balance(ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> credits.balance(caller.accountId()))
                .map(CreditsController::balanceBody);
    }

    @GetMapping("/api/credits/history")
    public Mono<Map<String, Object>> history(ServerHttpRequest request) {
        return callers.resolve(request)
                .flatMap(caller -> credits.history(caller.accountId(), HISTORY_LIMIT).collectList())
                .map(items -> Map.<String, Object>of("history", items.stream().map(CreditsController::historyItem).toList()));
    }

    // ---------------- 内部写 ----------------

    @PostMapping("/internal/credits/consume")
    public Mono<Map<String, Object>> consume(@RequestBody ConsumeRequest body) {
        return credits.consume(body.accountId(), body.feature(), body.operationId())
                .map(r -> success(Map.of("consumed", true, "balance", r.balance(),
                        "deduplicated", r.deduplicated(), "transactionId", r.transactionId())));
    }

    @PostMapping("/internal/credits/refund")
    public Mono<Map<String, Object>> refund(@RequestBody RefundRequest body) {
        int amount = body.amount() == null ? 1 : body.amount();
        return credits.refund(body.accountId(), amount, body.feature(), body.note(), body.operationId())
                .map(r -> success(Map.of("refunded", true, "balance", r.balance(),
                        "deduplicated", r.deduplicated(), "transactionId", r.transactionId())));
    }

    @PostMapping("/internal/credits/consume-compensations")
    public Mono<Map<String, Object>> compensateConsume(@RequestBody ConsumeCompensationRequest body) {
        return credits.compensateConsume(
                        body.accountId(), body.feature(), body.consumeOperationId(), body.note())
                .map(CreditsController::compensationBody);
    }

    @PostMapping("/internal/credits/award")
    public Mono<Map<String, Object>> award(@RequestBody AwardRequest body) {
        return credits.award(body.accountId(), body.amount(), body.note(), body.operationId())
                .map(r -> success(Map.of("awarded", true, "balance", r.balance(),
                        "deduplicated", r.deduplicated(), "transactionId", r.transactionId())));
    }

    // ---------------- 内部读（容器直连，共享密钥）——供 legacy Express 回滚读端代理 ----------------

    @GetMapping("/internal/credits/balance")
    public Mono<Map<String, Object>> internalBalance(@org.springframework.web.bind.annotation.RequestParam String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("缺少 accountId");
        }
        return credits.balance(accountId).map(CreditsController::balanceBody);
    }

    @GetMapping("/internal/credits/history")
    public Mono<Map<String, Object>> internalHistory(
            @org.springframework.web.bind.annotation.RequestParam String accountId,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "50") int limit) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("缺少 accountId");
        }
        return credits.history(accountId, limit).collectList()
                .map(items -> Map.<String, Object>of("history", items.stream().map(CreditsController::historyItem).toList()));
    }

    /**
     * 批量余额（admin 用户列表用，避免 N+1）。
     *
     * <p>容器直连，{@code X-Internal-Key} 鉴权（同其它 {@code /internal/credits/*}）。
     * 未建户的 accountId 不在 {@code accounts} 数组里（调用方按缺失 = 0 余额处理）。
     */
    @PostMapping("/internal/credits/balances")
    public Mono<Map<String, Object>> internalBalances(@RequestBody BalancesRequest body) {
        return credits.balances(body.accountIds()).collectList()
                .map(accounts -> success(Map.of(
                        "accounts", accounts.stream().map(CreditsController::balanceEntry).toList())));
    }

    // ---------------- helpers ----------------

    private static Map<String, Object> success(Object data) {
        return Map.of("success", true, "data", data);
    }

    private static Map<String, Object> balanceBody(CreditsAccount acct) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("balance", acct.balance());
        body.put("totalEarned", acct.totalEarned());
        body.put("totalSpent", acct.totalSpent());
        return body;
    }

    /** balances 批量端点的单条 entry（带 accountId，供调用方按 id 索引）。 */
    private static Map<String, Object> balanceEntry(CreditsAccount acct) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountId", acct.accountId());
        body.put("balance", acct.balance());
        body.put("totalEarned", acct.totalEarned());
        body.put("totalSpent", acct.totalSpent());
        return body;
    }

    private static Map<String, Object> compensationBody(CompensationResult result) {
        return success(Map.of(
                "state", result.state(),
                "action", result.action(),
                "balance", result.balance()));
    }

    private static Map<String, Object> historyItem(CreditsTransaction txn) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", txn.id());
        item.put("amount", txn.amount());
        item.put("balanceAfter", txn.balanceAfter());
        item.put("type", txn.type());
        item.put("feature", txn.feature());
        item.put("note", txn.note());
        item.put("createdAt", txn.createdAt() == null ? null : txn.createdAt().toString());
        return item;
    }

    /** consume 请求体：accountId/feature 必填，operationId 可选（缺失=非幂等一次性扣减）。 */
    public record ConsumeRequest(String accountId, String feature, String operationId) {
        public ConsumeRequest {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("缺少 accountId");
            }
            if (feature == null || feature.isBlank()) {
                throw new IllegalArgumentException("缺少 feature");
            }
            if (operationId != null && operationId.isBlank()) {
                throw new IllegalArgumentException("operationId 无效");
            }
        }
    }

    /** refund 请求体：amount 默认 1（兼容只退 1 的失败退款与 admin 退 |amount|）。 */
    public record RefundRequest(String accountId, Integer amount, String feature, String note, String operationId) {
        public RefundRequest {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("缺少 accountId");
            }
            if (feature == null || feature.isBlank()) {
                throw new IllegalArgumentException("缺少 feature");
            }
            if (operationId != null && operationId.isBlank()) {
                throw new IllegalArgumentException("operationId 无效");
            }
        }
    }

    /** Consume compensation derives the refund key server-side and never accepts an amount. */
    public record ConsumeCompensationRequest(
            String accountId, String feature, String consumeOperationId, String note) {
        public ConsumeCompensationRequest {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("缺少 accountId");
            }
            requireCanonicalUuid(accountId, "accountId 无效");
            if (feature == null || feature.isBlank()) {
                throw new IllegalArgumentException("缺少 feature");
            }
            requireMaxLength(feature, MAX_FEATURE_LENGTH, "feature 过长");
            if (consumeOperationId == null || consumeOperationId.isBlank()) {
                throw new IllegalArgumentException("缺少 consumeOperationId");
            }
            requireMaxLength(consumeOperationId, MAX_OPERATION_ID_LENGTH, "consumeOperationId 过长");
            requireMaxLength(note, MAX_NOTE_LENGTH, "note 过长");
        }
    }

    /** award 请求体：注册赠送 / admin 正向调整。amount 必填正整数。 */
    public record AwardRequest(String accountId, Integer amount, String note, String operationId) {
        public AwardRequest {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("缺少 accountId");
            }
            if (amount == null || amount <= 0) {
                throw new IllegalArgumentException("赠送金额必须为正");
            }
        }
    }

    /** 批量余额请求体：accountIds 上限 1000（admin 用户列表足够，防滥用）。 */
    public record BalancesRequest(java.util.List<String> accountIds) {
        public BalancesRequest {
            if (accountIds == null) {
                throw new IllegalArgumentException("缺少 accountIds");
            }
            if (accountIds.size() > 1000) {
                throw new IllegalArgumentException("accountIds 过多（上限 1000）");
            }
        }
    }

    private static void requireCanonicalUuid(String value, String message) {
        try {
            if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException(message);
            }
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void requireMaxLength(String value, int maxLength, String message) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(message);
        }
    }

}
