package com.grassland.identity.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.auth.IdentityException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

/**
 * 门店公开媒体聚合端点 IT（任务书 #42 D4/D5/D8）。
 *
 * <p>覆盖：未登录 200、无绑定 200 全空组、suspended store/org 404、上游 503 透传且
 * public-profile 不受影响（D4 解耦）、被滤项静默跳过、白名单不含 uploadedBy/organizationId/createdAt。
 */
class StorePublicMediaControllerIT extends IdentityItSupport {

    private static final String SUFFIX = "/public-media";

    @Autowired
    private StoreMediaRepository storeMediaRepository;

    /** 直接经仓储造绑定（公开端点只读，不经管理端点绕路）。 */
    private void seedBinding(String orgId, String storeId, StoreMediaKind kind, String mediaId,
                             String uploadedBy) {
        storeMediaRepository.bind(orgId, storeId, kind,
                List.of(new StoreMediaRepository.NewBinding(mediaId,
                        kind == StoreMediaKind.VIDEO ? "video/mp4" : "image/png", 2048L)),
                uploadedBy).collectList().block();
    }

    @Test
    @DisplayName("未登录 200：分组渲染 + 白名单断言（严禁 uploadedBy/organizationId/createdAt）")
    void publicMediaReadableWithoutLoginAndWhitelisted() {
        var owner = seedAccount("public-media-owner@example.com");
        String orgId = createOrg(owner.cookie(), "公开媒体主体");
        String storeId = createStore(orgId, owner.cookie(), "公开媒体门店");
        String frontA = UUID.randomUUID().toString();
        String frontB = UUID.randomUUID().toString();
        String promo = UUID.randomUUID().toString();
        seedBinding(orgId, storeId, StoreMediaKind.STOREFRONT, frontA, owner.accountId());
        seedBinding(orgId, storeId, StoreMediaKind.STOREFRONT, frontB, owner.accountId());
        seedBinding(orgId, storeId, StoreMediaKind.VIDEO, promo, owner.accountId());

        // 未登录（无 cookie）也放行 —— 大厅浏览场景。
        var response = client().get().uri("/api/stores/" + storeId + SUFFIX)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.storeId").isEqualTo(storeId)
                .jsonPath("$.data.groups.storefront.length()").isEqualTo(2)
                .jsonPath("$.data.groups.storefront[0].mediaId").isEqualTo(frontA)
                .jsonPath("$.data.groups.storefront[0].position").isEqualTo(1)
                .jsonPath("$.data.groups.storefront[0].mimeType").isEqualTo("image/png")
                .jsonPath("$.data.groups.storefront[0].sizeBytes").isEqualTo(1024)
                .jsonPath("$.data.groups.storefront[0].downloadUrl")
                .isEqualTo("https://cdn.example.com/store-media/" + frontA)
                .jsonPath("$.data.groups.storefront[0].urlExpiresAt").isNotEmpty()
                .jsonPath("$.data.groups.storefront[1].mediaId").isEqualTo(frontB)
                .jsonPath("$.data.groups.environment.length()").isEqualTo(0)
                .jsonPath("$.data.groups.menu.length()").isEqualTo(0)
                .jsonPath("$.data.groups.video.length()").isEqualTo(1)
                .jsonPath("$.data.groups.video[0].mediaId").isEqualTo(promo)
                // 白名单守卫：上传者/组织/创建时间严禁外泄。
                .jsonPath("$.data.groups.storefront[0].uploadedByAccountId").doesNotExist()
                .jsonPath("$.data.groups.storefront[0].uploadedBy").doesNotExist()
                .jsonPath("$.data.groups.storefront[0].organizationId").doesNotExist()
                .jsonPath("$.data.groups.storefront[0].createdAt").doesNotExist()
                .jsonPath("$.data.groups.video[0].uploadedByAccountId").doesNotExist()
                .returnResult();
        String body = new String(response.getResponseBody());
        assertThat(body).doesNotContain("uploadedBy").doesNotContain("organizationId")
                .doesNotContain("createdAt");

        // 登录用户同样可读。
        var viewer = seedAccount("public-media-viewer@example.com");
        client().get().uri("/api/stores/" + storeId + SUFFIX)
                .header("Cookie", "y1.sid=" + viewer.cookie())
                .exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("无绑定 → 200 groups 全空数组（不 404，公开页仍要渲染资料面板）")
    void emptyGroupsWhenNoBindings() {
        var owner = seedAccount("public-media-empty@example.com");
        String orgId = createOrg(owner.cookie(), "空媒体主体");
        String storeId = createStore(orgId, owner.cookie(), "空媒体门店");

        client().get().uri("/api/stores/" + storeId + SUFFIX)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.storeId").isEqualTo(storeId)
                .jsonPath("$.data.groups.storefront.length()").isEqualTo(0)
                .jsonPath("$.data.groups.environment.length()").isEqualTo(0)
                .jsonPath("$.data.groups.menu.length()").isEqualTo(0)
                .jsonPath("$.data.groups.video.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("gate：门店停用/组织 suspended/未知或非 UUID → 404（有绑定也不放行）")
    void gateRejectsInactiveOrUnknownStores() {
        var owner = seedAccount("public-media-404@example.com");
        String orgId = createOrg(owner.cookie(), "公开媒体404主体");

        String inactiveStore = createStore(orgId, owner.cookie(), "停用媒体门店");
        seedBinding(orgId, inactiveStore, StoreMediaKind.STOREFRONT,
                UUID.randomUUID().toString(), owner.accountId());
        db.sql("UPDATE store SET status = 'inactive' WHERE id = CAST(:id AS uuid)")
                .bind("id", inactiveStore).then().block();
        client().get().uri("/api/stores/" + inactiveStore + SUFFIX)
                .exchange().expectStatus().isNotFound();
        db.sql("UPDATE store SET status = 'active' WHERE id = CAST(:id AS uuid)")
                .bind("id", inactiveStore).then().block();

        String suspendedOrgStore = createStore(orgId, owner.cookie(), "组织停用媒体门店");
        seedBinding(orgId, suspendedOrgStore, StoreMediaKind.STOREFRONT,
                UUID.randomUUID().toString(), owner.accountId());
        db.sql("UPDATE organization SET status = 'suspended' WHERE id = CAST(:id AS uuid)")
                .bind("id", orgId).then().block();
        client().get().uri("/api/stores/" + suspendedOrgStore + SUFFIX)
                .exchange().expectStatus().isNotFound();
        db.sql("UPDATE organization SET status = 'active' WHERE id = CAST(:id AS uuid)")
                .bind("id", orgId).then().block();

        client().get().uri("/api/stores/" + UUID.randomUUID() + SUFFIX)
                .exchange().expectStatus().isNotFound();
        client().get().uri("/api/stores/not-a-uuid" + SUFFIX)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("上游 503 透传，且 public-profile 不受影响（D4 解耦验证）")
    void upstreamFailureMaps503WhilePublicProfileStaysReadable() {
        var owner = seedAccount("public-media-503@example.com");
        String orgId = createOrg(owner.cookie(), "上游故障主体");
        String storeId = createStore(orgId, owner.cookie(), "上游故障门店");
        seedBinding(orgId, storeId, StoreMediaKind.STOREFRONT,
                UUID.randomUUID().toString(), owner.accountId());
        // 造一份公开资料行，验证故障注入后 public-profile 仍 200。
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("address", "{\"address\":\"南京西路 1 号\"}",
                        "categories", List.of("火锅")))
                .exchange().expectStatus().isOk();

        when(storeMediaClient.downloadUrls(eq(orgId), eq(storeId), any()))
                .thenReturn(Mono.error(new IdentityException(503, "门店媒体服务暂不可用")));

        client().get().uri("/api/stores/" + storeId + SUFFIX)
                .exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("门店媒体服务暂不可用");
        // public-profile 零外部调用、零扇出（D4）：媒体上游故障不打挂资料面板。
        client().get().uri("/api/stores/" + storeId + "/public-profile")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.storeId").isEqualTo(storeId);
    }

    @Test
    @DisplayName("被滤项静默跳过：上游子集语义下，缺席媒体不出现在公开响应")
    void filteredItemsAreSkippedSilently() {
        var owner = seedAccount("public-media-filter@example.com");
        String orgId = createOrg(owner.cookie(), "过滤主体");
        String storeId = createStore(orgId, owner.cookie(), "过滤门店");
        String alive = UUID.randomUUID().toString();
        String filtered = UUID.randomUUID().toString();
        seedBinding(orgId, storeId, StoreMediaKind.STOREFRONT, alive, owner.accountId());
        seedBinding(orgId, storeId, StoreMediaKind.STOREFRONT, filtered, owner.accountId());

        // 上游四重过滤后被删/失效项缺席（子集语义）。
        Map<String, ResolvedMedia> subset = new LinkedHashMap<>();
        subset.put(alive, new ResolvedMedia("image/png", 4096L,
                "https://cdn.example.com/store-media/" + alive,
                Instant.parse("2026-08-20T00:00:00Z")));
        when(storeMediaClient.downloadUrls(eq(orgId), eq(storeId), anyList()))
                .thenReturn(Mono.just(subset));

        client().get().uri("/api/stores/" + storeId + SUFFIX)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.groups.storefront.length()").isEqualTo(1)
                .jsonPath("$.data.groups.storefront[0].mediaId").isEqualTo(alive)
                .jsonPath("$.data.groups.storefront[0].sizeBytes").isEqualTo(4096)
                .jsonPath("$.data.groups.storefront[0].urlExpiresAt")
                .isEqualTo("2026-08-20T00:00:00Z");
    }
}
