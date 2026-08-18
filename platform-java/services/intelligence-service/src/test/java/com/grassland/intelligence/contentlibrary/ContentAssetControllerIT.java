package com.grassland.intelligence.contentlibrary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.creationcontext.MarketplaceCreationContextClient;
import com.grassland.intelligence.security.IdentityOrgAuthorizationClient;
import com.grassland.intelligence.security.IdentityStoreAuthorizationClient;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

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
@SuppressWarnings("unchecked")
class ContentAssetControllerIT extends IntelligenceItSupport {

    @MockitoBean
    IdentityStoreAuthorizationClient storeAuthorization;

    @MockitoBean
    IdentityOrgAuthorizationClient orgAuthorization;

    @MockitoBean
    MarketplaceCreationContextClient marketplaceCreationContext;

    /** 组织级商家素材的管理路径按 org ADMIN/OWNER 放行（identity 权威判定在本 IT 中打桩）。 */
    private void allowOrgAdmin(String account, String org) {
        when(orgAuthorization.require(account, org, "admin")).thenReturn(Mono.empty());
    }

    /** 组织级商家素材的管理路径对 org member 拒绝。 */
    private void denyOrgAdmin(String account, String org) {
        when(orgAuthorization.require(account, org, "admin"))
                .thenReturn(Mono.error(new IntelligenceException(403, "组织权限不足")));
    }

    @BeforeEach
    void cleanContentAssets() {
        db.sql("DELETE FROM content_asset_embedding").then().block();
        db.sql("DELETE FROM content_asset_grant").then().block();
        db.sql("DELETE FROM content_asset_version").then().block();
        db.sql("DELETE FROM content_asset").then().block();
    }

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
                .expectStatus().isEqualTo(409)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error").isEqualTo("素材已被他人修改，请刷新后重试");
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

    // ---- 任务书 #33：素材 CRUD 与 Embedding 索引意图的事务一致性 ----

    /** 查询某素材的全部索引行（status/version/hash），供钩子断言。 */
    private List<Map<String, Object>> embeddingRows(String assetId) {
        return db.sql("""
                        SELECT status, asset_version, content_hash
                        FROM content_asset_embedding WHERE asset_id = CAST(:id AS uuid)
                        ORDER BY asset_version
                        """)
                .bind("id", assetId)
                .map(row -> Map.<String, Object>of(
                        "status", row.get("status", String.class),
                        "asset_version", row.get("asset_version", Integer.class),
                        "content_hash", row.get("content_hash", String.class)))
                .all().collectList().block();
    }

    @Test
    void createActiveAssetEnqueuesEmbeddingIntentInSameTransaction() {
        String mediaId = seedMedia("user-idx");
        String assetId = createPersonalAsset("user-idx", mediaId, "开业海报", "campaign", List.of("咖啡"));

        List<Map<String, Object>> rows = embeddingRows(assetId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("asset_version", 1).containsEntry("status", "pending");
        assertThat(rows.get(0).get("content_hash").toString()).hasSize(64);
    }

    @Test
    void editEnqueuesNewVersionAndStalesOldIntent() {
        String mediaId = seedMedia("user-idx2");
        String assetId = createAsset("user-idx2", mediaId, "旧标题");

        client().put().uri("/api/content-assets/" + assetId)
                .header(header(), sign("user-idx2", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1, "category", "store", "title", "新标题",
                        "tags", List.of("新标签")))
                .exchange()
                .expectStatus().isOk();

        List<Map<String, Object>> rows = embeddingRows(assetId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("asset_version", 1).containsEntry("status", "stale");
        assertThat(rows.get(1)).containsEntry("asset_version", 2).containsEntry("status", "pending");
        assertThat(rows.get(0).get("content_hash")).isNotEqualTo(rows.get(1).get("content_hash"));
    }

    @Test
    void deleteStalesCurrentEmbeddingIntent() {
        String mediaId = seedMedia("user-idx3");
        String assetId = createAsset("user-idx3", mediaId, "待删索引");

        client().delete().uri("/api/content-assets/" + assetId)
                .header(header(), sign("user-idx3", null))
                .exchange()
                .expectStatus().isOk();

        List<Map<String, Object>> rows = embeddingRows(assetId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("status", "stale");
    }

    @Test
    void publicReviewControlsEmbeddingIntentByStatus() {
        String mediaId = seedMedia("reviewer-idx");
        // pending_review：不建索引意图。
        String approvedId = createPublicAsset("reviewer-idx", mediaId, "公共素材A");
        assertThat(embeddingRows(approvedId)).isEmpty();

        // 审核通过（version 1→2 active）→ 入队 v2。
        client().post().uri("/api/admin/content-assets/" + approvedId + "/review/approve")
                .header(header(), signWithRole("reviewer-idx", null, null, "content_reviewer"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1))
                .exchange()
                .expectStatus().isOk();
        List<Map<String, Object>> approvedRows = embeddingRows(approvedId);
        assertThat(approvedRows).hasSize(1);
        assertThat(approvedRows.get(0)).containsEntry("asset_version", 2).containsEntry("status", "pending");

        // 驳回路径：同样不产生索引意图。
        String rejectedId = createPublicAsset("reviewer-idx", mediaId, "公共素材B");
        client().post().uri("/api/admin/content-assets/" + rejectedId + "/review/reject")
                .header(header(), signWithRole("reviewer-idx", null, null, "content_reviewer"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1, "note", "来源不明"))
                .exchange()
                .expectStatus().isOk();
        assertThat(embeddingRows(rejectedId)).isEmpty();
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
    void storeScopedMerchantAssetsArePersistedAndIsolated() {
        String org = "org-store-assets";
        String manager = "merchant-store-manager";
        String storeA = UUID.randomUUID().toString();
        String storeB = UUID.randomUUID().toString();
        when(storeAuthorization.authorize(manager, org, storeA, "manager"))
                .thenReturn(Mono.just(storeAccess(manager, org, storeA, "manager")));
        when(storeAuthorization.authorize(manager, org, storeB, "manager"))
                .thenReturn(Mono.just(storeAccess(manager, org, storeB, "manager")));
        when(storeAuthorization.require(manager, org, storeA, "manager")).thenReturn(Mono.empty());

        String assetA = createStoreAsset(manager, org, storeA, seedMedia(manager), "A店素材");
        createStoreAsset(manager, org, storeB, seedMedia(manager), "B店素材");
        String persistedStore = db.sql("SELECT store_id::text FROM content_asset WHERE id=CAST(:id AS uuid)")
                .bind("id", assetA).map(row -> row.get(0, String.class)).one().block();
        assertThat(persistedStore).isEqualTo(storeA);

        client().put().uri("/api/content-assets/" + assetA)
                .header(header(), signWithOrg(manager, org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1, "category", "store", "title", "A店素材-新版本"))
                .exchange().expectStatus().isOk();
        String snapshotStore = db.sql("SELECT store_id::text FROM content_asset_version WHERE asset_id=CAST(:id AS uuid)")
                .bind("id", assetA).map(row -> row.get(0, String.class)).one().block();
        assertThat(snapshotStore).isEqualTo(storeA);

        when(storeAuthorization.authorize(manager, org, storeA, "staff"))
                .thenReturn(Mono.just(storeAccess(manager, org, storeA, "manager")));
        client().get().uri("/api/content-assets?libraryType=merchant&organizationId=" + org + "&storeId=" + storeA)
                .header(header(), signWithOrg(manager, org))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].storeId").isEqualTo(storeA)
                .jsonPath("$.data.items[0].title").isEqualTo("A店素材-新版本");

        // Organization-level merchant listing excludes store-scoped assets.
        client().get().uri("/api/content-assets?libraryType=merchant")
                .header(header(), signWithOrg(manager, org))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.items.length()").isEqualTo(0);

        when(storeAuthorization.require("merchant-store-staff", org, storeA, "manager"))
                .thenReturn(Mono.error(new IntelligenceException(403, "门店权限不足")));
        client().put().uri("/api/content-assets/" + assetA)
                .header(header(), signWithOrg("merchant-store-staff", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1, "category", "store", "title", "越权修改"))
                .exchange().expectStatus().isForbidden();
    }

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
    void independentStoreManagerCanCreateManageAndListStoreAssets() {
        String manager = "independent-store-manager";
        String org = "independent-store-org";
        String store = UUID.randomUUID().toString();
        when(storeAuthorization.authorize(manager, org, store, "manager"))
                .thenReturn(Mono.just(storeAccess(manager, org, store, "manager")));
        when(storeAuthorization.authorize(manager, org, store, "staff"))
                .thenReturn(Mono.just(storeAccess(manager, org, store, "manager")));
        when(storeAuthorization.require(manager, org, store, "manager")).thenReturn(Mono.empty());

        String assetId = createStoreAsset(manager, org, store, seedMedia(manager), "独立店长素材", false);

        client().put().uri("/api/content-assets/" + assetId)
                .header(header(), sign(manager, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1, "category", "store", "title", "店长已更新"))
                .exchange().expectStatus().isOk();

        client().get().uri("/api/content-assets?libraryType=merchant&organizationId="
                        + org + "&storeId=" + store)
                .header(header(), sign(manager, null))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].title").isEqualTo("店长已更新");
    }

    @Test
    void merchantCreatesAndListsByOrg() {
        String org = "org-merchant";
        allowOrgAdmin("merchant-a", org);
        allowOrgAdmin("merchant-b", org);
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
    void orgMemberCannotManageOrgLevelAssetsButCanRead() {
        // org admin/member 粒度：组织级素材管理要求 org ADMIN/OWNER；member 只读。
        String org = "org-granularity";
        String admin = "org-admin";
        String member = "org-member";
        allowOrgAdmin(admin, org);
        denyOrgAdmin(member, org);

        // member 创建组织级素材 → 403
        client().post().uri("/api/content-assets")
                .header(header(), signWithOrg(member, org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("libraryType", "merchant", "mediaId", seedMedia(member),
                        "category", "store", "title", "成员新建"))
                .exchange().expectStatus().isForbidden();

        // admin 创建后，member 能读（列表 + 详情），但编辑/删除/授权全部 403
        String assetId = createMerchantAsset(admin, org, seedMedia(admin), "管理员素材");
        client().get().uri("/api/content-assets?libraryType=merchant")
                .header(header(), signWithOrg(member, org))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.items.length()").isEqualTo(1);
        client().get().uri("/api/content-assets/" + assetId)
                .header(header(), signWithOrg(member, org))
                .exchange().expectStatus().isOk();
        client().put().uri("/api/content-assets/" + assetId)
                .header(header(), signWithOrg(member, org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1, "category", "store", "title", "成员越权编辑"))
                .exchange().expectStatus().isForbidden();
        client().delete().uri("/api/content-assets/" + assetId)
                .header(header(), signWithOrg(member, org))
                .exchange().expectStatus().isForbidden();
        client().post().uri("/api/content-assets/" + assetId + "/grants")
                .header(header(), signWithOrg(member, org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("granteeAccountId", "recommender-gran"))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/api/content-assets/" + assetId + "/grants")
                .header(header(), signWithOrg(member, org))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void orgLevelManageFailsClosedWhenIdentityUnavailable() {
        // identity 不可达（5xx）不降级放行——组织级管理 fail-closed。
        String org = "org-failclosed";
        when(orgAuthorization.require("merchant-fc", org, "admin"))
                .thenReturn(Mono.error(new IllegalStateException("identity down")));
        client().post().uri("/api/content-assets")
                .header(header(), signWithOrg("merchant-fc", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("libraryType", "merchant", "mediaId", seedMedia("merchant-fc"),
                        "category", "store", "title", "x"))
                .exchange().expectStatus().is5xxServerError();
    }

    @Test
    void merchantListIsolatedByOrg() {
        // 不同 org 的商家看不到对方素材。
        allowOrgAdmin("merchant-x", "org-1");
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
        allowOrgAdmin("merchant-g", org);
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
        allowOrgAdmin("merchant-h", org);
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
        allowOrgAdmin("merchant-r", org);
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
        allowOrgAdmin("merchant-n", org);
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

    // ---------------- 智能素材推荐（PRD §4.8「按任务和平台智能推荐」） ----------------

    @Test
    void standaloneRecommendationsRankAccessibleAssetsWithReasons() {
        String user = "rec-user";
        String matched = createPersonalAsset(user, seedMedia(user), "开业招牌海报", "store",
                List.of("开业", "招牌"));
        String unmatched = createPersonalAsset(user, seedMedia(user), "日常随手拍", "other", List.of());
        // 他人个人素材：即使关键词命中也不入池（推荐只重排不越权）。
        String stranger = createPersonalAsset("rec-stranger", seedMedia("rec-stranger"),
                "开业探店素材", "store", List.of("开业"));
        // 公共素材：审核通过后入池。
        String publicAsset = createPublicAsset("reviewer-acct", seedMedia("reviewer-acct"), "开业气氛公共背景");
        db.sql("UPDATE content_asset SET status='active' WHERE id=CAST(:id AS uuid)")
                .bind("id", publicAsset).then().block();

        Map<String, Object> response = client().get()
                .uri("/api/content-assets/recommendations?keywords=开业,招牌&contentForm=graphic"
                        + "&platform=xiaohongshu&category=store")
                .header(header(), sign(user, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) ((Map<String, Object>) response.get("data")).get("items");
        assertThat(items).isNotEmpty();
        List<String> ids = items.stream().map(item -> item.get("id").toString()).toList();
        assertThat(ids).contains(matched, publicAsset, unmatched);
        assertThat(ids).doesNotContain(stranger);
        // 关键词命中的个人素材排最前，分数非增，带解释。
        assertThat(ids.get(0)).isEqualTo(matched);
        assertThat((List<String>) items.get(0).get("reasons")).contains("个人素材");
        int previous = Integer.MAX_VALUE;
        for (Map<String, Object> item : items) {
            int score = ((Number) item.get("score")).intValue();
            assertThat(score).isLessThanOrEqualTo(previous);
            previous = score;
        }
        Map<String, Object> query = (Map<String, Object>) ((Map<String, Object>) response.get("data")).get("query");
        assertThat(query.get("platform")).isEqualTo("xiaohongshu");
        assertThat((List<String>) query.get("terms")).contains("开业", "招牌");
    }

    @Test
    void recommendationsIncludeGrantedMerchantAssetsForRecommenderOnly() {
        String org = "org-rec-grant";
        allowOrgAdmin("merchant-rec", org);
        String granted = createMerchantAsset("merchant-rec", org, seedMedia("merchant-rec"), "授权的招牌素材");
        String ungranted = createMerchantAsset("merchant-rec", org, seedMedia("merchant-rec"), "未授权素材");
        client().post().uri("/api/content-assets/" + granted + "/grants")
                .header(header(), signWithOrg("merchant-rec", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("granteeAccountId", "recommender-rec"))
                .exchange().expectStatus().isOk();

        // 被授权推荐官：授权素材带「商家授权素材」理由入池；未授权素材绝不出现。
        Map<String, Object> response = client().get()
                .uri("/api/content-assets/recommendations?keywords=招牌")
                .header(header(), sign("recommender-rec", "recommender"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) ((Map<String, Object>) response.get("data")).get("items");
        List<String> ids = items.stream().map(item -> item.get("id").toString()).toList();
        assertThat(ids).contains(granted).doesNotContain(ungranted);
        Map<String, Object> grantedItem = items.stream()
                .filter(item -> item.get("id").toString().equals(granted)).findFirst().orElseThrow();
        assertThat((List<String>) grantedItem.get("reasons")).contains("商家授权素材");

        // 未被授权的推荐官看不到任何商家素材。
        List<Map<String, Object>> otherItems = (List<Map<String, Object>>) ((Map<String, Object>)
                client().get().uri("/api/content-assets/recommendations?keywords=招牌")
                        .header(header(), sign("recommender-other", "recommender"))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(Map.class).returnResult().getResponseBody()
                        .get("data")).get("items");
        assertThat(otherItems.stream().map(item -> item.get("id").toString()).toList())
                .doesNotContain(granted, ungranted);

        // 商家本人（merchant 身份）：本组织素材以「本组织素材」入池。
        List<Map<String, Object>> merchantItems = (List<Map<String, Object>>) ((Map<String, Object>)
                client().get().uri("/api/content-assets/recommendations?keywords=素材")
                        .header(header(), signWithOrg("merchant-rec", org))
                        .exchange()
                        .expectStatus().isOk()
                        .expectBody(Map.class).returnResult().getResponseBody()
                        .get("data")).get("items");
        Map<String, Object> ownItem = merchantItems.stream()
                .filter(item -> item.get("id").toString().equals(ungranted)).findFirst().orElseThrow();
        assertThat((List<String>) ownItem.get("reasons")).contains("本组织素材");
    }

    @Test
    void taskModeRecommendationsUseAuthoritativeTaskContext() {
        String recommender = "recommender-task";
        String applicationId = UUID.randomUUID().toString();
        String taskId = UUID.randomUUID().toString();
        String matching = createPersonalAsset(recommender, seedMedia(recommender), "新店开业探店vlog",
                "store", List.of("新店", "开业"));
        createPersonalAsset(recommender, seedMedia(recommender), "无关日常素材", "other", List.of());
        Map<String, Object> taskContext = Map.of(
                "taskId", taskId,
                "title", "新店开业探店图文",
                "description", "突出新店开业气氛与招牌菜",
                "contentForm", "graphic",
                "platform", "xiaohongshu",
                "requirements", Map.of("tone", "开业喜庆", "mustInclude", List.of("新店开业")));
        when(marketplaceCreationContext.fetch(applicationId, taskId, recommender))
                .thenReturn(Mono.just(new MarketplaceCreationContextClient.AuthoritativeContext(
                        taskContext, "org-task", Map.of())));

        Map<String, Object> response = client().get()
                .uri("/api/content-assets/recommendations?applicationId=" + applicationId
                        + "&taskId=" + taskId)
                .header(header(), sign(recommender, "recommender"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");
        assertThat(items.get(0).get("id").toString()).isEqualTo(matching);
        assertThat(items.get(0).get("reasons")).isNotNull();
        assertThat(data.get("sourceTitle")).isEqualTo("新店开业探店图文");
        Map<String, Object> query = (Map<String, Object>) data.get("query");
        assertThat(query.get("platform")).isEqualTo("xiaohongshu");
        assertThat(query.get("contentForm")).isEqualTo("graphic");

        // 参与方不匹配（marketplace 权威 403）→ 原样透传，不降级为独立模式。
        when(marketplaceCreationContext.fetch(applicationId, taskId, "recommender-imposter"))
                .thenReturn(Mono.error(new IntelligenceException(403, "任务上下文参与方不匹配")));
        client().get().uri("/api/content-assets/recommendations?applicationId=" + applicationId
                        + "&taskId=" + taskId)
                .header(header(), sign("recommender-imposter", "recommender"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void recommendationsValidateParamsAndRequireLogin() {
        client().get().uri("/api/content-assets/recommendations")
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/content-assets/recommendations?category=bogus")
                .header(header(), sign("rec-plain", null))
                .exchange().expectStatus().isBadRequest();
        // 任务模式两个 ID 必须成对。
        client().get().uri("/api/content-assets/recommendations?applicationId="
                        + UUID.randomUUID())
                .header(header(), sign("rec-plain", null))
                .exchange().expectStatus().isBadRequest();
        // limit 收敛到 [1,50]。
        client().get().uri("/api/content-assets/recommendations?limit=999")
                .header(header(), sign("rec-plain", null))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.items.length()").isEqualTo(0);
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
    private String createPersonalAsset(
            String account, String mediaId, String title, String category, List<String> tags) {
        Map<String, Object> response = client().post().uri("/api/content-assets")
                .header(header(), sign(account, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mediaId", mediaId, "category", category, "title", title, "tags", tags))
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
    private String createStoreAsset(String account, String org, String storeId, String mediaId, String title) {
        return createStoreAsset(account, org, storeId, mediaId, title, true);
    }

    @SuppressWarnings("unchecked")
    private String createStoreAsset(
            String account, String org, String storeId, String mediaId, String title, boolean merchantIdentity) {
        Map<String, Object> response = client().post().uri("/api/content-assets")
                .header(header(), merchantIdentity ? signWithOrg(account, org) : sign(account, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("libraryType", "merchant", "mediaId", mediaId,
                        "category", "store", "title", title,
                        "organizationId", org, "storeId", storeId))
                .exchange().expectStatus().isOk()
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

    private IdentityStoreAuthorizationClient.Authorization storeAccess(
            String accountId, String organizationId, String storeId, String role) {
        return new IdentityStoreAuthorizationClient.Authorization(
                true, accountId, organizationId, storeId, role, "store", "basic_publish");
    }

    // ---- 组织级 legacy 素材批量门店迁移（Slice 14 收尾）----

    /** 放行 org admin + 目标门店 manager（org OWNER/ADMIN 对门店隐式 MANAGER 的常驻路径）。 */
    private void allowMigration(String account, String org, String storeId) {
        allowOrgAdmin(account, org);
        when(storeAuthorization.require(account, org, storeId, "manager")).thenReturn(Mono.empty());
    }

    @Test
    void orgAdminBatchMigratesLegacyAssetsToStoreWithSnapshotAndEvent() {
        String org = "org-mig";
        String store = UUID.randomUUID().toString();
        allowMigration("merchant-mig", org, store);
        String first = createMerchantAsset("merchant-mig", org, seedMedia("merchant-mig"), "门头照");
        String second = createMerchantAsset("merchant-mig", org, seedMedia("merchant-mig"), "菜单图");
        allowOrgAdmin("merchant-other", "org-other");
        String foreign = createMerchantAsset("merchant-other", "org-other", seedMedia("merchant-other"), "别家素材");
        String personal = createAsset("user-plain", seedMedia("user-plain"), "个人素材");
        String otherStore = UUID.randomUUID().toString();
        when(storeAuthorization.authorize("merchant-mig", org, otherStore, "manager"))
                .thenReturn(Mono.just(storeAccess("merchant-mig", org, otherStore, "manager")));
        String storeAsset = createStoreAsset("merchant-mig", org, otherStore,
                seedMedia("merchant-mig"), "已在门店");

        @SuppressWarnings("unchecked")
        Map<String, Object> response = client().post().uri("/api/content-assets/store-migration")
                .header(header(), signWithOrg("merchant-mig", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storeId", store, "assetIds", List.of(first, second, foreign, personal, storeAsset)))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> data = (Map<String, Object>) response.get("data");
        assertThat(((Number) data.get("moved")).longValue()).isEqualTo(2L);

        // 两份组织级素材已迁为门店素材（version+1，storeId 落定）——DB 直查（消费者身份读商家素材按授权口径 404）。
        java.util.List<Object[]> migratedRows = db.sql(
                        "SELECT store_id::text, version FROM content_asset WHERE id = CAST(:id AS uuid)")
                .bind("id", first)
                .map((row, meta) -> new Object[] { row.get(0, String.class), row.get(1, Integer.class) })
                .all().collectList().block();
        assertThat(migratedRows).hasSize(1);
        assertThat(migratedRows.get(0)[0]).isEqualTo(store);
        assertThat(migratedRows.get(0)[1]).isEqualTo(2);
        // 组织级形态留档：v1 快照 storeId 为空（「更新不覆盖历史快照」不变式）。
        Integer snapshottedStore = db.sql(
                        "SELECT (store_id IS NULL)::int FROM content_asset_version"
                                + " WHERE asset_id = CAST(:asset AS uuid) AND version = 1")
                .bind("asset", first)
                .map((row, meta) -> row.get(0, Integer.class)).one().block();
        assertThat(snapshottedStore).isEqualTo(1);
        // 非组织级项（别家 org/个人库/已在门店）一律 moved:false，且行未被改写。
        assertThat(getAsset(personal, "user-plain").get("storeId")).isNull();
        String storeAfterMigration = db.sql(
                        "SELECT store_id::text FROM content_asset WHERE id = CAST(:id AS uuid)")
                .bind("id", storeAsset)
                .map((row, meta) -> row.get(0, String.class)).one().block();
        assertThat(storeAfterMigration).isEqualTo(otherStore);
        // 迁移事件（无既有消费者，新类型安全）。
        Integer events = db.sql(
                        "SELECT COUNT(*) FROM intelligence_outbox WHERE event_type = 'ContentAssetStoreMigrated'")
                .map((row, meta) -> row.get(0, Integer.class)).one().block();
        assertThat(events).isEqualTo(2);
    }

    @Test
    void migrationIsIdempotentOnRetry() {
        String org = "org-mig-idem";
        String store = UUID.randomUUID().toString();
        allowMigration("merchant-idem", org, store);
        String asset = createMerchantAsset("merchant-idem", org, seedMedia("merchant-idem"), "重试素材");

        Map<String, Object> body = Map.of("storeId", store, "assetIds", List.of(asset));
        client().post().uri("/api/content-assets/store-migration")
                .header(header(), signWithOrg("merchant-idem", org))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.moved").isEqualTo(1);
        client().post().uri("/api/content-assets/store-migration")
                .header(header(), signWithOrg("merchant-idem", org))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.moved").isEqualTo(0);
        // 第二次不产生新版本（守卫挡住已迁移行）。
        Integer versionAfterRetry = db.sql(
                        "SELECT version FROM content_asset WHERE id = CAST(:id AS uuid)")
                .bind("id", asset)
                .map((row, meta) -> row.get(0, Integer.class)).one().block();
        assertThat(versionAfterRetry).isEqualTo(2);
    }

    @Test
    void migrationRequiresOrgAdminNotPlainMember() {
        String org = "org-mig-member";
        String store = UUID.randomUUID().toString();
        denyOrgAdmin("merchant-member", org);
        when(storeAuthorization.require("merchant-member", org, store, "manager")).thenReturn(Mono.empty());

        client().post().uri("/api/content-assets/store-migration")
                .header(header(), signWithOrg("merchant-member", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storeId", store, "assetIds", List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void pureStoreManagerCannotPullOrgAssetsIntoStore() {
        String org = "org-mig-storemgr";
        String store = UUID.randomUUID().toString();
        allowOrgAdmin("merchant-owner", org);
        String asset = createMerchantAsset("merchant-owner", org, seedMedia("merchant-owner"), "组织素材");
        // 纯门店经理：目标门店 MANAGER 放行，但源（组织级素材管理）无 org admin。
        denyOrgAdmin("manager-only", org);
        when(storeAuthorization.require("manager-only", org, store, "manager")).thenReturn(Mono.empty());

        client().post().uri("/api/content-assets/store-migration")
                .header(header(), signWithOrg("manager-only", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storeId", store, "assetIds", List.of(asset)))
                .exchange().expectStatus().isForbidden();
        Integer stillOrgLevel = db.sql(
                        "SELECT (store_id IS NULL)::int FROM content_asset WHERE id = CAST(:id AS uuid)")
                .bind("id", asset)
                .map((row, meta) -> row.get(0, Integer.class)).one().block();
        assertThat(stillOrgLevel).isEqualTo(1);
    }

    @Test
    void migrationRejectsCrossOrgTargetStoreAndMissingInput() {
        String org = "org-mig-cross";
        String crossStore = UUID.randomUUID().toString();
        allowOrgAdmin("merchant-cross", org);
        when(storeAuthorization.require("merchant-cross", org, crossStore, "manager"))
                .thenReturn(Mono.error(new IntelligenceException(404, "门店不存在")));

        client().post().uri("/api/content-assets/store-migration")
                .header(header(), signWithOrg("merchant-cross", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storeId", crossStore, "assetIds", List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isNotFound();
        client().post().uri("/api/content-assets/store-migration")
                .header(header(), signWithOrg("merchant-cross", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("assetIds", List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isBadRequest();
        client().post().uri("/api/content-assets/store-migration")
                .header(header(), signWithOrg("merchant-cross", org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("storeId", UUID.randomUUID().toString()))
                .exchange().expectStatus().isBadRequest();
    }
}
