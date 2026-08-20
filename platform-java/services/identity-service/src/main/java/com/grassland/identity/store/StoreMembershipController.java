package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
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
 * 门店粒度成员 HTTP 入口。草场身份域 Slice 2G + Slice 2J（门店 MANAGER 级独立授权）。 挂
 * {@code /api/organizations/{orgId}/stores/{storeId}/memberships}。
 *
 * <ul>
 * <li>GET — 列门店成员，需门店 STAFF+（org OWNER/ADMIN 隐式满足）。</li>
 * <li>POST — 加成员：加 staff 需门店 MANAGER+；任命 manager 仅 org
 * ADMIN+；UNIQUE(store,account) 冲突 → 409；写 outbox
 * {@code StoreMembershipGranted}。</li>
 * <li>DELETE /{accountId} — 移除成员，需门店 MANAGER+；守卫最后一个 manager（不可移除）→ 409。</li>
 * </ul>
 *
 * <p>
 * authz 经 {@link StoreAuthorization}（门店粒度，org OWNER/ADMIN 隐式超管）；跨 org storeId →
 * 404。 门店经理可独立管理本店 staff，无需 org ADMIN（HLD 5.2 store-membership 独立于
 * merchant-organization）。
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/stores/{storeId}/memberships")
public class StoreMembershipController {

	private final StoreAuthorization storeAuthz;
	private final OrgAuthorization orgAuthz;
	private final StoreMembershipRepository storeMemberships;
	private final OutboxRepository outbox;
	private final TransactionalOperator transactions;

	public StoreMembershipController(StoreAuthorization storeAuthz, OrgAuthorization orgAuthz,
			StoreMembershipRepository storeMemberships, OutboxRepository outbox, TransactionalOperator transactions) {
		this.storeAuthz = storeAuthz;
		this.orgAuthz = orgAuthz;
		this.storeMemberships = storeMemberships;
		this.outbox = outbox;
		this.transactions = transactions;
	}

	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId, @PathVariable String storeId,
			ServerHttpRequest request) {
		return storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.STAFF)
				.flatMap(account -> storeMemberships.findByStore(storeId).collectList().map(list -> ResponseEntity
						.ok(Map.of("success", true, "data", list.stream().map(this::toBody).toList()))));
	}

	@PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> add(@PathVariable String orgId, @PathVariable String storeId,
			@RequestBody CreateStoreMembershipRequest body, ServerHttpRequest request) {
		StoreRole targetRole = StoreRole.fromDb(body.role());
		// 任命门店 manager 仅 org ADMIN+；管理 staff 需门店 MANAGER+（含 org 超管）。两者均经
		// ensureStoreInOrg（跨 org → 404）。
		var gate = (targetRole == StoreRole.MANAGER)
				? orgAuthz.requireRole(request, orgId, MembershipRole.ADMIN)
						.flatMap(admin -> storeAuthz.ensureStoreInOrg(orgId, storeId).thenReturn(admin))
				: storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.MANAGER);
		return gate
				.flatMap(
						account -> transactions
								.transactional(
										storeMemberships.create(storeId, body.accountId(), targetRole.dbValue())
												.flatMap(
														m -> outbox
																.append(new EventEnvelope(UUID.randomUUID().toString(),
																		"StoreMembershipGranted", "StoreMembership",
																		m.id(), 1, Instant.now(), null,
																		Map.of("storeId", storeId, "accountId",
																				m.accountId(), "role", m.role())))
																.thenReturn(m)))
								.map(m -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(m)))))
				.onErrorResume(DataIntegrityViolationException.class, e -> Mono
						.just(ResponseEntity.status(409).body(Map.of("success", false, "error", "该账号已是门店成员"))));
	}

	@DeleteMapping("/{accountId}")
	public Mono<ResponseEntity<Map<String, Object>>> remove(@PathVariable String orgId, @PathVariable String storeId,
			@PathVariable String accountId, ServerHttpRequest request) {
		return storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.MANAGER)
				.flatMap(account -> guardLastManager(storeId, accountId)
						.then(storeMemberships.deleteByStoreAndAccount(storeId, accountId))
						.map(deleted -> deleted > 0
								? ResponseEntity.ok(Map.<String, Object>of("success", true))
								: ResponseEntity.status(404)
										.body(Map.<String, Object>of("success", false, "error", "门店成员不存在"))));
	}

	/** 阻止移除最后一个 manager，保证门店始终有 manager；非 manager 成员直接放行。 */
	private Mono<Void> guardLastManager(String storeId, String accountId) {
		return storeMemberships.findRole(storeId, accountId).flatMap(role -> {
			if (!StoreRole.MANAGER.dbValue().equalsIgnoreCase(role)) {
				return Mono.<Void>empty();
			}
			return storeMemberships.countByStoreAndRole(storeId, StoreRole.MANAGER.dbValue())
					.flatMap(count -> count <= 1
							? Mono.<Void>error(new IdentityException(409, "不能移除最后一个门店经理"))
							: Mono.<Void>empty());
		}).switchIfEmpty(Mono.empty());
	}

	@ExceptionHandler(IdentityException.class)
	public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
		return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
	}

	private Map<String, Object> toBody(StoreMembership m) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", m.id());
		map.put("storeId", m.storeId());
		map.put("accountId", m.accountId());
		map.put("role", m.role());
		map.put("createdAt", m.createdAt() == null ? null : m.createdAt().toString());
		return map;
	}
}
