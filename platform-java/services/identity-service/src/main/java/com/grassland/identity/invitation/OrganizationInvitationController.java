package com.grassland.identity.invitation;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.event.EventEnvelope;
import com.grassland.identity.event.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.notify.SmtpMailSender;
import com.grassland.identity.organization.OrganizationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 组织侧的成员邀请入口。挂 {@code /api/organizations/{orgId}/invitations}。
 *
 * <ul>
 *   <li>POST — 按**邮箱**发出邀请（role∈admin/member），需 OWNER（与直接加成员同档）；同 org 同邮箱已有待接受邀请 → 409。</li>
 *   <li>GET — 列本组织的全部邀请（含终态），需 MEMBER 及以上。</li>
 *   <li>DELETE /{id} — 撤销待接受邀请，需 OWNER；已处理 → 409；不属本 org → 404。</li>
 * </ul>
 *
 * <p><b>为什么是邀请而不是「按邮箱查人」</b>：查人端点等价于账号枚举探针——任何能建组织的人输入邮箱
 * 即可判定该邮箱是否注册。邀请流下，本端点对「该邮箱是否有账号」<b>一律不作答</b>：无论对方是否存在都返回 201，
 * 是否真的成为成员取决于对方登录后自己接受（见 {@link MyInvitationController}）。
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/invitations")
public class OrganizationInvitationController {

    private static final Logger log = LoggerFactory.getLogger(OrganizationInvitationController.class);

    private final OrgAuthorization authz;
    private final InvitationRepository invitations;
    private final OrganizationRepository organizations;
    private final OutboxRepository outbox;
    private final SmtpMailSender mailSender;
    private final Duration ttl;
    private final TransactionalOperator transactions;

    public OrganizationInvitationController(OrgAuthorization authz, InvitationRepository invitations,
                                            OrganizationRepository organizations, OutboxRepository outbox,
                                            SmtpMailSender mailSender,
                                            @Value("${identity.invitation.ttl-hours:168}") long ttlHours,
                                            TransactionalOperator transactions) {
        this.authz = authz;
        this.invitations = invitations;
        this.organizations = organizations;
        this.outbox = outbox;
        this.mailSender = mailSender;
        this.ttl = Duration.ofHours(ttlHours > 0 ? ttlHours : 168);
        this.transactions = transactions;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> invite(@PathVariable String orgId,
                                                            @RequestBody CreateInvitationRequest body,
                                                            ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.OWNER)
                .flatMap(owner -> invitations.findPending(orgId, body.email())
                        .flatMap(existing -> Mono.<Invitation>error(
                                new IdentityException(409, "该邮箱已有待接受的邀请")))
                        .switchIfEmpty(Mono.defer(() ->
                                transactions.transactional(
                                        invitations.create(orgId, body.email(), body.role(), owner.id(), ttl)
                                                .flatMap(invitation -> outbox.append(new EventEnvelope(
                                                        UUID.randomUUID().toString(), "MembershipInvited", "OrganizationInvitation",
                                                        invitation.id(), 1, Instant.now(), null,
                                                        Map.of("organizationId", orgId, "email", invitation.email(),
                                                                "role", invitation.role(), "invitedBy", owner.id())))
                                                        .thenReturn(invitation)))))
                        .flatMap(invitation -> notifyInvitee(orgId, invitation)
                                .map(sent -> ResponseEntity.status(201).body(Map.of(
                                        "success", true, "data", toBody(invitation, sent))))))
                .onErrorResume(DataIntegrityViolationException.class, e ->
                        Mono.just(ResponseEntity.status(409)
                                .body(Map.of("success", false, "error", "该邮箱已有待接受的邀请"))));
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId, ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.MEMBER)
                .flatMap(account -> invitations.findByOrganization(orgId).collectList()
                        .map(list -> ResponseEntity.ok(Map.of("success", true,
                                "data", list.stream().map(i -> toBody(i, null)).toList()))));
    }

    @DeleteMapping("/{invitationId}")
    public Mono<ResponseEntity<Map<String, Object>>> revoke(@PathVariable String orgId,
                                                            @PathVariable String invitationId,
                                                            ServerHttpRequest request) {
        return authz.requireRole(request, orgId, MembershipRole.OWNER)
                .flatMap(owner -> invitations.findById(invitationId)
                        .filter(invitation -> orgId.equals(invitation.organizationId()))
                        .switchIfEmpty(Mono.error(new IdentityException(404, "邀请不存在")))
                        .flatMap(invitation -> doRevoke(orgId, invitation)));
    }

    private Mono<ResponseEntity<Map<String, Object>>> doRevoke(String orgId, Invitation invitation) {
        return transactions.transactional(
                invitations.transitionFromPending(invitation.id(), InvitationStatus.REVOKED)
                        .flatMap(rows -> {
                            if (rows == 0) {
                                return Mono.error(new IdentityException(409, "邀请已处理，无法撤销"));
                            }
                            return outbox.append(new EventEnvelope(
                                            UUID.randomUUID().toString(), "MembershipInvitationRevoked",
                                            "OrganizationInvitation", invitation.id(), 2, Instant.now(), null,
                                            Map.of("organizationId", orgId, "email", invitation.email())))
                                    .thenReturn(ResponseEntity.ok(Map.<String, Object>of("success", true)));
                        }));
    }

    /**
     * 尽力而为地把邀请通知发到被邀请邮箱；返回是否真的发出（前端据此提示邀请人是否需另行告知对方）。
     * 未配置 SMTP（本地/dev 常态）或发送失败都不影响邀请本身已落库。
     */
    private Mono<Boolean> notifyInvitee(String orgId, Invitation invitation) {
        if (!mailSender.isConfigured()) {
            return Mono.just(false);
        }
        return organizations.findById(orgId)
                .map(org -> org.name())
                .defaultIfEmpty("草场组织")
                .flatMap(orgName -> Mono.fromCallable(() -> {
                            mailSender.sendOrganizationInvitation(invitation.email(), orgName, invitation.role());
                            return true;
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .onErrorResume(e -> {
                    log.warn("invitation mail send failed for org {}: {}", orgId, e.toString());
                    return Mono.just(false);
                });
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }

    /** 非法邮箱/角色由 {@link CreateInvitationRequest} 的 compact constructor 抛出 → 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException error) {
        return ResponseEntity.status(400).body(Map.of("success", false, "error", "邮箱或角色不合法"));
    }

    /** emailSent 仅在创建时有意义（列表读不出「当时是否发信」），故列表传 null 时省略该字段。 */
    private Map<String, Object> toBody(Invitation invitation, Boolean emailSent) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", invitation.id());
        map.put("organizationId", invitation.organizationId());
        map.put("email", invitation.email());
        map.put("role", invitation.role());
        map.put("status", invitation.status());
        map.put("expiresAt", invitation.expiresAt() == null ? null : invitation.expiresAt().toString());
        map.put("createdAt", invitation.createdAt() == null ? null : invitation.createdAt().toString());
        map.put("expired", invitation.isPending() && invitation.isExpired(Instant.now()));
        if (emailSent != null) {
            map.put("emailSent", emailSent);
        }
        return map;
    }
}
