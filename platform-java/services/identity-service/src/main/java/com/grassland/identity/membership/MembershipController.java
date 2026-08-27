package com.grassland.identity.membership;

import com.grassland.identity.auth.IdentityException;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
 * 组织成员 HTTP 入口。草场身份域 Slice 2F。挂 {@code /api/organizations/{orgId}/memberships}。
 *
 * <ul>
 * <li>POST — 新增成员（role∈admin/member），需 OWNER；UNIQUE(org,account) 冲突 → 409；写
 * outbox {@code MembershipGranted}。</li>
 * <li>GET — 列成员，需 MEMBER 及以上。</li>
 * <li>DELETE /{accountId} — 移除成员，需 OWNER；守卫最后一个 owner（不可移除）→ 409。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/memberships")
public class MembershipController {

	private final OrgAuthorization authz;
	private final MembershipRepository memberships;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;

	public MembershipController(OrgAuthorization authz, MembershipRepository memberships, OutboxRepository outbox,
			TransactionalOperator transactions) {
		this.authz = authz;
		this.memberships = memberships;
		this.outbox = outbox;
		this.transactions = transactions;
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> add(@PathVariable String orgId,
			@RequestBody CreateMembershipRequest body, ServerHttpRequest request) {
		return authz
				.requireRole(request, orgId,
						MembershipRole.OWNER)
				.flatMap(
						owner -> transactions
								.transactional(
										memberships.create(orgId, body.accountId(), body.role())
												.flatMap(m -> outbox
														.append(new EventEnvelope(UUID.randomUUID().toString(),
																"MembershipGranted", "Membership", m.id(), 1,
																Instant.now(), null,
																Map.of("organizationId", orgId, "accountId",
																		m.accountId(), "role", m.role())))
														.thenReturn(m)))
								.map(m -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(m)))))
				.onErrorResume(DataIntegrityViolationException.class, e -> Mono
						.just(ResponseEntity.status(409).body(Map.of("success", false, "error", "该账号已是组织成员"))));
	}

	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId, ServerHttpRequest request) {
		return authz.requireRole(request, orgId, MembershipRole.MEMBER)
				.flatMap(account -> memberships.findByOrganizationWithAccountStatus(orgId).collectList().map(list -> ResponseEntity
						.ok(Map.of("success", true, "data", list.stream().map(this::toBody).toList()))));
	}

	@DeleteMapping("/{accountId}")
	public Mono<ResponseEntity<Map<String, Object>>> remove(@PathVariable String orgId, @PathVariable String accountId,
			ServerHttpRequest request) {
		return authz.requireRole(request, orgId, MembershipRole.OWNER).flatMap(owner -> guardLastOwner(orgId, accountId)
				.then(memberships.deleteByOrganizationAndAccount(orgId, accountId))
				.map(deleted -> deleted > 0
						? ResponseEntity.ok(Map.<String, Object>of("success", true))
						: ResponseEntity.status(404).body(Map.<String, Object>of("success", false, "error", "成员不存在"))));
	}

	/** 阻止移除最后一个 owner，保证 org 始终有 owner；非 owner 成员直接放行。 */
	private Mono<Void> guardLastOwner(String orgId, String accountId) {
		return memberships.findRole(orgId, accountId).flatMap(role -> {
			if (!MembershipRole.OWNER.dbValue().equalsIgnoreCase(role)) {
				return Mono.<Void>empty();
			}
			return memberships.countByOrganizationAndRole(orgId, MembershipRole.OWNER.dbValue())
					.flatMap(count -> count <= 1
							? Mono.<Void>error(new IdentityException(409, "不能移除最后一个 owner"))
							: Mono.<Void>empty());
		}).switchIfEmpty(Mono.empty());
	}

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
		return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
	}

	private Map<String, Object> toBody(Membership m) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", m.id());
		map.put("organizationId", m.organizationId());
		map.put("accountId", m.accountId());
		map.put("role", m.role());
		// 任务书 #48：账号状态（additive，旧客户端可忽略）；null=账号行不存在
		map.put("accountStatus", m.accountStatus());
		map.put("createdAt", m.createdAt() == null ? null : m.createdAt().toString());
		return map;
	}
}
