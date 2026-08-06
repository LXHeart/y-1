package com.grassland.identity.recommenderprofile;

import com.grassland.identity.assertion.BackendRole;
import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.identityprofile.IdentityProfileRepository;
import com.grassland.identity.identityprofile.IdentityType;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 推荐官平台认证审核（GL-P2-ADMIN-002）。
 *
 * <p>两类端点：
 * <ul>
 *   <li><b>推荐官自助</b>：{@code POST /api/me/recommender-verification}（提交认证申请，须已开通 recommender 身份）；
 *       {@code GET /api/me/recommender-verification}（查我的认证状态）。</li>
 *   <li><b>平台 admin</b>：{@code GET /api/admin/recommender-requests}（待审队列）；
 *       {@code POST /api/admin/recommender-requests/{id}/{approve|reject}}（审核）。
 *       鉴权用 {@link CurrentAccountResolver#requireRole(BackendRole...)}（MERCHANT_REVIEWER 或 CONTENT_REVIEWER 或 PLATFORM_ADMIN 超集）。</li>
 * </ul>
 *
 * <p>克隆 KYB 范式：submit 同事务落申请行 + outbox；review 同事务改 status + outbox。
 * 推荐官身份开通不受本流程影响（自助开通仍即生效），认证是可选加分项。
 */
@RestController
public class RecommenderVerificationController {

    private static final int MAX_NOTE_LENGTH = 500;
    private static final String AGGREGATE = "RecommenderVerificationRequest";

    private final CurrentAccountResolver accounts;
    private final RecommenderVerificationRepository requests;
    private final IdentityProfileRepository identities;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;
    private final long reviewSlaSeconds;

    public RecommenderVerificationController(
            CurrentAccountResolver accounts,
            RecommenderVerificationRepository requests,
            IdentityProfileRepository identities,
            OutboxRepository outbox,
            TransactionalOperator transactions,
            @Value("${identity.recommender.review-sla-seconds:259200}") long reviewSlaSeconds) {
        this.accounts = accounts;
        this.requests = requests;
        this.identities = identities;
        this.outbox = outbox;
        this.transactions = transactions;
        this.reviewSlaSeconds = reviewSlaSeconds;
    }

    // ---------------- 推荐官自助 ----------------

    @GetMapping("/api/me/recommender-verification")
    public Mono<ResponseEntity<Map<String, Object>>> myStatus(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(user -> requests.findLatestByAccount(user.id())
                        .map(this::toBody)
                        .switchIfEmpty(Mono.just(Map.<String, Object>of("status", "none"))))
                .map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)));
    }

    @PostMapping(value = "/api/me/recommender-verification", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> submit(
            @RequestBody SubmitVerificationRequest body, ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(user -> identities.findByAccountAndType(user.id(), IdentityType.RECOMMENDER.dbValue())
                        .switchIfEmpty(Mono.error(new IdentityException(409, "未开通推荐官身份，请先开通")))
                        .flatMap(profile -> requests.findLatestByAccount(user.id())
                                .filter(existing -> "pending".equalsIgnoreCase(existing.status()))
                                .flatMap(existing -> Mono.<RecommenderVerificationRequest>error(
                                        new IdentityException(409, "已有待审核的认证申请")))
                                .switchIfEmpty(Mono.defer(() -> createRequest(user.id(), body)))))
                .map(req -> ResponseEntity.ok(Map.of("success", true, "data", toBody(req))));
    }

    // ---------------- 平台 admin ----------------

    @GetMapping("/api/admin/recommender-requests")
    public Mono<ResponseEntity<Map<String, Object>>> listPending(ServerHttpRequest request) {
        return accounts.requireRole(request, BackendRole.MERCHANT_REVIEWER, BackendRole.CONTENT_REVIEWER)
                .flatMap(admin -> requests.findPending().collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream().map(this::toBody).toList()))));
    }

    @PostMapping(value = "/api/admin/recommender-requests/{id}/approve", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> approve(
            @PathVariable String id, @RequestBody ReviewRequest body, ServerHttpRequest request) {
        return review(id, body, request, "approved", false);
    }

    @PostMapping(value = "/api/admin/recommender-requests/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> reject(
            @PathVariable String id, @RequestBody ReviewRequest body, ServerHttpRequest request) {
        return review(id, body, request, "rejected", true);
    }

    // ---------------- 内部 ----------------

    private Mono<RecommenderVerificationRequest> createRequest(String accountId, SubmitVerificationRequest body) {
        Instant deadline = Instant.now().plusSeconds(reviewSlaSeconds);
        String materials = body.materials();
        return transactions.transactional(
                requests.create(accountId, materials, deadline)
                        .flatMap(req -> outbox.append(reviewEvent(req, "RecommenderVerificationRequested", null))
                                .thenReturn(req)));
    }

    private Mono<ResponseEntity<Map<String, Object>>> review(
            String id, ReviewRequest body, ServerHttpRequest request, String decision, boolean requireNote) {
        return accounts.requireRole(request, BackendRole.MERCHANT_REVIEWER, BackendRole.CONTENT_REVIEWER)
                .flatMap(admin -> {
                    UUID requestId = parseUuid(id);
                    String note = requireNote ? requireReviewNote(body.note()) : trimNote(body.note());
                    return requests.findById(requestId)
                            .switchIfEmpty(Mono.error(new IdentityException(404, "认证申请不存在")))
                            .flatMap(req -> {
                                if (!"pending".equalsIgnoreCase(req.status())) {
                                    return Mono.<RecommenderVerificationRequest>error(
                                            new IdentityException(409, "该申请已审核完成"));
                                }
                                return transactions.transactional(
                                        requests.updateStatus(requestId, decision, admin.id(), note)
                                                .switchIfEmpty(Mono.error(new IdentityException(409, "该申请已审核完成")))
                                                .flatMap(updated -> {
                                                    String event = "approved".equals(decision)
                                                            ? "RecommenderVerificationApproved"
                                                            : "RecommenderVerificationRejected";
                                                    return outbox.append(reviewEvent(updated, event, note))
                                                            .thenReturn(updated);
                                                }));
                            })
                            .map(updated -> ResponseEntity.ok(Map.of("success", true, "data", toBody(updated))));
                });
    }

    private EventEnvelope reviewEvent(RecommenderVerificationRequest req, String eventType, String note) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountId", req.accountId());
        payload.put("requestId", req.id().toString());
        if (note != null) {
            payload.put("note", note);
        }
        return new EventEnvelope(
                UUID.randomUUID().toString(), eventType, AGGREGATE, req.id().toString(),
                1, Instant.now(), null, payload);
    }

    private Map<String, Object> toBody(RecommenderVerificationRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", req.id().toString());
        body.put("accountId", req.accountId());
        body.put("status", req.status());
        if (req.materials() != null) {
            body.put("materials", req.materials());
        }
        if (req.reviewNote() != null) {
            body.put("reviewNote", req.reviewNote());
        }
        if (req.reviewerAccountId() != null) {
            body.put("reviewerAccountId", req.reviewerAccountId());
        }
        if (req.reviewDeadline() != null) {
            body.put("reviewDeadline", req.reviewDeadline().toString());
        }
        if (req.createdAt() != null) {
            body.put("createdAt", req.createdAt().toString());
        }
        return body;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException error) {
            throw new IdentityException(400, "id 格式无效");
        }
    }

    private static String requireReviewNote(String note) {
        String trimmed = note == null ? "" : note.trim();
        if (trimmed.isEmpty()) {
            throw new IdentityException(400, "拒绝须填写原因");
        }
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new IdentityException(400, "原因过长（上限 " + MAX_NOTE_LENGTH + " 字符）");
        }
        return trimmed;
    }

    private static String trimNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.length() > MAX_NOTE_LENGTH ? trimmed.substring(0, MAX_NOTE_LENGTH) : trimmed;
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "error", error.getMessage()));
    }

    /** 提交认证申请：materials 自由 JSON 字符串（社交账号/作品链接等）。 */
    public record SubmitVerificationRequest(String materials) {}

    /** 审核请求体：note 可选（reject 必填）。 */
    public record ReviewRequest(String note) {}
}
