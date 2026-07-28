package com.grassland.identity.invitation;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.membership.MembershipRepository;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.user.AuthUser;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 被邀请人侧的邀请入口。挂 {@code /api/me/invitations}。
 *
 * <ul>
 *   <li>GET — 列发给**我这个邮箱**的、未过期的待接受邀请（带组织名）。</li>
 *   <li>POST /{id}/accept — 接受：落成员关系（角色即邀请角色）。</li>
 *   <li>POST /{id}/decline — 谢绝。</li>
 * </ul>
 *
 * <p><b>邀请与账号的绑定靠邮箱</b>：邀请只写邮箱（发起时不查、也不回答该邮箱是否注册），
 * 接受时才校验「当前登录账号的邮箱 == 邀请邮箱」。因此邀请 id 即便泄露也无法被他人冒领；
 * 且邮箱不匹配一律返回 <b>404 而非 403</b>——403 会变相确认「这个 id 是个真邀请」，成为探测口。
 *
 * <p><b>顺序</b>：先做 guarded UPDATE（pending→accepted，并发只有一个赢家），再建成员关系。
 * 对方已经是成员时唯一约束冲突，视为幂等成功（邀请照样消费掉），不回滚。
 */
@RestController
@RequestMapping("/api/me/invitations")
public class MyInvitationController {

    private final CurrentAccountResolver accounts;
    private final InvitationRepository invitations;
    private final MembershipRepository memberships;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public MyInvitationController(CurrentAccountResolver accounts, InvitationRepository invitations,
                                  MembershipRepository memberships, OutboxRepository outbox,
                                  TransactionalOperator transactions) {
        this.accounts = accounts;
        this.invitations = invitations;
        this.memberships = memberships;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> invitations.findPendingForEmail(normalize(account.email())).collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream().map(MyInvitationController::toBody).toList()))));
    }

    @PostMapping("/{invitationId}/accept")
    public Mono<ResponseEntity<Map<String, Object>>> accept(@PathVariable String invitationId,
                                                            ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> requireMine(invitationId, account)
                        // 不套事务：joinOrganization 靠捕获 memberships.create 的 DataIntegrityViolation
                        // 做「已是成员→幂等」语义；被捕获的 INSERT 失败会把 R2DBC 事务置为 rollback-only，
                        // 致后续 outbox 写失败。需改 joinOrganization 为 ON CONFLICT 预判才能事务化（留 7C-2）。
                        .flatMap(invitation -> invitations.accept(invitationId, account.id())
                                .flatMap(rows -> rows == 0
                                        ? Mono.<ResponseEntity<Map<String, Object>>>error(
                                                new IdentityException(409, "邀请已处理"))
                                        : joinOrganization(invitation, account))));
    }

    @PostMapping("/{invitationId}/decline")
    public Mono<ResponseEntity<Map<String, Object>>> decline(@PathVariable String invitationId,
                                                             ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(account -> requireMine(invitationId, account)
                        .flatMap(invitation -> transactions.transactional(
                                invitations
                                        .transitionFromPending(invitationId, InvitationStatus.DECLINED)
                                        .flatMap(rows -> rows == 0
                                                ? Mono.<ResponseEntity<Map<String, Object>>>error(
                                                        new IdentityException(409, "邀请已处理"))
                                                : outbox.append(event("MembershipInvitationDeclined", invitation,
                                                                Map.of("organizationId", invitation.organizationId(),
                                                                        "accountId", account.id())))
                                                        .thenReturn(ResponseEntity.ok(
                                                                Map.<String, Object>of("success", true)))))));
    }

    /**
     * 取出确属当前账号的待接受邀请。不存在 / 邮箱不匹配 → 一律 404（不区分，避免成为「这个 id 是真邀请吗」的探测口）；
     * 已过期 → 410（对本人可以如实说明，便于 UI 提示「请让对方重新邀请」）。
     */
    private Mono<Invitation> requireMine(String invitationId, AuthUser account) {
        return invitations.findById(invitationId)
                .filter(invitation -> normalize(account.email()).equals(normalize(invitation.email())))
                .switchIfEmpty(Mono.error(new IdentityException(404, "邀请不存在")))
                .flatMap(invitation -> {
                    if (!invitation.isPending()) {
                        return Mono.error(new IdentityException(409, "邀请已处理"));
                    }
                    if (invitation.isExpired(Instant.now())) {
                        return Mono.error(new IdentityException(410, "邀请已过期，请让对方重新邀请"));
                    }
                    return Mono.just(invitation);
                });
    }

    /** 落成员关系 + 事件。已是成员（唯一约束冲突）视为幂等成功，用 alreadyMember 如实告知前端。 */
    private Mono<ResponseEntity<Map<String, Object>>> joinOrganization(Invitation invitation, AuthUser account) {
        Map<String, Object> payload = Map.of(
                "organizationId", invitation.organizationId(),
                "accountId", account.id(),
                "role", invitation.role());
        return memberships.create(invitation.organizationId(), account.id(), invitation.role())
                .flatMap(membership -> outbox.append(new EventEnvelope(
                        UUID.randomUUID().toString(), "MembershipGranted", "Membership",
                        membership.id(), 1, Instant.now(), null, payload))
                        .thenReturn(false))
                .onErrorResume(DataIntegrityViolationException.class, e -> Mono.just(true))
                .flatMap(alreadyMember -> outbox
                        .append(event("MembershipInvitationAccepted", invitation, payload))
                        .thenReturn(ResponseEntity.ok(Map.<String, Object>of("success", true, "data", Map.of(
                                "organizationId", invitation.organizationId(),
                                "role", invitation.role(),
                                "alreadyMember", alreadyMember)))));
    }

    private static EventEnvelope event(String type, Invitation invitation, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID().toString(), type, "OrganizationInvitation",
                invitation.id(), 2, Instant.now(), null, payload);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    private static Map<String, Object> toBody(PendingInvitationView view) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", view.id());
        map.put("organizationId", view.organizationId());
        map.put("organizationName", view.organizationName());
        map.put("role", view.role());
        map.put("expiresAt", view.expiresAt() == null ? null : view.expiresAt().toString());
        map.put("createdAt", view.createdAt() == null ? null : view.createdAt().toString());
        return map;
    }
}
