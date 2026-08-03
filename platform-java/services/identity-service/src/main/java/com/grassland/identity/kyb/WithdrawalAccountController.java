package com.grassland.identity.kyb;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public WithdrawalAccountController(
            OrgAuthorization authz,
            WithdrawalAccountRepository accounts,
            OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.authz = authz;
        this.accounts = accounts;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> create(@PathVariable String orgId,
                                                              @RequestBody CreateAccountRequest body,
                                                              ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> transactions.transactional(
                        accounts.create(orgId, body.accountType(), body.accountName(),
                                body.accountNumberEncrypted(), body.bankName(), body.branchName()))
                        .map(acc -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(acc)))));
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
                .flatMap(account -> transactions.transactional(
                        accounts.update(UUID.fromString(id), body.accountType(), body.accountName(),
                                body.accountNumberEncrypted(), body.bankName(), body.branchName()))
                        .switchIfEmpty(Mono.error(new IdentityException(409, "当前状态不可编辑")))
                        .map(acc -> ResponseEntity.ok(Map.of("success", true, "data", toBody(acc)))));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable String orgId,
                                                           @PathVariable String id,
                                                           ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> transactions.transactional(
                        accounts.deleteById(UUID.fromString(id))
                                .flatMap(deleted -> deleted > 0
                                        ? Mono.just(ResponseEntity.ok(Map.of("success", true, "data", Map.of("deleted", true))))
                                        : Mono.error(new IdentityException(404, "账户不存在或不可删除")))))
                .onErrorResume(e -> e instanceof IdentityException
                        ? Mono.error(e)
                        : Mono.just(ResponseEntity.ok(Map.of("success", true, "data", Map.of("deleted", false)))));
    }

    @PostMapping("/{id}/set-default")
    public Mono<ResponseEntity<Map<String, Object>>> setDefault(@PathVariable String orgId,
                                                                  @PathVariable String id,
                                                                  ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> transactions.transactional(
                        accounts.setDefault(UUID.fromString(id), orgId))
                        .map(acc -> ResponseEntity.ok(Map.of("success", true, "data", toBody(acc)))));
    }

    @PostMapping("/{id}/submit")
    public Mono<ResponseEntity<Map<String, Object>>> submit(@PathVariable String orgId,
                                                            @PathVariable String id,
                                                            ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(account -> accounts.findById(UUID.fromString(id))
                        .switchIfEmpty(Mono.error(new IdentityException(404, "账户不存在"))))
                .filter(acc -> WithdrawalAccountStatus.fromDb(acc.status()).isEditable())
                .switchIfEmpty(Mono.error(new IdentityException(409, "当前状态不可提交审核")))
                .flatMap(acc -> transactions.transactional(
                        accounts.updateStatus(acc.id(), WithdrawalAccountStatus.UNDER_REVIEW.dbValue(),
                                Instant.now(), null, null, null)
                                .flatMap(updated -> outbox.append(new EventEnvelope(
                                        UUID.randomUUID().toString(), "WithdrawalAccountSubmitted", "WithdrawalAccount",
                                        updated.id().toString(), 1, Instant.now(), null,
                                        Map.of("id", updated.id().toString(), "organizationId", orgId,
                                                "accountType", updated.accountType(), "accountName", updated.accountName())))
                                        .thenReturn(updated))))
                .map(acc -> ResponseEntity.ok(Map.of("success", true, "data", toBody(acc))));
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
        m.put("bankName", acc.bankName());
        m.put("branchName", acc.branchName());
        m.put("isDefault", acc.isDefault());
        m.put("status", acc.status());
        m.put("submittedAt", acc.submittedAt() == null ? null : acc.submittedAt().toString());
        m.put("reviewedAt", acc.reviewedAt() == null ? null : acc.reviewedAt().toString());
        m.put("createdAt", acc.createdAt() == null ? null : acc.createdAt().toString());
        return m;
    }

    public record CreateAccountRequest(
            String accountType,
            String accountName,
            String accountNumberEncrypted,
            String bankName,
            String branchName
    ) {}
}
