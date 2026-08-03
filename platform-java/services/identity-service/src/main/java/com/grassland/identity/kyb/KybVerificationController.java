package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
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

    private final KybVerificationRequestRepository requests;
    private final MerchantProfileRepository merchantProfiles;
    private final WithdrawalAccountRepository withdrawalAccounts;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public KybVerificationController(
            KybVerificationRequestRepository requests,
            MerchantProfileRepository merchantProfiles,
            WithdrawalAccountRepository withdrawalAccounts,
            OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.requests = requests;
        this.merchantProfiles = merchantProfiles;
        this.withdrawalAccounts = withdrawalAccounts;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> listPending(ServerHttpRequest request) {
        return requests.findPending().collectList()
                .map(list -> ResponseEntity.ok(Map.of("success", true,
                        "data", list.stream().map(this::toBody).toList())));
    }

    @PostMapping(path = "/{id}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> approve(@PathVariable String id,
                                                              @RequestBody ReviewRequest body,
                                                              ServerHttpRequest request) {
        return transactions.transactional(
                requests.findById(UUID.fromString(id))
                        .switchIfEmpty(Mono.error(new IdentityException(404, "审核请求不存在"))))
                .filter(req -> !req.status().equals("approved") && !req.status().equals("rejected"))
                .switchIfEmpty(Mono.error(new IdentityException(409, "审核请求已处理")))
                .flatMap(req -> {
                    String adminId = "admin"; // TODO: 从 request 获取真实 admin ID
                    return requests.updateStatus(req.id(), "approved", adminId, body.note())
                            .flatMap(updated -> {
                                // 根据审核类型更新目标状态
                                return updateTargetStatus(updated, "approved", adminId, body.note())
                                        .thenReturn(updated);
                            })
                            .flatMap(updated -> emitReviewEvent(updated, "approved", body.note())
                                    .thenReturn(updated));
                })
                .map(req -> ResponseEntity.ok(Map.of("success", true, "data", toBody(req))));
    }

    @PostMapping(path = "/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> reject(@PathVariable String id,
                                                              @RequestBody ReviewRequest body,
                                                              ServerHttpRequest request) {
        return transactions.transactional(
                requests.findById(UUID.fromString(id))
                        .switchIfEmpty(Mono.error(new IdentityException(404, "审核请求不存在"))))
                .filter(req -> !req.status().equals("approved") && !req.status().equals("rejected"))
                .switchIfEmpty(Mono.error(new IdentityException(409, "审核请求已处理")))
                .flatMap(req -> {
                    String adminId = "admin"; // TODO: 从 request 获取真实 admin ID
                    return requests.updateStatus(req.id(), "rejected", adminId, body.note())
                            .flatMap(updated -> {
                                // 根据审核类型更新目标状态
                                return updateTargetStatus(updated, "rejected", adminId, body.note())
                                        .thenReturn(updated);
                            })
                            .flatMap(updated -> emitReviewEvent(updated, "rejected", body.note())
                                    .thenReturn(updated));
                })
                .map(req -> ResponseEntity.ok(Map.of("success", true, "data", toBody(req))));
    }

    /** 根据审核类型更新目标表状态。*/
    private Mono<Void> updateTargetStatus(KybVerificationRequest req, String status, String adminId, String note) {
        KybVerificationType type = KybVerificationType.fromDb(req.verificationType());
        return switch (type) {
            case MERCHANT_PROFILE -> merchantProfiles.updateStatus(req.organizationId(), status,
                    null, Instant.now(), adminId, note).then();
            case WITHDRAWAL_ACCOUNT -> withdrawalAccounts.updateStatus(req.targetId(), status,
                    null, Instant.now(), adminId, note).then();
            case STORE_PROFILE -> Mono.empty(); // store_profile 暂无审核流程
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
        return outbox.append(new EventEnvelope(
                UUID.randomUUID().toString(), eventType, type.dbValue().substring(0, 1).toUpperCase() + type.dbValue().substring(1),
                req.targetId() != null ? req.targetId().toString() : req.organizationId(),
                1, Instant.now(), null,
                Map.of("requestId", req.id().toString(), "organizationId", req.organizationId(),
                        "decision", decision, "note", note != null ? note : "")));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private Map<String, Object> toBody(KybVerificationRequest req) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", req.id());
        m.put("organizationId", req.organizationId());
        m.put("verificationType", req.verificationType());
        m.put("targetId", req.targetId());
        m.put("status", req.status());
        m.put("reviewNote", req.reviewNote());
        m.put("reviewDeadline", req.reviewDeadline() == null ? null : req.reviewDeadline().toString());
        m.put("createdAt", req.createdAt() == null ? null : req.createdAt().toString());
        return m;
    }

    public record ReviewRequest(String note) {}
}
