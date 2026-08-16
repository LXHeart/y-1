package com.grassland.marketplace.taskcatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.marketplace.security.IdentityStoreAuthorizationClient;
import com.grassland.marketplace.security.IdentityStoreAuthorizationClient.StorePublicProfile;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 任务书 #24 Stage 3：任务视图的门店公开块增强。
 *
 * <p>用 identity 内部批量端点一次拉整页 storeId（页内先去重，不逐行调）；identity 不可用时
 * 降级为空 map（列表/详情仍可打开，只是没有门店块）——enrichment 不阻断核心交易链路。
 */
@Component
public class TaskStoreEnrichment {

    private final IdentityStoreAuthorizationClient identityStores;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public TaskStoreEnrichment(IdentityStoreAuthorizationClient identityStores) {
        this.identityStores = identityStores;
    }

    /** 按 storeId → 轻量门店块（storeName/city/categories），供 feed 与任务详情共用。 */
    public Mono<Map<String, Map<String, Object>>> loadStoreBlocks(Collection<String> storeIds) {
        if (storeIds == null || storeIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        storeIds.forEach(id -> {
            if (id != null && !id.isBlank()) {
                distinct.add(id);
            }
        });
        if (distinct.isEmpty()) {
            return Mono.just(Map.of());
        }
        return identityStores.publicProfiles(distinct)
                .map(profiles -> {
                    Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
                    for (StorePublicProfile profile : profiles) {
                        byId.put(profile.storeId(), storeBlock(profile));
                    }
                    return byId;
                })
                .onErrorResume(error -> Mono.just(Map.of()));
    }

    /** 单个门店块：大厅行内与详情页头部的轻量三件套。 */
    public static Map<String, Object> storeBlock(StorePublicProfile profile) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("storeName", profile.storeName());
        block.put("city", cityOf(profile.address()));
        block.put("categories", profile.categories() == null ? List.of() : profile.categories());
        return block;
    }

    /** storeBranding 快照块（AI 商家上下文），字段与任务书约定一致；无任何内容时返回 null。 */
    public static Map<String, Object> brandingBlock(StorePublicProfile profile) {
        boolean empty = isBlank(profile.brandTone())
                && isEmpty(profile.mustEmphasize()) && isEmpty(profile.forbiddenPhrases())
                && isEmpty(profile.allowedTags()) && isEmpty(profile.sellingPoints())
                && isEmpty(profile.categories()) && isEmpty(profile.signatureItems())
                && isBlank(profile.storeName());
        if (empty) {
            return null;
        }
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("storeName", profile.storeName());
        block.put("brandTone", profile.brandTone());
        block.put("mustEmphasize", orEmpty(profile.mustEmphasize()));
        block.put("forbiddenPhrases", orEmpty(profile.forbiddenPhrases()));
        block.put("allowedTags", orEmpty(profile.allowedTags()));
        block.put("sellingPoints", orEmpty(profile.sellingPoints()));
        block.put("categories", orEmpty(profile.categories()));
        block.put("signatureItems", orEmpty(profile.signatureItems()));
        return block;
    }

    private static List<String> orEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 从地址 jsonb 文本里容错取 city（坏 JSON/缺字段 → null，不影响主块）。 */
    private static String cityOf(String addressJson) {
        if (addressJson == null || addressJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(addressJson);
            JsonNode city = node == null ? null : node.get("city");
            return city != null && city.isTextual() && !city.asText().isBlank() ? city.asText() : null;
        } catch (Exception error) {
            return null;
        }
    }
}
