package com.grassland.identity.store;

import com.grassland.identity.auth.IdentityException;
import com.grassland.identity.organization.CurrentAccountResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * 门店公开媒体聚合端点（任务书 #42 D4/D5/D8）：{@code GET /api/stores/{storeId}/public-media}。
 *
 * <p>identity <b>没有全局 SecurityWebFilterChain</b>，本端点显式走
 * {@link CurrentAccountResolver#resolveOptional}：未登录也放行（大厅浏览场景）。
 * gate = store+org 双 active（照 public-profile SQL gate）否则 404；无绑定 → 200 groups 全空数组。
 * 一次 {@code client.downloadUrls} 换整店（≤33 项）URL，单项被滤静默跳过；上游 5xx → 503（D5）。
 *
 * <p>响应严格白名单（手写 toBody）：每项仅 mediaId/mimeType/sizeBytes/position/downloadUrl/
 * urlExpiresAt，<b>严禁</b>含 uploadedBy/organizationId/createdAt。
 */
@RestController
public class StorePublicMediaController {

    private static final List<String> GROUP_ORDER =
            List.of("storefront", "environment", "menu", "video");

    private final CurrentAccountResolver accounts;
    private final StoreMediaRepository storeMedia;
    private final StoreMediaClient mediaClient;

    public StorePublicMediaController(CurrentAccountResolver accounts,
                                      StoreMediaRepository storeMedia,
                                      StoreMediaClient mediaClient) {
        this.accounts = accounts;
        this.storeMedia = storeMedia;
        this.mediaClient = mediaClient;
    }

    @GetMapping("/api/stores/{storeId}/public-media")
    public Mono<ResponseEntity<Map<String, Object>>> publicMedia(@PathVariable String storeId,
                                                                 ServerHttpRequest request) {
        String id = requireUuid(storeId);
        // 可选鉴权：只确认登录态存在与否，不做任何授权判定；未登录同样放行。
        return accounts.resolveOptional(request)
                .then(storeMedia.isPubliclyReadable(id)
                        .flatMap(readable -> readable
                                ? storeMedia.findPublic(id).collectList()
                                : Mono.error(new IdentityException(404, "门店不存在")))
                        .flatMap(bindings -> {
                            if (bindings.isEmpty()) {
                                return Mono.just(toBody(id, List.of(), Map.of()));
                            }
                            String orgId = bindings.get(0).organizationId();
                            List<String> mediaIds = bindings.stream()
                                    .map(StoreMediaBinding::mediaReferenceId).toList();
                            return mediaClient.downloadUrls(orgId, id, mediaIds)
                                    .map(resolved -> toBody(id, bindings, resolved));
                        }))
                .map(data -> ResponseEntity.ok(envelope(data)));
    }

    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String, Object>> handleError(IdentityException error) {
        return ResponseEntity.status(error.status())
                .body(Map.of("success", false, "error", error.getMessage()));
    }

    private static Map<String, Object> envelope(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    /**
     * 白名单响应体：data = {storeId, groups:{storefront,environment,menu,video}}；
     * 单项被滤（不在 resolved 集）静默跳过；空组保留空数组（公开页仍要渲染资料面板）。
     */
    private static Map<String, Object> toBody(String storeId, List<StoreMediaBinding> bindings,
                                              Map<String, ResolvedMedia> resolved) {
        Map<String, Object> groups = new LinkedHashMap<>();
        for (String kind : GROUP_ORDER) {
            groups.put(kind, new ArrayList<Map<String, Object>>());
        }
        for (StoreMediaBinding binding : bindings) {
            ResolvedMedia media = resolved.get(binding.mediaReferenceId());
            if (media == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> group = (List<Map<String, Object>>) groups.get(binding.kind());
            if (group == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mediaId", binding.mediaReferenceId());
            item.put("mimeType", media.mimeType());
            item.put("sizeBytes", media.sizeBytes());
            item.put("position", binding.position());
            item.put("downloadUrl", media.downloadUrl());
            item.put("urlExpiresAt", media.expiresAt() == null ? null : media.expiresAt().toString());
            group.add(item);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("storeId", storeId);
        data.put("groups", groups);
        return data;
    }

    private static String requireUuid(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException error) {
            throw new IdentityException(404, "门店不存在");
        }
    }
}
