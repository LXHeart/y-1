package com.grassland.identity.brand;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import com.grassland.identity.organization.OrganizationRepository;
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
 * 组织品牌资料公开消费端点（缺口清偿之六，#32 D9 按需另案）：
 * {@code GET /api/organizations/{orgId}/public-brand-profile}。
 *
 * <p>供大厅/任务详情/AI 上下文按 org 消费品牌资料（商家后台读写仍走
 * {@link BrandProfileController}）。identity 无全局 SecurityWebFilterChain，本端点显式走
 * {@link CurrentAccountResolver#resolveOptional}：未登录也放行。
 *
 * <p>gate = 组织存在且 active，否则 404（与门店公开口径一致——非 active 组织不出现在公开面）。
 * 响应白名单：organizationId/brandName/description/industry/logoUrl——**不含**
 * {@code brandLogoMediaReferenceId}（内部媒体 id）与 {@code version}（写路径细节）。
 * 无资料行回全 null 字段（公开页渲染为空）；{@code logoUrl} 经
 * {@link BrandLogoMediaClient#logoUrlFailSoft} fail-soft 解析。
 */
@RestController
public class PublicBrandProfileController {

    private final CurrentAccountResolver accounts;
    private final OrganizationRepository organizations;
    private final OrganizationBrandProfileRepository profiles;
    private final BrandLogoMediaClient mediaClient;

    public PublicBrandProfileController(CurrentAccountResolver accounts,
                                        OrganizationRepository organizations,
                                        OrganizationBrandProfileRepository profiles,
                                        BrandLogoMediaClient mediaClient) {
        this.accounts = accounts;
        this.organizations = organizations;
        this.profiles = profiles;
        this.mediaClient = mediaClient;
    }

    @GetMapping("/api/organizations/{orgId}/public-brand-profile")
    public Mono<ResponseEntity<Map<String, Object>>> publicBrand(@PathVariable String orgId,
                                                                 ServerHttpRequest request) {
        String id = requireUuid(orgId);
        // 可选鉴权：只确认登录态存在与否，不做任何授权判定；未登录同样放行。
        return accounts.resolveOptional(request)
                .then(organizations.findById(id))
                .filter(organization -> "active".equals(organization.status()))
                .switchIfEmpty(Mono.error(new IdentityException(404, "组织不存在")))
                .flatMap(organization -> profiles.find(id)
                        .flatMap(profile -> withLogoUrl(profile))
                        .defaultIfEmpty(publicBody(id, null, null, null, null)))
                .map(data -> ResponseEntity.ok(envelope(data)));
    }

    private Mono<Map<String, Object>> withLogoUrl(OrganizationBrandProfile profile) {
        if (profile.brandLogoMediaReferenceId() == null) {
            return Mono.just(publicBody(
                    profile.organizationId(), profile.brandName(), profile.description(),
                    profile.industry(), null));
        }
        return mediaClient.logoUrlFailSoft(profile.brandLogoMediaReferenceId(), profile.organizationId())
                .map(logoUrl -> publicBody(
                        profile.organizationId(), profile.brandName(), profile.description(),
                        profile.industry(), logoUrl))
                .defaultIfEmpty(publicBody(
                        profile.organizationId(), profile.brandName(), profile.description(),
                        profile.industry(), null));
    }

    /** 公开白名单体：全字段可空（未填资料/未配置 Logo 的合法状态）。 */
    private static Map<String, Object> publicBody(String organizationId, String brandName,
                                                  String description, String industry, String logoUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organizationId", organizationId);
        m.put("brandName", brandName);
        m.put("description", description);
        m.put("industry", industry);
        m.put("logoUrl", logoUrl);
        return m;
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    /** 响应包装。用 LinkedHashMap 而非 {@code Map.of}——data 字段可空。 */
    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    private static String requireUuid(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException error) {
            throw new IdentityException(404, "组织不存在");
        }
    }
}
