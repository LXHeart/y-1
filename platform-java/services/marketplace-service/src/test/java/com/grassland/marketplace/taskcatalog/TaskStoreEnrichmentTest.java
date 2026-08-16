package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.security.IdentityStoreAuthorizationClient;
import com.grassland.marketplace.security.IdentityStoreAuthorizationClient.StorePublicProfile;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

/**
 * feed/详情门店块增强单测（任务书 #24 Stage 3）：页内去重、city 解析、identity 故障降级、
 * storeBranding 块组装。
 */
class TaskStoreEnrichmentTest {

    private final IdentityStoreAuthorizationClient client = mock(IdentityStoreAuthorizationClient.class);
    private final TaskStoreEnrichment enrichment = new TaskStoreEnrichment(client);

    private static StorePublicProfile profile(String storeId) {
        return new StorePublicProfile(storeId, "旗舰店",
                "{\"city\":\"上海\",\"address\":\"南京西路 1 号\"}",
                null, null, null, List.of("火锅"), List.of("招牌毛肚"),
                "¥30–¥80", 6500, "地铁直达", List.of("现切牛肉"),
                "温暖亲切", List.of("锅底现熬"), List.of("最好吃"), List.of("#探店"));
    }

    @Test
    void dedupesStoreIdsBeforeSingleBatchCall() {
        when(client.publicProfiles(any())).thenReturn(Mono.just(List.of(profile("s1"))));

        Map<String, Map<String, Object>> blocks = enrichment
                .loadStoreBlocks(Arrays.asList("s1", "s1", "s1", null, "  "))
                .block();

        assertThat(blocks).containsOnlyKeys("s1");
        assertThat(blocks.get("s1")).containsEntry("storeName", "旗舰店")
                .containsEntry("city", "上海");
        assertThat((List<String>) blocks.get("s1").get("categories")).containsExactly("火锅");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(client).publicProfiles(captor.capture());
        assertThat(captor.getValue()).containsExactly("s1");
    }

    @Test
    void emptyInputNeverCallsIdentity() {
        assertThat(enrichment.loadStoreBlocks(List.of()).block()).isEmpty();
        assertThat(enrichment.loadStoreBlocks(null).block()).isEmpty();
        verify(client, never()).publicProfiles(any());
    }

    @Test
    void identityFailureDegradesToEmptyMap() {
        when(client.publicProfiles(any())).thenReturn(Mono.error(new IllegalStateException("down")));
        assertThat(enrichment.loadStoreBlocks(List.of("s1")).block()).isEmpty();
    }

    @Test
    void cityMissingWhenAddressMalformed() {
        StorePublicProfile broken = new StorePublicProfile("s1", "旗舰店", "not-json",
                null, null, null, List.of(), List.of(), null, null, null,
                List.of(), null, List.of(), List.of(), List.of());
        Map<String, Object> block = TaskStoreEnrichment.storeBlock(broken);
        assertThat(block.get("storeName")).isEqualTo("旗舰店");
        assertThat(block.get("city")).isNull();
    }

    @Test
    void brandingBlockCarriesAiContextFields() {
        Map<String, Object> branding = TaskStoreEnrichment.brandingBlock(profile("s1"));
        assertThat(branding)
                .containsEntry("storeName", "旗舰店")
                .containsEntry("brandTone", "温暖亲切");
        assertThat((List<String>) branding.get("mustEmphasize")).containsExactly("锅底现熬");
        assertThat((List<String>) branding.get("forbiddenPhrases")).containsExactly("最好吃");
        assertThat((List<String>) branding.get("allowedTags")).containsExactly("#探店");
        assertThat((List<String>) branding.get("sellingPoints")).containsExactly("现切牛肉");
        assertThat((List<String>) branding.get("categories")).containsExactly("火锅");
        assertThat((List<String>) branding.get("signatureItems")).containsExactly("招牌毛肚");
        // AI 上下文不携带地址/电话/营业时间等与创作无关的字段。
        assertThat(branding).doesNotContainKeys("address", "phone", "businessHours",
                "priceRange", "averageSpendCents", "visitNotes");
    }

    @Test
    void brandingBlockNullWhenStoreHasNoContent() {
        StorePublicProfile empty = new StorePublicProfile("s1", null, null,
                null, null, null, List.of(), List.of(), null, null, null,
                List.of(), null, List.of(), List.of(), List.of());
        assertThat(TaskStoreEnrichment.brandingBlock(empty)).isNull();
    }
}
