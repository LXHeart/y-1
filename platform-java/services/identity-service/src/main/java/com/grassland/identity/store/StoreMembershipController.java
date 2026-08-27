package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 门店粒度成员 HTTP 入口。草场身份域 Slice 2G + Slice 2J（门店 MANAGER 级独立授权）。 挂
 * {@code /api/organizations/{orgId}/stores/{storeId}/memberships}。
 *
 * <ul>
 * <li>GET — 列门店成员，需门店 STAFF+（org OWNER/ADMIN 隐式满足）。</li>
 * </ul>
 *
 * 任务书 #49：POST（挂既有账号）与 DELETE（解除关系）已随挂靠通路下线——门店成员经
 * 店长直建子账号产生，移除走 {@code DELETE /accounts/{accountId}}（账号永久作废）。
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
	private final StoreMembershipRepository storeMemberships;

	public StoreMembershipController(StoreAuthorization storeAuthz,
			StoreMembershipRepository storeMemberships) {
		this.storeAuthz = storeAuthz;
		this.storeMemberships = storeMemberships;
	}

	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId, @PathVariable String storeId,
			ServerHttpRequest request) {
		return storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.STAFF)
				.flatMap(account -> storeMemberships.findByStoreWithAccountStatus(storeId).collectList().map(list -> ResponseEntity
						.ok(Map.of("success", true, "data", list.stream().map(this::toBody).toList()))));
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
		// 任务书 #48：账号状态（additive）；pending_review 行据此渲染审核入口
		map.put("accountStatus", m.accountStatus());
		map.put("createdAt", m.createdAt() == null ? null : m.createdAt().toString());
		return map;
	}
}
