package com.grassland.intelligence.ai.byok;

import com.grassland.intelligence.security.IdentityOrgAuthorizationClient;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 组织 BYOK 回退策略管理入口（ADR-D17 / D-11）。
 *
 * <p>组织配置了有效组织密钥后，成员平台回退须本策略允许（默认不允许，无行 = 关）。
 * 响应形状与 {@code AiOrgBudgetController} 同款信封；鉴权同款：组织 admin/owner，
 * 组织不存在/非管理员统一 404 隐藏存在性。
 */
@RestController
@RequestMapping("/api/ai/organizations/{organizationId}/byok-policy")
public class AiOrgByokPolicyController {

    private final IntelligenceCallerResolver callers;
    private final IdentityOrgAuthorizationClient orgAuthorization;
    private final AiOrgByokPolicyRepository policies;

    public AiOrgByokPolicyController(
            IntelligenceCallerResolver callers,
            IdentityOrgAuthorizationClient orgAuthorization,
            AiOrgByokPolicyRepository policies) {
        this.callers = callers;
        this.orgAuthorization = orgAuthorization;
        this.policies = policies;
    }

    @GetMapping
    public Mono<ResponseEntity<Map<String, Object>>> get(
            @PathVariable String organizationId, ServerWebExchange exchange) {
        return requireAdmin(organizationId, exchange)
                .then(policies.find(organizationId)
                        .map(AiOrgByokPolicyController::toResponse)
                        .defaultIfEmpty(defaultResponse())
                        .map(AiOrgByokPolicyController::success));
    }

    @PutMapping
    public Mono<ResponseEntity<Map<String, Object>>> update(
            @PathVariable String organizationId,
            @RequestBody UpdatePolicyRequest body,
            ServerWebExchange exchange) {
        if (body.expectedVersion() == null || body.expectedVersion() < 0) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("success", false, "error", "expectedVersion 必填且不能为负")));
        }
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> orgAuthorization
                        .require(caller.accountId(), organizationId, "admin")
                        .onErrorMap(IntelligenceException.class, error ->
                                error.status() == 403 || error.status() == 404
                                        ? new IntelligenceException(404, "组织不存在") : error)
                        .then(Mono.defer(() -> upsert(organizationId, body, caller.accountId())))
                        .map(AiOrgByokPolicyController::success));
    }

    private Mono<Map<String, Object>> upsert(String organizationId, UpdatePolicyRequest body, String accountId) {
        long expectedVersion = body.expectedVersion();
        if (expectedVersion == 0) {
            return policies.create(organizationId, body.allowPlatformFallback(), accountId)
                    .map(AiOrgByokPolicyController::toResponse)
                    .switchIfEmpty(Mono.error(new IntelligenceException(409, "策略已存在，请刷新后重试")));
        }
        return policies.update(organizationId, body.allowPlatformFallback(), expectedVersion, accountId)
                .map(AiOrgByokPolicyController::toResponse)
                .switchIfEmpty(Mono.error(new IntelligenceException(409, "策略已被他人修改，请刷新后重试")));
    }

    private static Map<String, Object> toResponse(AiOrgByokPolicy policy) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configured", true);
        data.put("allowPlatformFallback", policy.allowPlatformFallback());
        data.put("version", policy.version());
        data.put("updatedAt", policy.updatedAt() == null ? null : policy.updatedAt().toString());
        return data;
    }

    private static Map<String, Object> defaultResponse() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("configured", false);
        data.put("allowPlatformFallback", false);
        data.put("version", 0);
        data.put("updatedAt", null);
        return data;
    }

    private static ResponseEntity<Map<String, Object>> success(Map<String, Object> data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    private Mono<Void> requireAdmin(String organizationId, ServerWebExchange exchange) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> orgAuthorization.require(caller.accountId(), organizationId, "admin")
                        .onErrorMap(IntelligenceException.class, error ->
                                error.status() == 403 || error.status() == 404
                                        ? new IntelligenceException(404, "组织不存在") : error));
    }

    /** expectedVersion=0 创建；allowPlatformFallback 关闭即写入 false（无行与显式 false 等价）。 */
    public record UpdatePolicyRequest(Long expectedVersion, boolean allowPlatformFallback) {
    }
}
