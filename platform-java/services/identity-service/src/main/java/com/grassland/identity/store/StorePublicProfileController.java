package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 门店公开详情页读端点（任务书 #24 Stage 2）：{@code GET /api/stores/{storeId}/public-profile}。
 *
 * <p>identity <b>没有全局 SecurityWebFilterChain</b>，本端点显式走
 * {@link CurrentAccountResolver#resolveOptional}：登录即可看，未登录也放行（大厅浏览场景），
 * 不用组织角色守卫。响应严格白名单（{@link StorePublicProfile}）：不回 KYB 审核列、
 * org owner、permission_tier、内部备注。
 *
 * <p>前置条件在 SQL 层：store.status=active 且所属 organization active（非 suspended），
 * 否则 404。路由前缀 {@code /api/stores} 需在 edge-bff RouteManifest 注册
 * （{@code EDGE_ROUTE_STORES_PUBLIC_IDENTITY}），否则 fail-closed 404。
 */
@RestController
public class StorePublicProfileController {

    private final CurrentAccountResolver accounts;
    private final StoreProfileRepository storeProfiles;

    public StorePublicProfileController(CurrentAccountResolver accounts,
                                        StoreProfileRepository storeProfiles) {
        this.accounts = accounts;
        this.storeProfiles = storeProfiles;
    }

    @GetMapping("/api/stores/{storeId}/public-profile")
    public Mono<ResponseEntity<Map<String, Object>>> publicProfile(@PathVariable String storeId,
                                                                   ServerHttpRequest request) {
        String id = requireUuid(storeId);
        // 可选鉴权：只用于确认登录态存在与否，不做任何授权判定；未登录同样放行。
        return accounts.resolveOptional(request)
                .then(storeProfiles.findPublicProfile(id)
                        .map(profile -> ResponseEntity.ok(Map.<String, Object>of(
                                "success", true, "data", toBody(profile))))
                        .switchIfEmpty(Mono.error(new IdentityException(404, "门店不存在"))));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    /** 白名单响应体：字段集合与 {@link StorePublicProfile} 一一对应，严禁整行序列化。 */
    private static Map<String, Object> toBody(StorePublicProfile profile) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("storeId", profile.storeId());
        m.put("storeName", profile.storeName());
        m.put("address", profile.address());
        m.put("phone", profile.phone());
        m.put("businessHours", profile.businessHours());
        m.put("description", profile.description());
        m.put("categories", profile.categories());
        m.put("signatureItems", profile.signatureItems());
        m.put("priceRange", profile.priceRange());
        m.put("averageSpendCents", profile.averageSpendCents());
        m.put("visitNotes", profile.visitNotes());
        m.put("sellingPoints", profile.sellingPoints());
        m.put("brandTone", profile.brandTone());
        m.put("mustEmphasize", profile.mustEmphasize());
        m.put("forbiddenPhrases", profile.forbiddenPhrases());
        m.put("allowedTags", profile.allowedTags());
        return m;
    }

    private static String requireUuid(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException error) {
            throw new IdentityException(404, "门店不存在");
        }
    }
}
