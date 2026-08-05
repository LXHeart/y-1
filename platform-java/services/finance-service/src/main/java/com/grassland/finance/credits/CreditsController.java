package com.grassland.finance.credits;

import com.grassland.finance.credits.CreditsRepository.CreditsAccount;
import com.grassland.finance.credits.CreditsRepository.CreditsTransaction;
import com.grassland.finance.credits.CreditsService.MutationResult;
import com.grassland.finance.security.FinanceCallerResolver;
import java.util.LinkedHashMap;
import java.util.Map;
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

}
