package com.grassland.identity.store;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.assertion.IdentityAssertion;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门店公开详情页 + 内部批量端点 IT（任务书 #24 Stage 2）。
 *
 * <p>公开端点：未登录 200、suspended store/org 404、响应白名单不含 KYB 审核列；
 * 内部端点：断言缺失 401、非受信服务 403、批量往返。
 */
class StorePublicProfileControllerIT extends IdentityItSupport {

    private static final String PUBLIC_PATH_SUFFIX = "/public-profile";

    @Test
    void publicProfileReadableWithoutLoginAndWhitelisted() {
        var owner = seedAccount("public-profile-owner@example.com");
        String orgId = createOrg(owner.cookie(), "公开资料主体");
        String storeId = createStore(orgId, owner.cookie(), "公开资料门店");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("address", "{\"address\":\"南京西路 1 号\",\"city\":\"上海\"}");
        body.put("phone", "13800000000");
        body.put("businessHours", "[{\"dayOfWeek\":1,\"openTime\":\"09:00\",\"closeTime\":\"22:00\"}]");
        body.put("description", "老字号火锅");
        body.put("categories", List.of("火锅", "川菜"));
        body.put("signatureItems", List.of("招牌毛肚"));
        body.put("sellingPoints", List.of("现切牛肉"));
        body.put("mustEmphasize", List.of("锅底现熬"));
        body.put("forbiddenPhrases", List.of("最好吃"));
        body.put("allowedTags", List.of("#探店"));
        body.put("brandTone", "温暖亲切");
        body.put("priceRange", "¥30–¥80");
        body.put("averageSpendCents", 6500);
        body.put("visitNotes", "地铁 2 号线直达");
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(body)
                .exchange().expectStatus().isOk();

        // 未登录（无 cookie）也放行 —— 大厅浏览场景。
        var response = client().get().uri("/api/stores/" + storeId + PUBLIC_PATH_SUFFIX)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.storeId").isEqualTo(storeId)
                .jsonPath("$.data.storeName").isEqualTo("公开资料门店")
                // jsonb 不保键序，只断言内容存在。
                .jsonPath("$.data.address").value(address -> assertThat(String.valueOf(address))
                        .contains("南京西路 1 号").contains("上海"))
                .jsonPath("$.data.phone").isEqualTo("13800000000")
                .jsonPath("$.data.businessHours").isNotEmpty()
                .jsonPath("$.data.description").isEqualTo("老字号火锅")
                .jsonPath("$.data.categories[0]").isEqualTo("火锅")
                .jsonPath("$.data.signatureItems[0]").isEqualTo("招牌毛肚")
                .jsonPath("$.data.priceRange").isEqualTo("¥30–¥80")
                .jsonPath("$.data.averageSpendCents").isEqualTo(6500)
                .jsonPath("$.data.visitNotes").isEqualTo("地铁 2 号线直达")
                .jsonPath("$.data.sellingPoints[0]").isEqualTo("现切牛肉")
                .jsonPath("$.data.brandTone").isEqualTo("温暖亲切")
                .jsonPath("$.data.mustEmphasize[0]").isEqualTo("锅底现熬")
                .jsonPath("$.data.forbiddenPhrases[0]").isEqualTo("最好吃")
                .jsonPath("$.data.allowedTags[0]").isEqualTo("#探店")
                // 白名单守卫：KYB 审核列与组织内部字段严禁外泄。
                .jsonPath("$.data.status").doesNotExist()
                .jsonPath("$.data.submittedAt").doesNotExist()
                .jsonPath("$.data.reviewedAt").doesNotExist()
                .jsonPath("$.data.reviewerAccountId").doesNotExist()
                .jsonPath("$.data.reviewNote").doesNotExist()
                .jsonPath("$.data.organizationId").doesNotExist()
                .jsonPath("$.data.permissionTier").doesNotExist()
                .returnResult();
        assertThat(new String(response.getResponseBody())).doesNotContain("review");

        // 登录用户同样可读。
        var other = seedAccount("public-profile-viewer@example.com");
        client().get().uri("/api/stores/" + storeId + PUBLIC_PATH_SUFFIX)
                .header("Cookie", "y1.sid=" + other.cookie())
                .exchange().expectStatus().isOk();
    }

    @Test
    void publicProfileReturns404ForMissingInactiveOrUnknownStores() {
        var owner = seedAccount("public-profile-404@example.com");
        String orgId = createOrg(owner.cookie(), "公开资料404主体");

        // 从未填写资料 → 404。
        String emptyStore = createStore(orgId, owner.cookie(), "空资料门店");
        client().get().uri("/api/stores/" + emptyStore + PUBLIC_PATH_SUFFIX)
                .exchange().expectStatus().isNotFound();

        // 有资料但门店停用 → 404。
        String inactiveStore = createStore(orgId, owner.cookie(), "停用门店");
        seedProfile(orgId, inactiveStore, owner.cookie());
        db.sql("UPDATE store SET status = 'inactive' WHERE id = CAST(:id AS uuid)")
                .bind("id", inactiveStore).then().block();
        client().get().uri("/api/stores/" + inactiveStore + PUBLIC_PATH_SUFFIX)
                .exchange().expectStatus().isNotFound();

        // 有资料但组织 suspended → 404。
        String suspendedOrgStore = createStore(orgId, owner.cookie(), "组织停用门店");
        seedProfile(orgId, suspendedOrgStore, owner.cookie());
        db.sql("UPDATE organization SET status = 'suspended' WHERE id = CAST(:id AS uuid)")
                .bind("id", orgId).then().block();
        client().get().uri("/api/stores/" + suspendedOrgStore + PUBLIC_PATH_SUFFIX)
                .exchange().expectStatus().isNotFound();
        db.sql("UPDATE organization SET status = 'active' WHERE id = CAST(:id AS uuid)")
                .bind("id", orgId).then().block();

        // 未知 UUID 与非 UUID → 404（不泄露存在性）。
        client().get().uri("/api/stores/" + UUID.randomUUID() + PUBLIC_PATH_SUFFIX)
                .exchange().expectStatus().isNotFound();
        client().get().uri("/api/stores/not-a-uuid" + PUBLIC_PATH_SUFFIX)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @SuppressWarnings("unchecked")
    void internalBatchReturnsWhitelistedProfilesForTrustedMarketplace() {
        var owner = seedAccount("public-profile-batch@example.com");
        String orgId = createOrg(owner.cookie(), "批量公开资料主体");
        String storeA = createStore(orgId, owner.cookie(), "批量门店A");
        String storeB = createStore(orgId, owner.cookie(), "批量门店B");
        seedProfile(orgId, storeA, owner.cookie());
        // storeB 无资料行：LEFT JOIN 仍回 storeName（feed enrichment 需要）。

        Map<String, Object> response = client()
                .post().uri("/internal/identity/stores/public-profiles")
                .header("X-Grassland-Identity", serviceAssertion("marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storeIds", List.of(storeA, storeB, UUID.randomUUID().toString())))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.get("data");
        assertThat(items).hasSize(2);

        Map<String, Object> a = items.stream()
                .filter(item -> storeA.equals(item.get("storeId"))).findFirst().orElseThrow();
        assertThat(a.get("storeName")).isEqualTo("批量门店A");
        assertThat((List<String>) a.get("categories")).containsExactly("火锅");
        assertThat(a.get("brandTone")).isEqualTo("温暖亲切");
        assertThat(a).doesNotContainKeys("status", "submittedAt", "reviewedAt",
                "reviewerAccountId", "reviewNote", "organizationId", "permissionTier");

        Map<String, Object> b = items.stream()
                .filter(item -> storeB.equals(item.get("storeId"))).findFirst().orElseThrow();
        assertThat(b.get("storeName")).isEqualTo("批量门店B");
        assertThat((List<?>) b.get("categories")).isEmpty();
    }

    @Test
    void internalBatchRejectsMissingUntrustedAndMalformedRequests() {
        String storeId = UUID.randomUUID().toString();

        // 断言缺失 → 401。
        client().post().uri("/internal/identity/stores/public-profiles")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storeIds", List.of(storeId)))
                .exchange().expectStatus().isUnauthorized();

        // 非受信服务 → 403。
        client().post().uri("/internal/identity/stores/public-profiles")
                .header("X-Grassland-Identity", serviceAssertion("trust"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storeIds", List.of(storeId)))
                .exchange().expectStatus().isForbidden();

        // 空入参/非法 UUID → 400。
        client().post().uri("/internal/identity/stores/public-profiles")
                .header("X-Grassland-Identity", serviceAssertion("marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storeIds", List.of()))
                .exchange().expectStatus().isBadRequest();
        client().post().uri("/internal/identity/stores/public-profiles")
                .header("X-Grassland-Identity", serviceAssertion("marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storeIds", List.of("not-a-uuid")))
                .exchange().expectStatus().isBadRequest();
    }

    private void seedProfile(String orgId, String storeId, String cookie) {
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + cookie)
                .bodyValue(Map.of(
                        "address", "{\"address\":\"南京西路 1 号\"}",
                        "categories", List.of("火锅"),
                        "brandTone", "温暖亲切"))
                .exchange().expectStatus().isOk();
    }

    private String serviceAssertion(String principal) {
        Instant now = Instant.now();
        return com.grassland.identity.assertion.TestAssertionHelper
                .serviceSigner(principal, "grassland-identity").sign(new IdentityAssertion(
                        "service:" + principal, null, null, null, null,
                        "service", "internal", null, UUID.randomUUID().toString(),
                        UUID.randomUUID().toString(),
                        "grassland-identity", now, now.plusSeconds(30), "service", principal));
    }
}
