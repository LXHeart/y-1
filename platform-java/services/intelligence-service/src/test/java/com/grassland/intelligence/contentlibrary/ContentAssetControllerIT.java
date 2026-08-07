package com.grassland.intelligence.contentlibrary;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 内容素材库个人库集成测试（草场 PRD §4.8 / Slice 14 Stage 1）。复用 {@link IntelligenceItSupport}
 *（testcontainers postgres + 真实断言签名，对象存储未启 → CRUD 可测、下载端点 503）。
 *
 * <p>media_reference 行直接用 {@code db} 插入（MediaController 未装配，无法走三步上传）——本 IT 只测素材
 * 业务层（挂接/CRUD/编辑快照/IDOR），三步上传本身由 {@code MediaControllerIT} 覆盖。
 *
 * <p>锁定：创建（IDOR 守卫 media owner）、列表（仅自己 active）、详情（跨账号 404）、编辑（落 version 快照 +
 * 乐观锁 409）、删除（软删后不可见/不可下钻）、下载（storage 未启 503）。
 */
class ContentAssetControllerIT extends IntelligenceItSupport {

    /** 直接插一行 active media_reference（绕过 MediaController 三步上传），返回 media id。 */
    @SuppressWarnings("unchecked")
    private String seedMedia(String ownerAccountId) {
        String mediaId = UUID.randomUUID().toString();
        String objectKey = "media/content_asset/" + mediaId;
        db.sql("""
                INSERT INTO media_reference (id, owner_account_id, purpose, object_key, mime_type,
                                             size_bytes, source, status)
                VALUES (CAST(:id AS uuid), :owner, 'content_asset', :key, 'image/png', 1024, 'upload', 'active')
                """)
                .bind("id", mediaId).bind("owner", ownerAccountId).bind("key", objectKey)
                .then().block();
        return mediaId;
    }

    @Test
    void createPersonalAssetLinksMediaAndReturnsId() {
        String mediaId = seedMedia("user-a");
        Map<String, Object> body = Map.of(
                "mediaId", mediaId, "category", "store", "title", "门店门头照",
                "tags", List.of("招牌", "外景"));

        Map<String, Object> response = client().post().uri("/api/content-assets")
                .header(header(), sign("user-a", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(response).containsEntry("success", true);
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(data).containsEntry("libraryType", "personal");
        assertThat(data).containsEntry("category", "store");
        assertThat(data).containsEntry("title", "门店门头照");
        assertThat(data.get("tags")).isEqualTo(List.of("招牌", "外景"));
        assertThat(data).containsEntry("version", 1);
        assertThat(data).containsKey("id");
    }

    @Test
    void createRejectsMediaOwnedByAnotherAccount() {
        // IDOR 守卫：media 属于 user-b，user-a 不能挂接到自己素材库。
        String mediaId = seedMedia("user-b");
        Map<String, Object> body = Map.of("mediaId", mediaId, "category", "product", "title", "盗用图");

        client().post().uri("/api/content-assets")
                .header(header(), sign("user-a", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void listReturnsOnlyOwnActiveAssets() {
        String mediaA1 = seedMedia("user-list");
        String mediaA2 = seedMedia("user-list");
        String mediaB = seedMedia("user-other");
        createAsset("user-list", mediaA1, "我的素材1");
        createAsset("user-list", mediaA2, "我的素材2");
        createAsset("user-other", mediaB, "别人的素材");

        Map<String, Object> response = client().get().uri("/api/content-assets")
                .header(header(), sign("user-list", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>) response.get("data")).get("items");
        assertThat(items).hasSize(2);
        assertThat(items).allSatisfy(item -> assertThat(item.get("title").toString()).startsWith("我的素材"));
    }

    @Test
    void getReturns404ForOtherAccountAsset() {
        String mediaId = seedMedia("user-owner");
        String assetId = createAsset("user-owner", mediaId, "owner 的素材");

        client().get().uri("/api/content-assets/" + assetId)
                .header(header(), sign("user-stranger", null))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void editAppendsVersionSnapshotAndIncrementsVersion() {
        String mediaId = seedMedia("user-edit");
        String assetId = createAsset("user-edit", mediaId, "原标题");

        // 编辑：title 改 + expectedVersion=1
        client().put().uri("/api/content-assets/" + assetId)
                .header(header(), sign("user-edit", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1, "category", "product", "title", "新标题",
                        "tags", List.of("改后")))
                .exchange()
                .expectStatus().isOk();

        // 详情应反映 v2 + 新标题
        Map<String, Object> detail = getAsset(assetId, "user-edit");
        assertThat(detail).containsEntry("version", 2);
        assertThat(detail).containsEntry("title", "新标题");

        // 历史快照应有 v1（原标题）
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> versions = (List<Map<String, Object>>) ((Map<String, Object>)
                client().get().uri("/api/content-assets/" + assetId + "/versions")
                        .header(header(), sign("user-edit", null))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(Map.class).returnResult().getResponseBody()
                        .get("data")).get("items");
        assertThat(versions).hasSize(1);
        assertThat(versions.get(0)).containsEntry("version", 1);
        assertThat(versions.get(0)).containsEntry("title", "原标题");
    }

    @Test
    void editRejectsStaleExpectedVersion() {
        String mediaId = seedMedia("user-lock");
        String assetId = createAsset("user-lock", mediaId, "锁测试");

        // 用错误的 expectedVersion=99 → 409
        client().put().uri("/api/content-assets/" + assetId)
                .header(header(), sign("user-lock", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 99, "category", "store", "title", "冲突"))
                .exchange()
                .expectStatus().is4xxClientError()
                .expectBody(Map.class).value(body -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) body.get("error");
                    assertThat(body.get("success")).isEqualTo(false);
                });
    }

    @Test
    void deleteSoftDeletesAndHidesFromList() {
        String mediaId = seedMedia("user-del");
        String assetId = createAsset("user-del", mediaId, "待删");

        client().delete().uri("/api/content-assets/" + assetId)
                .header(header(), sign("user-del", null))
                .exchange()
                .expectStatus().isOk();

        // 列表不再含该素材
        Map<String, Object> listResp = client().get().uri("/api/content-assets")
                .header(header(), sign("user-del", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) ((Map<String, Object>) listResp.get("data")).get("items");
        assertThat(items).isEmpty();

        // 详情 404（软删后不可下钻）
        client().get().uri("/api/content-assets/" + assetId)
                .header(header(), sign("user-del", null))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void downloadUrlReturns503WhenStorageDisabled() {
        String mediaId = seedMedia("user-dl");
        String assetId = createAsset("user-dl", mediaId, "下载测试");

        // 基座 object-storage.enabled=false → storage 不可用 → 503（非 500，非 NPE）。
        client().get().uri("/api/content-assets/" + assetId + "/download-url")
                .header(header(), sign("user-dl", null))
                .exchange()
                .expectStatus().is5xxServerError();
    }

    @Test
    void createRejectsInvalidCategory() {
        String mediaId = seedMedia("user-cat");
        Map<String, Object> body = Map.of("mediaId", mediaId, "category", "bogus", "title", "x");

        client().post().uri("/api/content-assets")
                .header(header(), sign("user-cat", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void requiresAuthentication() {
        client().get().uri("/api/content-assets")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---- 辅助 ----

    private String header() {
        return "X-Grassland-Identity";
    }

    @SuppressWarnings("unchecked")
    private String createAsset(String account, String mediaId, String title) {
        Map<String, Object> response = client().post().uri("/api/content-assets")
                .header(header(), sign(account, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mediaId", mediaId, "category", "store", "title", title))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Map<String, Object>) response.get("data")).get("id").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getAsset(String assetId, String account) {
        Map<String, Object> response = client().get().uri("/api/content-assets/" + assetId)
                .header(header(), sign(account, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) response.get("data");
    }
}
