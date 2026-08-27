package com.grassland.identity.organization.subaccount;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.organization.OrganizationRepository;
import com.grassland.identity.store.StoreAuthorization;
import com.grassland.identity.store.StoreRole;
import com.grassland.identity.user.AuthUser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 商家主体子账号 HTTP 入口（任务书 #48）。全部挂在既有 {@code /api/organizations/**} 家族下
 * （D15：不新增 edge 前缀，规避 RouteManifest 404 陷阱）。
 *
 * <ul>
 * <li>POST /api/organizations/{orgId}/accounts — owner/admin 直建任意角色（D1），邮箱冲突走
 * confirmBindExisting 分支（D5）。</li>
 * <li>POST /api/organizations/{orgId}/stores/{storeId}/accounts — 店长代建本店 staff，
 * 开关决定 active/pending_review（D6）。</li>
 * <li>GET/PATCH /api/organizations/{orgId}/member-review-required — 审核开关读/切。</li>
 * <li>suspend/restore/review/reset-password — 停用恢复即时生效（D7）+ 四守卫（D8）；
 * 权限在 service 按 id 显式判定，controller 只负责解析当前账号。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/organizations/{orgId}")
public class OrgSubAccountController {

    private final CurrentAccountResolver accounts;
    private final OrgAuthorization orgAuthz;
    private final StoreAuthorization storeAuthz;
    private final OrganizationRepository organizations;
    private final OrgSubAccountService subAccounts;

    public OrgSubAccountController(CurrentAccountResolver accounts, OrgAuthorization orgAuthz,
            StoreAuthorization storeAuthz, OrganizationRepository organizations,
            OrgSubAccountService subAccounts) {
        this.accounts = accounts;
        this.orgAuthz = orgAuthz;
        this.storeAuthz = storeAuthz;
        this.organizations = organizations;
        this.subAccounts = subAccounts;
    }

    @PostMapping(value = "/accounts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createByOrg(@PathVariable String orgId,
            @RequestBody CreateSubAccountRequest body, ServerHttpRequest request) {
        return orgAuthz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(operator -> subAccounts.createByOrg(operator.id(), orgId, body))
                .map(created -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(created))));
    }

    @PostMapping(value = "/stores/{storeId}/accounts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createByStoreManager(@PathVariable String orgId,
            @PathVariable String storeId, @RequestBody CreateSubAccountRequest body, ServerHttpRequest request) {
        return storeAuthz.requireStoreRole(request, orgId, storeId, StoreRole.MANAGER)
                .flatMap(operator -> subAccounts.createStaffByManager(operator.id(), orgId, storeId, body))
                .map(created -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(created))));
    }

    /** 开关读：本组织任意成员可读（前端渲染 toggle 用），非成员 403。 */
    @GetMapping("/member-review-required")
    public Mono<ResponseEntity<Map<String, Object>>> getReviewRequired(@PathVariable String orgId,
            ServerHttpRequest request) {
        return accounts.resolve(request)
                .flatMap(operator -> orgAuthz.roleOfAccount(operator.id(), orgId))
                .switchIfEmpty(Mono.error(new IdentityException(403, "无权访问该组织")))
                .then(organizations.selectMemberReviewRequired(orgId).defaultIfEmpty(Boolean.FALSE))
                .map(required -> ResponseEntity.ok(Map.of("success", true, "data", Map.of("required", required))));
    }

    /** 开关切：仅 ADMIN+（D6）。 */
    @PatchMapping(value = "/member-review-required", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> updateReviewRequired(@PathVariable String orgId,
            @RequestBody MemberReviewRequiredRequest body, ServerHttpRequest request) {
        if (body.required() == null) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("success", false, "error", "required 必填")));
        }
        return orgAuthz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(operator -> organizations.updateMemberReviewRequired(orgId, body.required()))
                .thenReturn(ResponseEntity.ok().<Map<String, Object>>body(
                        Map.of("success", true, "data", Map.of("required", body.required()))));
    }

    @PostMapping("/accounts/{accountId}/suspend")
    public Mono<ResponseEntity<Map<String, Object>>> suspend(@PathVariable String orgId,
            @PathVariable String accountId, ServerHttpRequest request) {
        return actOnTargetVoid(request, operator -> subAccounts.suspend(operator, orgId, accountId));
    }

    /** 删除成员（任务书 #49 D8）：关系解除 + 账号永久作废（逻辑删除留痕）；前端须输入账号名强确认。 */
    @DeleteMapping("/accounts/{accountId}")
    public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable String orgId,
            @PathVariable String accountId, ServerHttpRequest request) {
        return actOnTargetVoid(request, operator -> subAccounts.deleteSubAccount(operator, orgId, accountId));
    }

    @PostMapping("/accounts/{accountId}/restore")
    public Mono<ResponseEntity<Map<String, Object>>> restore(@PathVariable String orgId,
            @PathVariable String accountId, ServerHttpRequest request) {
        return actOnTargetVoid(request, operator -> subAccounts.restore(operator, orgId, accountId));
    }

    @PostMapping(value = "/accounts/{accountId}/review", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> review(@PathVariable String orgId,
            @PathVariable String accountId, @RequestBody SubAccountReviewRequest body, ServerHttpRequest request) {
        return actOnTargetVoid(request, operator -> subAccounts.review(operator, orgId, accountId, body));
    }

    @PostMapping("/accounts/{accountId}/reset-password")
    public Mono<ResponseEntity<Map<String, Object>>> resetPassword(@PathVariable String orgId,
            @PathVariable String accountId, ServerHttpRequest request) {
        return accounts.resolve(request)
                .map(AuthUser::id)
                .flatMap(operator -> subAccounts.resetPassword(operator, orgId, accountId))
                .map(created -> ResponseEntity.ok().body(
                        Map.<String, Object>of("success", true, "data", toBody(created))));
    }

    /** 统一包装：解析操作者 → 执行无返回值动作 → 成功响应。 */
    private Mono<ResponseEntity<Map<String, Object>>> actOnTargetVoid(ServerHttpRequest request,
            java.util.function.Function<String, Mono<Void>> action) {
        return accounts.resolve(request)
                .map(AuthUser::id)
                .flatMap(action)
                .thenReturn(okNoContent());
    }

    private ResponseEntity<Map<String, Object>> okNoContent() {
        return ResponseEntity.ok().body(Map.<String, Object>of("success", true));
    }

    private Map<String, Object> toBody(OrgSubAccountService.CreatedSubAccount created) {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", created.accountId());
        account.put("username", created.username());
        account.put("displayName", created.displayName());
        account.put("role", created.role());
        account.put("status", created.status());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("account", account);
        // D2/D3：明文初始密码只在创建/重置这一次响应出现，此后任何接口不可再取。
        if (created.initialPassword() != null) {
            data.put("initialPassword", created.initialPassword());
            data.put("mustChangePassword", true);
        }
        return data;
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
    }
}
