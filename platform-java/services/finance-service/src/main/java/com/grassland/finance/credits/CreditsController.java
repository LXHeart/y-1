package com.grassland.finance.credits;

import com.grassland.finance.credits.CreditsRepository.CreditsAccount;
import com.grassland.finance.credits.CreditsRepository.CreditsTransaction;
import com.grassland.finance.credits.CreditsService.MutationResult;
import com.grassland.finance.credits.CreditsService.CompensationResult;
import com.grassland.finance.credits.CreditsService.UsageReservationResult;
import com.grassland.finance.credits.CreditsService.UsageSettlementResult;
import com.grassland.finance.security.FinanceCallerResolver;
import com.grassland.finance.security.FinanceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
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
 * <p><b>内部命令（容器直连、服务断言）</b>：{@code /internal/credits/**}。
 * 每个端点按已验签的服务 principal 授权：identity 可读取批量余额并人工调账，
 * intelligence 可扣减、补偿及执行受限 AI 退款。通用共享密钥不授予任何权限。
 * 响应保持 legacy 信封 {@code {success:true, data:{…,deduplicated}}}，402→{@code {success:false,error}}（经全局 advice）。
 * {@code operation_id} 原样透传给 {@link CreditsService}（调用方自行派生 {@code refund:<consumeId>}）。
 */
@RestController
public class CreditsController {

    private static final int HISTORY_LIMIT = 50;
    private static final int MAX_FEATURE_LENGTH = 64;
    private static final int MAX_OPERATION_ID_LENGTH = 256;
    private static final int MAX_NOTE_LENGTH = 512;
    private static final Set<String> AI_QUOTA_FEATURES = Set.of(
            "video_analysis", "image_analysis", "article_generation", "comedy_generation",
            "video_production_script", "video_production_video", "ai_run_text",
            "ai_run_voice", "ai_run_embedding", "intelligence_smoke",
            "creation_assistant", "moments_generation", "video_studio_bgm",
            "card_series_plan");

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
    public Mono<Map<String, Object>> consume(
            ServerHttpRequest request, @RequestBody ConsumeRequest body) {
        return callers.requireService(request, FinanceCallerResolver.INTELLIGENCE_SERVICE)
                .then(credits.consume(body.accountId(), body.feature(), body.operationId(),
                        body.aiQuotaMultiplierBps(), body.policyVersion()))
                .map(result -> success(mutationBody("consumed", result)));
    }

    @PostMapping("/internal/credits/refund")
    public Mono<Map<String, Object>> refund(
            ServerHttpRequest request, @RequestBody RefundRequest body) {
        int amount = body.amount() == null ? 1 : body.amount();
        return callers.resolve(request)
                .filter(caller -> caller.isServicePrincipal(FinanceCallerResolver.IDENTITY_SERVICE)
                        || isAuthorizedIntelligenceRefund(caller, body, amount))
                .switchIfEmpty(Mono.error(new FinanceException(403, "无权执行积分退款")))
                .then(credits.refund(
                        body.accountId(), amount, body.feature(), body.note(), body.operationId()))
                .map(result -> success(mutationBody("refunded", result)));
    }

    @PostMapping("/internal/credits/consume-compensations")
    public Mono<Map<String, Object>> compensateConsume(
            ServerHttpRequest request, @RequestBody ConsumeCompensationRequest body) {
        return callers.requireService(request, FinanceCallerResolver.INTELLIGENCE_SERVICE)
                .then(credits.compensateConsume(
                        body.accountId(), body.feature(), body.consumeOperationId(), body.note()))
                .map(CreditsController::compensationBody);
    }

    @PostMapping("/internal/credits/usage-reservations")
    public Mono<Map<String, Object>> reserveUsage(
            ServerHttpRequest request, @RequestBody UsageReservationRequest body) {
        return callers.requireService(request, FinanceCallerResolver.INTELLIGENCE_SERVICE)
                .then(credits.reserveUsage(
                        body.accountId(), body.feature(), body.operationId(), body.estimatedCents(),
                        body.creditsCentsPolicyVersion(), body.aiQuotaMultiplierBps(), body.policyVersion()))
                .map(CreditsController::usageReservationBody);
    }

    @PostMapping("/internal/credits/usage-settlements")
    public Mono<Map<String, Object>> settleUsage(
            ServerHttpRequest request, @RequestBody UsageSettlementRequest body) {
        return callers.requireService(request, FinanceCallerResolver.INTELLIGENCE_SERVICE)
                .then(credits.settleUsage(
                        body.accountId(), body.feature(), body.consumeOperationId(),
                        body.actualCents(), body.creditsCentsPolicyVersion()))
                .map(CreditsController::usageSettlementBody);
    }

    @PostMapping("/internal/credits/award")
    public Mono<Map<String, Object>> award(
            ServerHttpRequest request, @RequestBody AwardRequest body) {
        return callers.requireService(request, FinanceCallerResolver.IDENTITY_SERVICE)
                .then(credits.award(body.accountId(), body.amount(), body.note(), body.operationId()))
                .map(result -> success(mutationBody("awarded", result)));
    }

    /**
     * 批量余额（admin 用户列表用，避免 N+1）。
     *
     * <p>仅接受 identity-service 签发、受众为 finance 的服务断言。
     * 未建户的 accountId 不在 {@code accounts} 数组里（调用方按缺失 = 0 余额处理）。
     */
    @PostMapping("/internal/credits/balances")
    public Mono<Map<String, Object>> internalBalances(
            ServerHttpRequest request, @RequestBody BalancesRequest body) {
        return callers.requireService(request, FinanceCallerResolver.IDENTITY_SERVICE)
                .thenMany(credits.balances(body.accountIds())).collectList()
                .map(accounts -> success(Map.of(
                        "accounts", accounts.stream().map(CreditsController::balanceEntry).toList())));
    }

    /** Finance-authoritative, read-only consume fences for Intelligence reconciliation. */
    @PostMapping("/internal/credits/consume-operations/query")
    public Mono<Map<String, Object>> consumeOperations(
            ServerHttpRequest request, @RequestBody ConsumeOperationsRequest body) {
        return callers.requireService(request, FinanceCallerResolver.INTELLIGENCE_SERVICE)
                .thenMany(credits.consumeOperations(body.operationIds()))
                .map(CreditsController::consumeOperationItem)
                .collectList()
                .map(items -> success(Map.of("operations", items)));
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
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", result.state());
        data.put("action", result.action());
        data.put("balance", result.balance());
        data.put("source", result.source());
        data.put("policyVersion", result.policyVersion());
        data.put("quotaLimit", result.quotaLimit());
        data.put("transactionId", result.transactionId());
        return success(data);
    }

    private static Map<String, Object> mutationBody(String action, MutationResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(action, true);
        data.put("balance", result.balance());
        data.put("deduplicated", result.deduplicated());
        data.put("transactionId", result.transactionId());
        data.put("source", result.source());
        data.put("policyVersion", result.policyVersion());
        data.put("quotaLimit", result.quotaLimit());
        return data;
    }

    private static Map<String, Object> usageReservationBody(UsageReservationResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("reserved", true);
        data.put("balance", result.balance());
        data.put("deduplicated", result.deduplicated());
        data.put("transactionId", result.transactionId());
        data.put("source", result.source());
        data.put("policyVersion", result.entitlementPolicyVersion());
        data.put("quotaLimit", result.quotaLimit());
        data.put("creditsCentsPolicyVersion", result.creditsCentsPolicyVersion());
        data.put("reservedCents", result.reservedCents());
        data.put("reservedCredits", result.reservedCredits());
        return success(data);
    }

    private static Map<String, Object> usageSettlementBody(UsageSettlementResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settled", true);
        data.put("balance", result.balance());
        data.put("deduplicated", result.deduplicated());
        data.put("transactionId", result.transactionId());
        data.put("source", result.source());
        data.put("creditsCentsPolicyVersion", result.creditsCentsPolicyVersion());
        data.put("reservedCents", result.reservedCents());
        data.put("reservedCredits", result.reservedCredits());
        data.put("actualCents", result.actualCents());
        data.put("actualCredits", result.actualCredits());
        data.put("adjustmentCredits", result.adjustmentCredits());
        return success(data);
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

    private static Map<String, Object> consumeOperationItem(
            CreditsRepository.ConsumeOperation operation) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("operationId", operation.operationId());
        item.put("accountId", operation.accountId());
        item.put("feature", operation.feature());
        item.put("state", operation.state());
        item.put("source", operation.chargeSource());
        item.put("policyVersion", operation.policyVersion());
        item.put("consumeTransactionId", "quota".equals(operation.chargeSource())
                ? operation.quotaConsumeTransactionId() : operation.consumeTransactionId());
        item.put("refundTransactionId", "quota".equals(operation.chargeSource())
                ? operation.quotaRefundTransactionId() : operation.refundTransactionId());
        item.put("usagePriced", operation.usagePriced());
        item.put("creditsCentsPolicyVersion", operation.creditsCentsPolicyVersion());
        item.put("reservedCents", operation.reservedCents());
        item.put("reservedCredits", operation.reservedCredits());
        item.put("actualCents", operation.actualCents());
        item.put("actualCredits", operation.actualCredits());
        item.put("adjustmentCredits", operation.adjustmentCredits());
        item.put("settlementTransactionId", operation.settlementTransactionId());
        return item;
    }

    /** consume 请求体：accountId/feature 必填，operationId 可选（缺失=非幂等一次性扣减）。 */
    public record ConsumeRequest(
            String accountId, String feature, String operationId,
            Integer aiQuotaMultiplierBps, Long policyVersion) {
        public ConsumeRequest {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("缺少 accountId");
            }
            requireCanonicalUuid(accountId, "accountId 无效");
            if (feature == null || feature.isBlank()) {
                throw new IllegalArgumentException("缺少 feature");
            }
            requireMaxLength(feature, MAX_FEATURE_LENGTH, "feature 过长");
            if (operationId != null && operationId.isBlank()) {
                throw new IllegalArgumentException("operationId 无效");
            }
            requireMaxLength(operationId, MAX_OPERATION_ID_LENGTH, "operationId 过长");
            if ((aiQuotaMultiplierBps == null) != (policyVersion == null)) {
                throw new IllegalArgumentException("AI 权益快照字段不完整");
            }
            if (aiQuotaMultiplierBps != null) {
                if (!AI_QUOTA_FEATURES.contains(feature)) {
                    throw new IllegalArgumentException("feature 不支持 AI 免费额度");
                }
                if (operationId == null) {
                    throw new IllegalArgumentException("AI 权益扣减必须提供 operationId");
                }
                if (aiQuotaMultiplierBps < 1_000 || aiQuotaMultiplierBps > 100_000) {
                    throw new IllegalArgumentException("aiQuotaMultiplierBps 超出范围");
                }
                if (policyVersion < 1) {
                    throw new IllegalArgumentException("policyVersion 必须大于等于 1");
                }
            }
        }

        boolean hasAiQuotaEntitlement() {
            return aiQuotaMultiplierBps != null;
        }
    }

    /** refund 请求体：amount 默认 1（兼容只退 1 的失败退款与 admin 退 |amount|）。 */
    public record RefundRequest(String accountId, Integer amount, String feature, String note, String operationId) {
        public RefundRequest {
            if (accountId == null || accountId.isBlank()) {
                throw new IllegalArgumentException("缺少 accountId");
            }
            requireCanonicalUuid(accountId, "accountId 无效");
            if (feature == null || feature.isBlank()) {
                throw new IllegalArgumentException("缺少 feature");
            }
            requireMaxLength(feature, MAX_FEATURE_LENGTH, "feature 过长");
            if (operationId != null && operationId.isBlank()) {
                throw new IllegalArgumentException("operationId 无效");
            }
            requireMaxLength(operationId, MAX_OPERATION_ID_LENGTH, "operationId 过长");
            requireMaxLength(note, MAX_NOTE_LENGTH, "note 过长");
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

    public record UsageReservationRequest(
            String accountId,
            String feature,
            String operationId,
            Long estimatedCents,
            String creditsCentsPolicyVersion,
            Integer aiQuotaMultiplierBps,
            Long policyVersion) {
        public UsageReservationRequest {
            requireUsageScope(accountId, feature, operationId, creditsCentsPolicyVersion);
            if (estimatedCents == null || estimatedCents < 0) {
                throw new IllegalArgumentException("estimatedCents 必须大于等于 0");
            }
            if ((aiQuotaMultiplierBps == null) != (policyVersion == null)) {
                throw new IllegalArgumentException("AI 权益快照字段不完整");
            }
        }
    }

    public record UsageSettlementRequest(
            String accountId,
            String feature,
            String consumeOperationId,
            Long actualCents,
            String creditsCentsPolicyVersion) {
        public UsageSettlementRequest {
            requireUsageScope(accountId, feature, consumeOperationId, creditsCentsPolicyVersion);
            if (actualCents == null || actualCents < 0) {
                throw new IllegalArgumentException("actualCents 必须大于等于 0");
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

    public record ConsumeOperationsRequest(java.util.List<String> operationIds) {
        public ConsumeOperationsRequest {
            if (operationIds == null) {
                throw new IllegalArgumentException("缺少 operationIds");
            }
            if (operationIds.size() > 500) {
                throw new IllegalArgumentException("operationIds 过多（上限 500）");
            }
            operationIds = operationIds.stream().distinct().toList();
            for (String operationId : operationIds) {
                if (operationId == null || operationId.isBlank()) {
                    throw new IllegalArgumentException("operationId 无效");
                }
                requireMaxLength(operationId, MAX_OPERATION_ID_LENGTH, "operationId 过长");
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

    private static void requireUsageScope(
            String accountId, String feature, String operationId, String moneyPolicyVersion) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("缺少 accountId");
        }
        requireCanonicalUuid(accountId, "accountId 无效");
        if (feature == null || feature.isBlank() || !AI_QUOTA_FEATURES.contains(feature)) {
            throw new IllegalArgumentException("feature 不支持按 AI 用量结算");
        }
        requireMaxLength(feature, MAX_FEATURE_LENGTH, "feature 过长");
        if (operationId == null || operationId.isBlank()) {
            throw new IllegalArgumentException("缺少 operationId");
        }
        requireMaxLength(operationId, MAX_OPERATION_ID_LENGTH, "operationId 过长");
        if (moneyPolicyVersion == null || moneyPolicyVersion.isBlank()
                || moneyPolicyVersion.length() > 64
                || !moneyPolicyVersion.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("creditsCentsPolicyVersion 无效");
        }
    }

    private static void requireMaxLength(String value, int maxLength, String message) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean isAuthorizedIntelligenceRefund(
            FinanceCallerResolver.Caller caller, RefundRequest body, int amount) {
        return caller.isServicePrincipal(FinanceCallerResolver.INTELLIGENCE_SERVICE)
                && amount == 1
                && AI_QUOTA_FEATURES.contains(body.feature())
                && body.operationId() != null
                && body.operationId().startsWith("refund:")
                && body.operationId().length() > "refund:".length();
    }

}
