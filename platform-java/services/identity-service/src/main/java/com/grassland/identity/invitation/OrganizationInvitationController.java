package com.grassland.identity.invitation;

import com.grassland.identity.auth.IdentityException;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.store.StoreAuthorization;
import com.grassland.identity.store.StoreRole;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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

/**
 * 组织侧的成员邀请入口。挂 {@code /api/organizations/{orgId}/invitations}。
 *
 * <ul>
 * <li>POST — 按**邮箱**发出邀请（role∈admin/member），需 OWNER（与直接加成员同档）；同 org 同邮箱已有待接受邀请
 * → 409。</li>
 * <li>GET — 列本组织的全部邀请（含终态），需 MEMBER 及以上。</li>
 * <li>DELETE /{id} — 撤销待接受邀请，需 OWNER；已处理 → 409；不属本 org → 404。</li>
 * </ul>
 *
 * <p>
 * <b>为什么是邀请而不是「按邮箱查人」</b>：查人端点等价于账号枚举探针——任何能建组织的人输入邮箱
 * 即可判定该邮箱是否注册。邀请流下，本端点对「该邮箱是否有账号」<b>一律不作答</b>：无论对方是否存在都返回 201，
 * 是否真的成为成员取决于对方登录后自己接受（见 {@link MyInvitationController}）。
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/invitations")
public class OrganizationInvitationController {

	private final OrgAuthorization authz;
	private final StoreAuthorization storeAuthz;
	private final InvitationRepository invitations;
	private final OutboxRepository outbox;
	private final Duration ttl;
	private final TransactionalOperator transactions;

	public OrganizationInvitationController(OrgAuthorization authz, StoreAuthorization storeAuthz,
			InvitationRepository invitations, OutboxRepository outbox,
			@Value("${identity.invitation.ttl-hours:168}") long ttlHours, TransactionalOperator transactions) {
		this.authz = authz;
		this.storeAuthz = storeAuthz;
		this.invitations = invitations;
		this.outbox = outbox;
		this.ttl = Duration.ofHours(ttlHours > 0 ? ttlHours : 168);
		this.transactions = transactions;
	}

	/**
	 * 邀请门禁：组织级 → org OWNER（与直接加成员同档）；门店级 staff → 门店 MANAGER+， 门店级 manager → org
	 * ADMIN+（镜像 StoreMembershipController.add 的分档；跨 org 门店 404）。
	 */
	private reactor.core.publisher.Mono<com.grassland.identity.user.AuthUser> invitationGate(String orgId,
			String storeId, String role, ServerHttpRequest request) {
		if (storeId == null) {
			return authz.requireRole(request, orgId, MembershipRole.OWNER);
		}
		return StoreRole.MANAGER.dbValue().equals(role)
				? authz.requireRole(request, orgId, MembershipRole.ADMIN)
						.flatMap(admin -> storeAuthz.ensureStoreInOrg(orgId, storeId).thenReturn(admin))
				: storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.MANAGER);
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> invite(@PathVariable String orgId,
			@RequestBody CreateInvitationRequest body, ServerHttpRequest request) {
		return invitationGate(orgId, body.storeId(), body.role(), request)
				.flatMap(inviter -> invitations.findPending(orgId, body.email())
						.flatMap(existing -> Mono.<Invitation>error(new IdentityException(409, "该邮箱已有待接受的邀请")))
						.switchIfEmpty(Mono.defer(() -> transactions.transactional(invitations
								.create(orgId, body.storeId(), body.email(), body.role(), inviter.id(), ttl)
								.flatMap(invitation -> outbox.append(new EventEnvelope(UUID.randomUUID().toString(),
										"MembershipInvited", "OrganizationInvitation", invitation.id(), 1,
										Instant.now(), null, invitedPayload(invitation, inviter.id())))
										.thenReturn(invitation)))))
						// GL-P1-NOTIFY-001：邀请邮件改由 MembershipInvited 事件 → NotificationEventProcessor
						// → mail_outbox 异步可靠发送（见 notify/mail）。此处入队即 201，不再同步直发。
						.map(invitation -> ResponseEntity.status(201)
								.body(Map.of("success", true, "data", toBody(invitation)))))
				.onErrorResume(DataIntegrityViolationException.class, e -> Mono
						.just(ResponseEntity.status(409).body(Map.of("success", false, "error", "该邮箱已有待接受的邀请"))));
	}

	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId, ServerHttpRequest request) {
		return authz.requireRole(request, orgId, MembershipRole.MEMBER)
				.flatMap(account -> invitations.findByOrganization(orgId).collectList().map(list -> ResponseEntity
						.ok(Map.of("success", true, "data", list.stream().map(this::toBody).toList()))));
	}

	@DeleteMapping("/{invitationId}")
	public Mono<ResponseEntity<Map<String, Object>>> revoke(@PathVariable String orgId,
			@PathVariable String invitationId, ServerHttpRequest request) {
		// 先按 org 定位邀请（不存在/跨 org 一律 404），再按范围分档：组织级 → org OWNER；
		// 门店级 → 该门店 MANAGER（店长自管本店邀请）。纯门店经理没有组织成员行，
		// 不能先用 requireRole(MEMBER) 当第一道闸——那会把店长挡在自己店的门外。
		return invitations.findById(invitationId).filter(invitation -> orgId.equals(invitation.organizationId()))
				.switchIfEmpty(Mono.error(new IdentityException(404, "邀请不存在")))
				.flatMap(invitation -> invitation.storeId() == null
						? requireOwner(orgId, request).then(doRevoke(orgId, invitation))
						: storeAuthz.requireStoreRole(request, orgId, invitation.storeId(), StoreRole.MANAGER)
								.then(doRevoke(orgId, invitation)));
	}

	private Mono<Void> requireOwner(String orgId, ServerHttpRequest request) {
		return authz.requireRole(request, orgId, MembershipRole.OWNER).then();
	}

	/** 邀请事件 payload：门店级带 storeId（站内通知/邮件渲染可用）。 */
	private static Map<String, Object> invitedPayload(Invitation invitation, String inviterId) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("organizationId", invitation.organizationId());
		if (invitation.storeId() != null) {
			payload.put("storeId", invitation.storeId());
		}
		payload.put("email", invitation.email());
		payload.put("role", invitation.role());
		payload.put("invitedBy", inviterId);
		return payload;
	}

	private Mono<ResponseEntity<Map<String, Object>>> doRevoke(String orgId, Invitation invitation) {
		return transactions.transactional(
				invitations.transitionFromPending(invitation.id(), InvitationStatus.REVOKED).flatMap(rows -> {
					if (rows == 0) {
						return Mono.error(new IdentityException(409, "邀请已处理，无法撤销"));
					}
					return outbox
							.append(new EventEnvelope(UUID.randomUUID().toString(), "MembershipInvitationRevoked",
									"OrganizationInvitation", invitation.id(), 2, Instant.now(), null,
									Map.of("organizationId", orgId, "email", invitation.email())))
							.thenReturn(ResponseEntity.ok(Map.<String, Object>of("success", true)));
				}));
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

	private Map<String, Object> toBody(Invitation invitation) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", invitation.id());
		map.put("organizationId", invitation.organizationId());
		if (invitation.storeId() != null) {
			map.put("storeId", invitation.storeId());
		}
		map.put("email", invitation.email());
		map.put("role", invitation.role());
		map.put("status", invitation.status());
		map.put("expiresAt", invitation.expiresAt() == null ? null : invitation.expiresAt().toString());
		map.put("createdAt", invitation.createdAt() == null ? null : invitation.createdAt().toString());
		map.put("expired", invitation.isPending() && invitation.isExpired(Instant.now()));
		return map;
	}
}
