package com.grassland.identity.organization.subaccount;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.membership.MembershipRole;
import com.grassland.identity.membership.OrgAuthorization;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.user.AuthUser;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 商家主体子账号 HTTP 入口（任务书 #48；#52 池模型）。全部挂在既有
 * {@code /api/organizations/**} 家族下（D15：不新增 edge 前缀，规避 RouteManifest 404 陷阱）。
 *
 * <ul>
 * <li>POST /api/organizations/{orgId}/accounts — ADMIN+ 直建（唯一建号路径，#52 决策 A：
 * member=入池；manager/staff=入池并挂店，manager 过一店一店长闸）。</li>
 * <li>suspend/restore/reset-password — 停用恢复即时生效 + 三守卫（#52 决策 E：最后店长
 * 保护已废除，店可无店长由主体代管）。</li>
 * <li>#52 决策 A 退役：店长代建（POST /stores/{storeId}/accounts）、GET/PATCH
 * member-review-required、POST /accounts/{id}/review 均已删除——建号只在主体区一处。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/organizations/{orgId}")
public class OrgSubAccountController {

    private final CurrentAccountResolver accounts;
    private final OrgAuthorization orgAuthz;
    private final OrgSubAccountService subAccounts;

    public OrgSubAccountController(CurrentAccountResolver accounts, OrgAuthorization orgAuthz,
            OrgSubAccountService subAccounts) {
        this.accounts = accounts;
        this.orgAuthz = orgAuthz;
        this.subAccounts = subAccounts;
    }

    @PostMapping(value = "/accounts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> createByOrg(@PathVariable String orgId,
            @RequestBody CreateSubAccountRequest body, ServerHttpRequest request) {
        return orgAuthz.requireRole(request, orgId, MembershipRole.ADMIN)
                .flatMap(operator -> subAccounts.createByOrg(operator.id(), orgId, body))
                .map(created -> ResponseEntity.status(201).body(Map.of("success", true, "data", toBody(created))));
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
