package com.grassland.identity.membership;

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
 * 组织成员 HTTP 入口。草场身份域 Slice 2F。挂 {@code /api/organizations/{orgId}/memberships}。
 *
 * <ul>
 * <li>GET — 列成员，需 MEMBER 及以上。</li>
 * </ul>
 *
 * 任务书 #49：POST（挂既有账号为成员）与 DELETE（解除关系）已随挂靠通路下线——成员经主体
 * 直建子账号产生，移除走 {@code DELETE /accounts/{accountId}}（关系解除 + 账号永久作废）。
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/memberships")
public class MembershipController {

	private final OrgAuthorization authz;
	private final MembershipRepository memberships;

	public MembershipController(OrgAuthorization authz, MembershipRepository memberships) {
		this.authz = authz;
		this.memberships = memberships;
	}

	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId, ServerHttpRequest request) {
		return authz.requireRole(request, orgId, MembershipRole.MEMBER)
				.flatMap(account -> memberships.findByOrganizationWithAccountStatus(orgId).collectList().map(list -> ResponseEntity
						.ok(Map.of("success", true, "data", list.stream().map(this::toBody).toList()))));
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
		// 账号名（前缀-登录名）：行展示与删除强确认都要用，仓储已联 account_username
		map.put("username", m.username());
		// #52 池模型：该成员当前挂靠的门店（至多一店）；全 null = 未分配
		map.put("storeId", m.storeId());
		map.put("storeRole", m.storeRole());
		map.put("storeName", m.storeName());
		map.put("createdAt", m.createdAt() == null ? null : m.createdAt().toString());
		return map;
	}
}
