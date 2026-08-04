package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * KYB 审核流程 HTTP 入口（Admin）。GL-P3-MERCHANT-001。
 *
 * <ul>
 *   <li>GET — 列出待审核请求（pending/under_review），需 admin 角色。</li>
 *   <li>POST /{id}/approve — 批准审核，需 admin 角色。</li>
 *   <li>POST /{id}/reject — 拒绝审核，需 admin 角色。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/kyb-requests")
public class KybVerificationController {

    private final CurrentAccountResolver accounts;
    private final KybVerificationRequestRepository requests;
    private final MerchantProfileRepository merchantProfiles;
    private final WithdrawalAccountRepository withdrawalAccounts;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public KybVerificationController(
            CurrentAccountResolver accounts,
            KybVerificationRequestRepository requests,
            MerchantProfileRepository merchantProfiles,
            WithdrawalAccountRepository withdrawalAccounts,
            OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.accounts = accounts;
        this.requests = requests;
        this.merchantProfiles = merchantProfiles;
        this.withdrawalAccounts = withdrawalAccounts;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> listPending(ServerHttpRequest request) {
        return accounts.requireAdmin(request)
                .flatMap(admin -> requests.findPending().collectList())
                .map(list -> ResponseEntity.ok(Map.of("success", true,
                        "data", list.stream().map(this::toBody).toList())));
    }

    @PostMapping(path = "/{id}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> approve(@PathVariable String id,
                                                              @RequestBody ReviewRequest body,
                                                              ServerHttpRequest request) {
        return review(id, KybRequestStatus.APPROVED, body, request);
    }

    @PostMapping(path = "/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> reject(@PathVariable String id,
                                                              @RequestBody ReviewRequest body,
                                                              ServerHttpRequest request) {
        return review(id, KybRequestStatus.REJECTED, body, request);
    }

    /**
     * 审核裁定。approve/reject 唯一差别是终态值，故合并到一处。
     *
     * <p><b>鉴权</b>：`requireAdmin` 是这里唯一的门禁——identity-service 无全局 security filter，
     * 此前这三个端点接了 {@code ServerHttpRequest} 却从不鉴权，等于任何人可批准自己的 KYB。
     *
     * <p><b>reviewer</b>：用真实 {@code admin.id()}。此前硬编码字符串 {@code "admin"} 绑进
     * {@code reviewer_account_id = CAST(:reviewer AS uuid)}，真跑必 SQL 报错。
     */
    private Mono<ResponseEntity<Map<String, Object>>> review(String id, KybRequestStatus decision,
                                                              ReviewRequest body, ServerHttpRequest request) {
        return accounts.requireAdmin(request)
                .flatMap(admin -> {
                    UUID requestId = KybSubmissionService.parseUuid(id, "审核请求 ID");
                    return transactions.transactional(
                            requests.findById(requestId)
                                    .switchIfEmpty(Mono.error(new IdentityException(404, "审核请求不存在")))
                                    .flatMap(req -> KybRequestStatus.fromDb(req.status()).isTerminal()
                                            ? Mono.<KybVerificationRequest>error(
                                                    new IdentityException(409, "审核请求已处理"))
                                            : Mono.just(req))
                                    .flatMap(req -> requests.updateStatus(
                                                    req.id(), decision.dbValue(), admin.id(), body.note())
                                            .switchIfEmpty(Mono.error(new IdentityException(409, "审核请求已处理")))
                                            .flatMap(updated -> updateTargetStatus(
                                                            updated, decision.dbValue(), admin.id(), body.note())
                                                    .then(emitReviewEvent(updated, decision.dbValue(), body.note()))
                                                    .thenReturn(updated))));
                })
                .map(req -> ResponseEntity.ok(Map.of("success", true, "data", toBody(req))));
    }

    /**
     * 根据审核类型更新目标表状态。
     *
     * <p>{@code submittedAt} 传原值而非 null——`updateStatus` 是全字段 SET，
     * 传 null 会把提交时间抹掉（审核完成后就看不出这单是什么时候提交的了）。
     */
    private Mono<Void> updateTargetStatus(KybVerificationRequest req, String status, String adminId, String note) {
        KybVerificationType type = KybVerificationType.fromDb(req.verificationType());
        return switch (type) {
            case MERCHANT_PROFILE -> merchantProfiles.findById(req.organizationId())
                    .flatMap(profile -> merchantProfiles.updateStatus(req.organizationId(), status,
                            profile.submittedAt(), Instant.now(), adminId, note))
                    .then();
            case WITHDRAWAL_ACCOUNT -> withdrawalAccounts.findById(req.targetId())
                    .flatMap(acc -> withdrawalAccounts.updateStatus(req.targetId(), status,
                            acc.submittedAt(), Instant.now(), adminId, note))
                    .then();
            case STORE_PROFILE -> Mono.empty(); // store_profile 暂无审核流程（V16 建表但无端点，属 Slice 2）
        };
    }

    /** 发送审核事件。*/
    private Mono<Void> emitReviewEvent(KybVerificationRequest req, String decision, String note) {
        KybVerificationType type = KybVerificationType.fromDb(req.verificationType());
        String eventType = switch (type) {
            case MERCHANT_PROFILE -> decision.equals("approved") ? "MerchantProfileApproved" : "MerchantProfileRejected";
            case WITHDRAWAL_ACCOUNT -> decision.equals("approved") ? "WithdrawalAccountApproved" : "WithdrawalAccountRejected";
            case STORE_PROFILE -> null; // 暂无事件
        };
        if (eventType == null) {
            return Mono.empty();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("requestId", req.id().toString());
        payload.put("organizationId", req.organizationId());
        payload.put("decision", decision);
        payload.put("note", note != null ? note : "");
        return outbox.append(new EventEnvelope(
                UUID.randomUUID().toString(), eventType, type.aggregateType(),
                req.targetId() != null ? req.targetId().toString() : req.organizationId(),
                1, Instant.now(), null, payload));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private Map<String, Object> toBody(KybVerificationRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", req.id());
        m.put("organizationId", req.organizationId());
        m.put("requesterAccountId", req.requesterAccountId());
        m.put("verificationType", req.verificationType());
        m.put("targetId", req.targetId());
        m.put("materials", req.materials());
        m.put("status", req.status());
        m.put("reviewerAccountId", req.reviewerAccountId());
        m.put("reviewNote", req.reviewNote());
        m.put("reviewDeadline", req.reviewDeadline() == null ? null : req.reviewDeadline().toString());
        m.put("createdAt", req.createdAt() == null ? null : req.createdAt().toString());
        return m;
    }

    public record ReviewRequest(String note) {}
}
