package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 收款账户 HTTP 入口。GL-P3-MERCHANT-001。
 *
 * <ul>
 *   <li>POST — 添加账户，需 ADMIN 及以上角色。</li>
 *   <li>GET — 列出账户，需 MEMBER 及以上。</li>
 *   <li>PUT /{id} — 更新账户（仅 pending 状态），需 ADMIN 及以上。</li>
 *   <li>DELETE /{id} — 删除账户（仅 pending 状态），需 ADMIN 及以上。</li>
 *   <li>POST /{id}/set-default — 设置默认账户，需 ADMIN 及以上。</li>
 *   <li>POST /{id}/submit — 提交审核（pending → under_review），需 ADMIN 及以上。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/withdrawal-accounts")
public class WithdrawalAccountController {

    private final OrgAuthorization authz;
    private final WithdrawalAccountRepository accounts;
    private final KybSubmissionService submissions;
    private final KybFieldCrypto crypto;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public WithdrawalAccountController(
            OrgAuthorization authz,
            WithdrawalAccountRepository accounts,
            KybSubmissionService submissions,
            KybFieldCrypto crypto,
            OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.authz = authz;
        this.accounts = accounts;
        this.submissions = submissions;
        this.crypto = crypto;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> create(@PathVariable String orgId,
                                                              @RequestBody CreateAccountRequest body,
                                                              ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> Mono.fromCallable(() -> encryptAccountNumber(body))
                        .flatMap(cipher -> transactions.transactional(
                                accounts.create(orgId, body.accountType(), body.accountName(),
                                        cipher, body.bankName(), body.branchName()))))
                .map(acc -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(acc))));
    }

    /**
     * 加密收款账号。账号是必填的强敏感字段，故 KEK 未配置时整个端点 503——
     * 不存明文（列名承诺加密），也不假装可用（与 intelligence BYOK fail-closed 同口径）。
     */
    private String encryptAccountNumber(CreateAccountRequest body) {
        if (body.accountNumber() == null || body.accountNumber().isBlank()) {
            throw new IdentityException(400, "收款账号不能为空");
        }
        return crypto.encrypt(body.accountNumber());
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId, ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                .flatMap(account -> accounts.findByOrganization(orgId).collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream().map(this::toBody).toList()))));
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> update(@PathVariable String orgId,
                                                              @PathVariable String id,
                                                              @RequestBody CreateAccountRequest body,
                                                              ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> Mono.fromCallable(() -> new Object[]{
                                KybSubmissionService.parseUuid(id, "账户 ID"), encryptAccountNumber(body)})
                        .flatMap(prepared -> transactions.transactional(
                                accounts.update((UUID) prepared[0], orgId, body.accountType(), body.accountName(),
                                        (String) prepared[1], body.bankName(), body.branchName()))))
                .switchIfEmpty(Mono.error(new IdentityException(409, "账户不存在或当前状态不可编辑")))
                .map(acc -> ResponseEntity.ok(Map.of("success", true, "data", toBody(acc))));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable String orgId,
                                                           @PathVariable String id,
                                                           ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> transactions.transactional(
                        accounts.deleteByIdAndOrganization(
                                        KybSubmissionService.parseUuid(id, "账户 ID"), orgId)
                                .flatMap(deleted -> deleted > 0
                                        ? Mono.just(ResponseEntity.ok(Map.of("success", true,
                                                "data", Map.of("deleted", true))))
                                        : Mono.error(new IdentityException(404, "账户不存在或不可删除")))));
    }

    @PostMapping("/{id}/set-default")
    public Mono<ResponseEntity<Map<String, Object>>> setDefault(@PathVariable String orgId,
                                                                  @PathVariable String id,
                                                                  ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> {
                    UUID accountId = KybSubmissionService.parseUuid(id, "账户 ID");
                    // 先按 org 确认归属，再 setDefault——否则可把他人账户置为本 org 默认。
                    return accounts.findByIdAndOrganization(accountId, orgId)
                            .switchIfEmpty(Mono.error(new IdentityException(404, "账户不存在")))
                            .flatMap(existing -> transactions.transactional(
                                    accounts.setDefault(accountId, orgId)));
                })
                .map(acc -> ResponseEntity.ok(Map.of("success", true, "data", toBody(acc))));
    }

    @PostMapping("/{id}/submit")
    public Mono<ResponseEntity<Map<String, Object>>> submit(@PathVariable String orgId,
                                                            @PathVariable String id,
                                                            ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> accounts.findByIdAndOrganization(
                                KybSubmissionService.parseUuid(id, "账户 ID"), orgId)
                        .switchIfEmpty(Mono.error(new IdentityException(404, "账户不存在")))
                        .flatMap(acc -> {
                            WithdrawalAccountStatus status = WithdrawalAccountStatus.fromDb(acc.status());
                            if (status.isUnderReview()) {
                                return Mono.<WithdrawalAccount>error(new IdentityException(409, "账户已在审核中"));
                            }
                            if (!status.canSubmit()) {
                                return Mono.<WithdrawalAccount>error(new IdentityException(409, "账户已通过审核，无需重复提交"));
                            }
                            return Mono.just(acc);
                        })
                        // 同事务：状态变更 + 审核入队 + outbox。此前只改状态发事件，审核行从不创建。
                        .flatMap(acc -> transactions.transactional(
                                accounts.updateStatus(acc.id(), WithdrawalAccountStatus.UNDER_REVIEW.dbValue(),
                                                Instant.now(), null, null, null)
                                        .flatMap(updated -> submissions.enqueue(
                                                        KybVerificationType.WITHDRAWAL_ACCOUNT, orgId,
                                                        updated.id(), account.id(), List.of())
                                                .flatMap(req -> outbox.append(submittedEvent(orgId, updated, req))
                                                        .thenReturn(updated))))))
                .map(acc -> ResponseEntity.ok(Map.of("success", true, "data", toBody(acc))));
    }

    /** 提交事件 payload。不带 accountName（可含真实姓名，D-10 最小化 PII）。 */
    private EventEnvelope submittedEvent(String orgId, WithdrawalAccount account, KybVerificationRequest req) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", account.id().toString());
        payload.put("organizationId", orgId);
        payload.put("accountType", account.accountType());
        payload.put("requestId", req.id().toString());
        return new EventEnvelope(UUID.randomUUID().toString(), "WithdrawalAccountSubmitted", "WithdrawalAccount",
                account.id().toString(), 1, Instant.now(), null, payload);
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private Map<String, Object> toBody(WithdrawalAccount acc) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", acc.id());
        m.put("organizationId", acc.organizationId());
        m.put("accountType", acc.accountType());
        m.put("accountName", acc.accountName());
        // 收款账号只回末 4 位掩码，完整明文永不出响应体（D-10）。
        m.put("accountNumberMasked", crypto.maskTail4(acc.accountNumberEncrypted()));
        m.put("bankName", acc.bankName());
        m.put("branchName", acc.branchName());
        m.put("isDefault", acc.isDefault());
        m.put("status", acc.status());
        m.put("submittedAt", acc.submittedAt() == null ? null : acc.submittedAt().toString());
        m.put("reviewedAt", acc.reviewedAt() == null ? null : acc.reviewedAt().toString());
        m.put("createdAt", acc.createdAt() == null ? null : acc.createdAt().toString());
        return m;
    }

    /**
     * 创建/更新请求体。
     *
     * <p>字段名从 {@code accountNumberEncrypted} 改为 {@code accountNumber}：请求体里收的是**明文**，
     * 加密在服务端做（{@link KybFieldCrypto}）。原字段名暗示由客户端加密，
     * 实际却把明文直接存进 {@code account_number_encrypted} 列——名不副实且落库是明文。
     */
    public record CreateAccountRequest(
            String accountType,
            String accountName,
            String accountNumber,
            String bankName,
            String branchName
    ) {}
}
