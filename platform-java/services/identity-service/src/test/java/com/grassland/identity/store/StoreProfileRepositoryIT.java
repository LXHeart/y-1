package com.grassland.identity.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * store_profile 营销字段 repository IT（任务书 #24 Stage 1）。
 *
 * <p>守卫：写入 SQL 按 {@code (organization_id, store_id)} 双限定——跨组织一律查空（统一 404 的第二道闸）；
 * 空数组与 null 等价（清空语义）；编辑重置 draft 的 V22 KYB 语义不变。
 */
class StoreProfileRepositoryIT extends IdentityItSupport {

    @Autowired
    private StoreProfileRepository repository;

    @Test
    void marketingFieldsRoundTrip() {
        var owner = seedAccount("store-profile-repo-roundtrip@example.com");
        String orgId = createOrg(owner.cookie(), "门店资料仓储主体");
        String storeId = createStore(orgId, owner.cookie(), "仓储往返门店");

        StoreProfileDraft draft = new StoreProfileDraft(
                "{\"address\":\"南京西路 1 号\"}", "13800000000", null, "老字号火锅",
                List.of("火锅", "川菜"), List.of("招牌毛肚"), List.of("现切牛肉", "免费停车"),
                List.of("锅底现熬"), List.of("最好吃"), List.of("#探店", "#火锅"),
                "温暖亲切", "¥30–¥80", 6500, "地铁 2 号线直达");
        StoreProfile saved = repository.upsertDraft(orgId, storeId, draft).block();

        assertThat(saved).isNotNull();
        assertThat(saved.status()).isEqualTo("draft");
        assertThat(saved.categories()).containsExactly("火锅", "川菜");
        assertThat(saved.signatureItems()).containsExactly("招牌毛肚");
        assertThat(saved.sellingPoints()).containsExactly("现切牛肉", "免费停车");
        assertThat(saved.mustEmphasize()).containsExactly("锅底现熬");
        assertThat(saved.forbiddenPhrases()).containsExactly("最好吃");
        assertThat(saved.allowedTags()).containsExactly("#探店", "#火锅");
        assertThat(saved.brandTone()).isEqualTo("温暖亲切");
        assertThat(saved.priceRange()).isEqualTo("¥30–¥80");
        assertThat(saved.averageSpendCents()).isEqualTo(6500);
        assertThat(saved.visitNotes()).isEqualTo("地铁 2 号线直达");

        StoreProfile reloaded = repository.findByOrganizationAndId(orgId, storeId).block();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.categories()).containsExactly("火锅", "川菜");
        assertThat(reloaded.averageSpendCents()).isEqualTo(6500);
        assertThat(reloaded.brandTone()).isEqualTo("温暖亲切");
    }

    @Test
    void emptyListsClearExistingMarketingFields() {
        var owner = seedAccount("store-profile-repo-clear@example.com");
        String orgId = createOrg(owner.cookie(), "门店资料清空主体");
        String storeId = createStore(orgId, owner.cookie(), "清空语义门店");

        repository.upsertDraft(orgId, storeId, new StoreProfileDraft(
                "{\"address\":\"南京西路 1 号\"}", null, null, null,
                List.of("火锅"), List.of("招牌毛肚"), List.of("卖点"),
                List.of("强调"), List.of("禁语"), List.of("#标签"),
                "语气", "¥30–¥80", 6500, "提示")).block();

        StoreProfile cleared = repository.upsertDraft(orgId, storeId, new StoreProfileDraft(
                "{\"address\":\"南京西路 1 号\"}", null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null)).block();

        assertThat(cleared).isNotNull();
        assertThat(cleared.categories()).isEmpty();
        assertThat(cleared.signatureItems()).isEmpty();
        assertThat(cleared.sellingPoints()).isEmpty();
        assertThat(cleared.mustEmphasize()).isEmpty();
        assertThat(cleared.forbiddenPhrases()).isEmpty();
        assertThat(cleared.allowedTags()).isEmpty();
        assertThat(cleared.brandTone()).isNull();
        assertThat(cleared.priceRange()).isNull();
        assertThat(cleared.averageSpendCents()).isNull();
        assertThat(cleared.visitNotes()).isNull();
    }

    @Test
    void crossOrgScopedWritesAndReadsStayEmpty() {
        var ownerA = seedAccount("store-profile-repo-org-a@example.com");
        String orgA = createOrg(ownerA.cookie(), "仓储主体A");
        String storeId = createStore(orgA, ownerA.cookie(), "A店");

        var ownerB = seedAccount("store-profile-repo-org-b@example.com");
        String orgB = createOrg(ownerB.cookie(), "仓储主体B");

        // 用 orgB 作用域写 A 店资料 → 门店不属于该组织 → 空（controller 层映射 404）。
        StoreProfile crossWrite = repository.upsertDraft(orgB, storeId, new StoreProfileDraft(
                "{\"address\":\"越权地址\"}", null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                null, null, null, null)).block();
        assertThat(crossWrite).isNull();

        assertThat(repository.findByOrganizationAndId(orgB, storeId).block()).isNull();
        assertThat(repository.findByOrganizationAndIdForUpdate(orgB, storeId).block()).isNull();

        // A 店资料未受影响。
        StoreProfile intact = repository.findByOrganizationAndId(orgA, storeId).block();
        assertThat(intact).isNull(); // 从未在 orgA 写过资料
    }
}
