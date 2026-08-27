package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 门店成员关系端点（任务书 #52 池模型）。
 *
 * <ul>
 * <li>GET /api/organizations/{orgId}/stores/{storeId}/memberships — 列表（带账号状态与账号名，
 * STAFF+ 可读：店长工作台要渲染本店成员）。</li>
 * <li>PUT .../memberships/{accountId} — 分配或调度池内成员到本店（assign-or-move，同店=改角色）。
 * 仅主体 ADMIN+（#52 决策 C）；manager 过一店一店长闸（决策 B）。</li>
 * <li>DELETE .../memberships/{accountId} — 移除回池（解除挂靠，账号保留为池内成员）。</li>
 * </ul>
 *
 * <p>#49 下线的「外部挂靠」不复活：分配对象必须已是本主体池内成员；#48 的店长代建
 * （POST /stores/{storeId}/accounts）已随 #52 决策 A 退役。
 */
@RestController
@RequestMapping("/api/organizations/{orgId}/stores/{storeId}/memberships")
public class StoreMembershipController {

	private final StoreAuthorization storeAuthz;
	private final OrgAuthorization orgAuthz;
	private final StoreMembershipRepository storeMemberships;
	private final StoreAssignmentService assignments;

	public StoreMembershipController(StoreAuthorization storeAuthz, OrgAuthorization orgAuthz,
			StoreMembershipRepository storeMemberships, StoreAssignmentService assignments) {
		this.storeAuthz = storeAuthz;
		this.orgAuthz = orgAuthz;
		this.storeMemberships = storeMemberships;
		this.assignments = assignments;
	}

	@GetMapping
	public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable String orgId, @PathVariable String storeId,
			ServerHttpRequest request) {
		return storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.STAFF)
				.flatMap(account -> storeMemberships.findByStoreWithAccountStatus(storeId).collectList().map(list -> ResponseEntity
						.ok(Map.of("success", true, "data", list.stream().map(this::toBody).toList()))));
	}

	/**
	 * 分配或调度（#52 决策 C/D）：assign-or-move——已挂他店则同事务先解除再挂本店；
	 * 同店调用 = 改角色。body.role ∈ manager/staff。
	 */
	@PutMapping(path = "/{accountId}", consumes = MediaType.APPLICATION_JSON_VALUE)
	public Mono<ResponseEntity<Map<String, Object>>> assign(@PathVariable String orgId,
			@PathVariable String storeId, @PathVariable String accountId,
			@RequestBody AssignStoreMemberRequest body, ServerHttpRequest request) {
		return orgAuthz.requireRole(request, orgId, MembershipRole.ADMIN)
				.flatMap(operator -> assignments.assign(operator.id(), orgId, storeId, accountId, body.role()))
				.map(m -> ResponseEntity.ok(Map.of("success", true, "data", toBody(m))));
	}

	/** 移除回池（#52）：只解除本店挂靠；账号保留为池内成员，删号走主体区。 */
	@DeleteMapping("/{accountId}")
	public Mono<ResponseEntity<Map<String, Object>>> remove(@PathVariable String orgId,
			@PathVariable String storeId, @PathVariable String accountId, ServerHttpRequest request) {
		return orgAuthz.requireRole(request, orgId, MembershipRole.ADMIN)
				.flatMap(operator -> assignments.remove(operator.id(), orgId, storeId, accountId))
				.thenReturn(ResponseEntity.ok().<Map<String, Object>>body(Map.of("success", true)));
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
		// 账号名（前缀-登录名）：行展示与删除强确认都要用，仓储已联 account_username
		map.put("username", m.username());
		map.put("createdAt", m.createdAt() == null ? null : m.createdAt().toString());
		return map;
	}
}
