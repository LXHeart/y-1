package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.security.InternalServiceCallerResolver;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 门店公开资料内部批量端点（任务书 #24 Stage 2）：
 * {@code POST /internal/identity/stores/public-profiles}。
 *
 * <p>给 marketplace feed enrichment 一次拉整页 storeId（断言密钥对已在
 * {@code deploy/security/identity-assertion-key-pairs.csv} 登记，门店坐标解析同款链路）。
 * 内部端点不进 edge RouteManifest，走服务断言；响应同公开端点白名单。
 */
@RestController
public class InternalStorePublicProfileController {

    /** 一次批量上限（一页 feed ≤50，留余量防重复前去重前的原始入参）。 */
    private static final int MAX_BATCH = 100;

    private final InternalServiceCallerResolver callers;
    private final StoreProfileRepository profiles;

    public InternalStorePublicProfileController(InternalServiceCallerResolver callers,
                                                StoreProfileRepository profiles) {
        this.callers = callers;
        this.profiles = profiles;
    }

    @PostMapping("/internal/identity/stores/public-profiles")
    public Mono<ResponseEntity<Map<String, Object>>> batch(@RequestBody BatchRequest body,
                                                           ServerHttpRequest request) {
        LinkedHashSet<String> storeIds = requireStoreIds(body);
        return callers.requireServicePrincipal(request, "marketplace")
                .then(profiles.findPublicProfiles(storeIds)
                        .map(InternalStorePublicProfileController::toBody)
                        .collectList()
                        .map(items -> ResponseEntity.ok(Map.<String, Object>of(
                                "success", true, "data", items))));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    private static LinkedHashSet<String> requireStoreIds(BatchRequest body) {
        if (body == null || body.storeIds() == null || body.storeIds().isEmpty()) {
            throw new IdentityException(400, "storeIds 不能为空");
        }
        if (body.storeIds().size() > MAX_BATCH) {
            throw new IdentityException(400, "一次最多查询 " + MAX_BATCH + " 个门店");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : body.storeIds()) {
            if (value == null || value.isBlank()) {
                throw new IdentityException(400, "storeId 不能为空");
            }
            try {
                normalized.add(UUID.fromString(value).toString());
            } catch (IllegalArgumentException error) {
                throw new IdentityException(400, "storeId 格式无效");
            }
        }
        return normalized;
    }

    /** 白名单响应体，与公开端点一致（严禁整行序列化）。 */
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

    public record BatchRequest(List<String> storeIds) {}
}
