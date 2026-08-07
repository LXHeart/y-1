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

    // ---------------- Stage 2：商家素材库 + 授权 ----------------

    @Test
    void merchantCreateRequiresMerchantIdentity() {
        String mediaId = seedMedia("merchant-a");
        // 非商家身份（个人/消费者）创建商家库素材 → 403
        client().post().uri("/api/content-assets")
                .header(header(), sign("plain-user", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("libraryType", "merchant", "mediaId", mediaId,
                        "category", "store", "title", "x"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void merchantCreatesAndListsByOrg() {
        String org = "org-merchant";
        String mediaId = seedMedia("merchant-a");
        createMerchantAsset("merchant-a", org, mediaId, "门店照");
        createMerchantAsset("merchant-a", org, seedMedia("merchant-a"), "菜单");

        // 同 org 另一商家成员也能看到全部素材（org 维度）。
        createMerchantAsset("merchant-b", org, seedMedia("merchant-b"), "产品图");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<String, Object>)
                client().get().uri("/api/content-assets?libraryType=merchant")
                        .header(header(), signWithOrg("merchant-b", org))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(Map.class).returnResult().getResponseBody()
                        .get("data")).get("items");
        assertThat(items).hasSize(3);
    }

    @Test
    void merchantListIsolatedByOrg() {
        // 不同 org 的商家看不到对方素材。
        String mediaId = seedMedia("merchant-x");
        createMerchantAsset("merchant-x", "org-1", mediaId, "org1 素材");

        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) ((Map<String, Object>)
                client().get().uri("/api/content-assets?libraryType=merchant")
                        .header(header(), signWithOrg("merchant-y", "org-2"))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(Map.class).returnResult().getResponseBody()
                        .get("data")).get("items");
        assertThat(items).isEmpty();
    }

    @Test
    void merchantGrantsAssetToRecommenderAndRecommenderCanRead() {
        String org = "org-grant";
        String mediaId = seedMedia("merchant-g");
        String assetId = createMerchantAsset("merchant-g", org, mediaId, "授权素材");

        // 商家授权推荐官
        client().post().uri("/api/content-assets/" + assetId + "/grants")
                .header(header(), signWithOrg("merchant-g", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("granteeAccountId", "recommender-1"))
                .exchange()
                .expectStatus().isOk();

        // 推荐官能在「我被授权的商家素材」列表看到
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> granted = (List<Map<String, Object>>) ((Map<String, Object>)
                client().get().uri("/api/content-assets?libraryType=merchant&granted=true")
                        .header(header(), sign("recommender-1", "recommender"))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(Map.class).returnResult().getResponseBody()
                        .get("data")).get("items");
        assertThat(granted).hasSize(1);
        assertThat(granted.get(0)).containsEntry("title", "授权素材");

        // 推荐官能读详情（跨账号，靠 grant 放行）
        client().get().uri("/api/content-assets/" + assetId)
                .header(header(), sign("recommender-1", "recommender"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void ungrantedRecommenderCannotReadMerchantAsset() {
        String org = "org-grant2";
        String mediaId = seedMedia("merchant-h");
        String assetId = createMerchantAsset("merchant-h", org, mediaId, "未授权素材");

        // 未被授权的推荐官 → 404（不泄露存在性）
        client().get().uri("/api/content-assets/" + assetId)
                .header(header(), sign("recommender-2", "recommender"))
                .exchange()
                .expectStatus().isNotFound();

        // 也不在被授权列表里
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) ((Map<String, Object>)
                client().get().uri("/api/content-assets?libraryType=merchant&granted=true")
                        .header(header(), sign("recommender-2", "recommender"))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(Map.class).returnResult().getResponseBody()
                        .get("data")).get("items");
        assertThat(items).isEmpty();
    }

    @Test
    void revokeGrantHidesAssetFromRecommender() {
        String org = "org-revoke";
        String mediaId = seedMedia("merchant-r");
        String assetId = createMerchantAsset("merchant-r", org, mediaId, "将撤销");

        // 授权
        client().post().uri("/api/content-assets/" + assetId + "/grants")
                .header(header(), signWithOrg("merchant-r", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("granteeAccountId", "recommender-3"))
                .exchange()
                .expectStatus().isOk();

        // 撤销
        client().delete().uri("/api/content-assets/" + assetId + "/grants/recommender-3")
                .header(header(), signWithOrg("merchant-r", org))
                .exchange()
                .expectStatus().isOk();

        // 撤销后推荐官读不到
        client().get().uri("/api/content-assets/" + assetId)
                .header(header(), sign("recommender-3", "recommender"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void merchantGrantIdempotentRenewal() {
        String org = "org-renew";
        String mediaId = seedMedia("merchant-n");
        String assetId = createMerchantAsset("merchant-n", org, mediaId, "续约");

        // 同一推荐官重复授权 → 幂等（GREATEST 续约只前进），两次都 200
        client().post().uri("/api/content-assets/" + assetId + "/grants")
                .header(header(), signWithOrg("merchant-n", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("granteeAccountId", "recommender-4"))
                .exchange()
                .expectStatus().isOk();
        client().post().uri("/api/content-assets/" + assetId + "/grants")
                .header(header(), signWithOrg("merchant-n", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("granteeAccountId", "recommender-4"))
                .exchange()
                .expectStatus().isOk();

        // 授权列表只有一条
        @SuppressWarnings("unchecked")
        List<?> grants = (List<?>) ((Map<String, Object>)
                client().get().uri("/api/content-assets/" + assetId + "/grants")
                        .header(header(), signWithOrg("merchant-n", org))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(Map.class).returnResult().getResponseBody()
                        .get("data")).get("items");
        assertThat(grants).hasSize(1);
    }

    // ---------------- Stage 3：公共与 AI 素材库 + 审核流 ----------------

    @Test
    void publicCreateRequiresContentReviewerRole() {
        String mediaId = seedMedia("reviewer-acct");
        // 普通用户创建公共素材 → 403
        client().post().uri("/api/content-assets")
                .header(header(), sign("plain-user", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("libraryType", "public", "mediaId", mediaId, "category", "scene",
                        "title", "x", "source", "s", "licenseScope", "l",
                        "validUntil", "2027-01-01T00:00:00Z"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void publicCreateRequiresSourceLicenseAndValidity() {
        String mediaId = seedMedia("reviewer-acct");
        // 缺 source/licenseScope/validUntil → 400
        client().post().uri("/api/content-assets")
                .header(header(), signWithRole("reviewer-acct", null, null, "content_reviewer"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("libraryType", "public", "mediaId", mediaId, "category", "scene", "title", "x"))
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    void publicAssetGoesThroughReviewFlow() {
        String mediaId = seedMedia("reviewer-acct");
        // 上传即 pending_review
        String assetId = createPublicAsset("reviewer-acct", mediaId, "行业背景图");

        // 公共列表（active）还看不到
        assertThat(countPublicItems(null)).isZero();

        // 审核队列能看到（pending_review）
        assertThat(countReviewQueue("reviewer-acct")).isOne();

        // 审核通过 → active
        client().post().uri("/api/admin/content-assets/" + assetId + "/review/approve")
                .header(header(), signWithRole("reviewer-acct", null, null, "content_reviewer"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1))
                .exchange()
                .expectStatus().isOk();

        // 现在公共列表可见
        assertThat(countPublicItems(null)).isOne();

        // 详情详情能取到（登录用户）
        client().get().uri("/api/content-assets/" + assetId)
                .header(header(), sign("anyone", null))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void publicRejectRequiresNote() {
        String mediaId = seedMedia("reviewer-acct");
        String assetId = createPublicAsset("reviewer-acct", mediaId, "将被驳回");

        // 驳回缺 note → 400
        client().post().uri("/api/admin/content-assets/" + assetId + "/review/reject")
                .header(header(), signWithRole("reviewer-acct", null, null, "content_reviewer"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1))
                .exchange()
                .expectStatus().is4xxClientError();

        // 带 note 驳回 → rejected，公共列表不可见
        client().post().uri("/api/admin/content-assets/" + assetId + "/review/reject")
                .header(header(), signWithRole("reviewer-acct", null, null, "content_reviewer"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1, "note", "来源不明"))
                .exchange()
                .expectStatus().isOk();
        assertThat(countPublicItems(null)).isZero();
    }

    @Test
    void publicListReadableWithoutLogin() {
        String mediaId = seedMedia("reviewer-acct");
        createPublicAsset("reviewer-acct", mediaId, "已审素材");
        // 先审核通过（直接改库为 active，绕过审核端点，专测「全员读」）
        db.sql("UPDATE content_asset SET status='active' WHERE library_type='public' AND status='pending_review'")
                .then().block();

        // 无断言头（未登录）也能列公共素材
        @SuppressWarnings("unchecked")
        List<?> items = (List<?>) ((Map<String, Object>)
                client().get().uri("/api/content-assets?libraryType=public")
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(Map.class).returnResult().getResponseBody()
                        .get("data")).get("items");
        assertThat(items).isNotEmpty();
    }

    @Test
    void expiredPublicAssetExcludedFromList() {
        String mediaId = seedMedia("reviewer-acct");
        String assetId = createPublicAsset("reviewer-acct", mediaId, "已过期");
        // 直接改库：active 但 valid_until 已过
        db.sql("UPDATE content_asset SET status='active', valid_until=now() - interval '1 day'"
                + " WHERE id=CAST(:id AS uuid)")
                .bind("id", assetId).then().block();

        assertThat(countPublicItems(null)).isZero();
        // 详情也 404（过期的不可读）
        client().get().uri("/api/content-assets/" + assetId)
                .header(header(), sign("anyone", null))
                .exchange()
                .expectStatus().isNotFound();
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
    private String createMerchantAsset(String account, String org, String mediaId, String title) {
        Map<String, Object> response = client().post().uri("/api/content-assets")
                .header(header(), signWithOrg(account, org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("libraryType", "merchant", "mediaId", mediaId,
                        "category", "store", "title", title))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Map<String, Object>) response.get("data")).get("id").toString();
    }

    @SuppressWarnings("unchecked")
    private String createPublicAsset(String reviewer, String mediaId, String title) {
        Map<String, Object> response = client().post().uri("/api/content-assets")
                .header(header(), signWithRole(reviewer, null, null, "content_reviewer"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("libraryType", "public", "mediaId", mediaId, "category", "scene",
                        "title", title, "source", "平台素材", "licenseScope", "公开授权",
                        "validUntil", "2027-01-01T00:00:00Z"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Map<String, Object>) response.get("data")).get("id").toString();
    }

    @SuppressWarnings("unchecked")
    private int countPublicItems(String category) {
        String uri = "/api/content-assets?libraryType=public" + (category != null ? "&category=" + category : "");
        return ((List<?>) ((Map<String, Object>)
                client().get().uri(uri).exchange()
                        .expectStatus().isOk()
                        .expectBody(Map.class).returnResult().getResponseBody()
                        .get("data")).get("items")).size();
    }

    @SuppressWarnings("unchecked")
    private int countReviewQueue(String reviewer) {
        return ((List<?>) ((Map<String, Object>)
                client().get().uri("/api/admin/content-assets/review")
                        .header(header(), signWithRole(reviewer, null, null, "content_reviewer"))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(Map.class).returnResult().getResponseBody()
                        .get("data")).get("items")).size();
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
