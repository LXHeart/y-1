package com.grassland.identity.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.grassland.identity.IdentityItSupport;
import com.grassland.identity.auth.IdentityException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

/**
 * 门店媒体管理端点 IT（任务书 #42 Stage 2）。上游 intelligence 走 {@link StoreMediaClient} 替身
 * （基座默认全量命中），fail-closed/故障分支由 mock 返回值刻画。
 *
 * <p>覆盖：未登录 401、无门店角色 403（STAFF 只读）、跨组织 404、pending/它店 mediaId 绑定 400、
 * 第 7 张门头 409、开票 kind→MIME/帽前置校验、reorder 精确集合、解绑 D6。
 */
class StoreMediaControllerIT extends IdentityItSupport {

    private String uri(String orgId, String storeId) {
        return "/api/organizations/" + orgId + "/stores/" + storeId + "/media";
    }

    private void addOrgMember(String orgId, String accountId, String role) {
        db.sql("INSERT INTO organization_membership(id, organization_id, account_id, role) "
                        + "VALUES (gen_random_uuid(), CAST(:org AS uuid), CAST(:account AS uuid), :role)")
                .bind("org", orgId).bind("account", accountId).bind("role", role)
                .fetch().rowsUpdated().block();
    }

    private void addStoreStaff(String orgId, String storeId, String ownerCookie, String accountId) {
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + ownerCookie)
                .bodyValue("{\"accountId\":\"" + accountId + "\",\"role\":\"staff\"}")
                .exchange().expectStatus().isCreated();
    }

    private void addStoreManager(String orgId, String storeId, String ownerCookie, String accountId) {
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + ownerCookie)
                .bodyValue("{\"accountId\":\"" + accountId + "\",\"role\":\"manager\"}")
                .exchange().expectStatus().isCreated();
    }

    @Test
    @DisplayName("未登录：五端点全部 401（identity 无全局过滤链，端点显式鉴权兜底验证）")
    void unauthenticatedRequestsAre401() {
        var owner = seedAccount("sm-ctrl-401-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "未登录主体");
        String storeId = createStore(orgId, owner.cookie(), "未登录门店");
        String base = uri(orgId, storeId);

        client().post().uri(base + "/upload-tickets").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "storefront", "contentType", "image/png", "sizeBytes", 1024))
                .exchange().expectStatus().isUnauthorized();
        client().post().uri(base).contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "storefront", "mediaIds", List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isUnauthorized();
        client().get().uri(base).exchange().expectStatus().isUnauthorized();
        client().put().uri(base + "/order").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("kind", "storefront", "orderedMediaIds", List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isUnauthorized();
        client().delete().uri(base + "/" + UUID.randomUUID())
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("门店角色矩阵：STAFF 只读（GET 200、写 403）；无门店角色的 org MEMBER 读回落 200、写 403")
    void storeRoleMatrix() {
        var owner = seedAccount("sm-ctrl-role-owner-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "角色矩阵主体");
        String storeId = createStore(orgId, owner.cookie(), "角色矩阵门店");
        String base = uri(orgId, storeId);

        var staff = seedAccount("sm-ctrl-role-staff-" + UUID.randomUUID() + "@example.com");
        addStoreStaff(orgId, storeId, owner.cookie(), staff.accountId());
        client().get().uri(base).header("Cookie", "y1.sid=" + staff.cookie())
                .exchange().expectStatus().isOk();
        client().post().uri(base).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + staff.cookie())
                .bodyValue(Map.of("kind", "storefront", "mediaIds", List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("权限不足");
        client().post().uri(base + "/upload-tickets").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + staff.cookie())
                .bodyValue(Map.of("kind", "storefront", "contentType", "image/png", "sizeBytes", 1024))
                .exchange().expectStatus().isForbidden();

        // org MEMBER（无门店角色）：读回落放行，写 403。
        var member = seedAccount("sm-ctrl-role-member-" + UUID.randomUUID() + "@example.com");
        addOrgMember(orgId, member.accountId(), "member");
        client().get().uri(base).header("Cookie", "y1.sid=" + member.cookie())
                .exchange().expectStatus().isOk();
        client().post().uri(base).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + member.cookie())
                .bodyValue(Map.of("kind", "storefront", "mediaIds", List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isForbidden();

        // 无任何成员身份的旁观账号：读 403（无门店角色且非 org 成员）。
        var stranger = seedAccount("sm-ctrl-role-stranger-" + UUID.randomUUID() + "@example.com");
        client().get().uri(base).header("Cookie", "y1.sid=" + stranger.cookie())
                .exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("门店 MANAGER（非 org ADMIN）：四个写端点均 200，对其他组织门店仍 404（验收标准 1）")
    void storeManagerCanWriteAndCrossOrgStays404() {
        var owner = seedAccount("sm-ctrl-mgr-owner-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "MANAGER 主体");
        String storeId = createStore(orgId, owner.cookie(), "MANAGER 门店");
        String base = uri(orgId, storeId);

        var manager = seedAccount("sm-ctrl-mgr-account-" + UUID.randomUUID() + "@example.com");
        addStoreManager(orgId, storeId, owner.cookie(), manager.accountId());

        // POST /media/upload-tickets → 200（默认 stub 全命中）。
        client().post().uri(base + "/upload-tickets").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + manager.cookie())
                .bodyValue(Map.of("kind", "storefront", "contentType", "image/png", "sizeBytes", 1024))
                .exchange().expectStatus().isOk();

        // POST /media → 200 并回整组（uploadedBy=门店 MANAGER 本人）。
        String mediaId = UUID.randomUUID().toString();
        client().post().uri(base).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + manager.cookie())
                .bodyValue(Map.of("kind", "storefront", "mediaIds", List.of(mediaId)))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items[0].uploadedByAccountId").isEqualTo(manager.accountId());

        // PUT /media/order → 200。
        client().put().uri(base + "/order").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + manager.cookie())
                .bodyValue(Map.of("kind", "storefront", "orderedMediaIds", List.of(mediaId)))
                .exchange().expectStatus().isOk();

        // DELETE /media/{id} → 200。
        client().delete().uri(base + "/" + mediaId)
                .header("Cookie", "y1.sid=" + manager.cookie())
                .exchange().expectStatus().isOk();

        // 对其他组织门店仍 404：给 MANAGER 在 orgB 挂 org ADMIN（否则先在角色闸被 403，
        // 到不了跨组织闸），再用 orgB + storeId（实属首个 org）触发 ensureStoreInOrg → 404。
        var ownerB = seedAccount("sm-ctrl-mgr-ownerb-" + UUID.randomUUID() + "@example.com");
        String orgB = createOrg(ownerB.cookie(), "MANAGER 跨组织主体");
        addOrgMember(orgB, manager.accountId(), "admin");
        String foreign = uri(orgB, storeId);
        client().post().uri(foreign + "/upload-tickets").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + manager.cookie())
                .bodyValue(Map.of("kind", "storefront", "contentType", "image/png", "sizeBytes", 1024))
                .exchange().expectStatus().isNotFound();
        client().post().uri(foreign).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + manager.cookie())
                .bodyValue(Map.of("kind", "storefront", "mediaIds", List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isNotFound();
        client().put().uri(foreign + "/order").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + manager.cookie())
                .bodyValue(Map.of("kind", "storefront", "orderedMediaIds", List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isNotFound();
        client().delete().uri(foreign + "/" + UUID.randomUUID())
                .header("Cookie", "y1.sid=" + manager.cookie())
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("跨组织：他 org owner 对本 org 门店读/写/解绑统一 404")
    void crossOrgReturns404() {
        var ownerA = seedAccount("sm-ctrl-cross-a-" + UUID.randomUUID() + "@example.com");
        String orgA = createOrg(ownerA.cookie(), "跨组织主体A");
        String storeA = createStore(orgA, ownerA.cookie(), "跨组织门店A");
        var ownerB = seedAccount("sm-ctrl-cross-b-" + UUID.randomUUID() + "@example.com");
        String orgB = createOrg(ownerB.cookie(), "跨组织主体B");
        String base = uri(orgB, storeA); // storeA 实属 orgA

        client().get().uri(base).header("Cookie", "y1.sid=" + ownerB.cookie())
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("门店不存在");
        client().post().uri(base).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + ownerB.cookie())
                .bodyValue(Map.of("kind", "storefront", "mediaIds", List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isNotFound();
        client().post().uri(base + "/upload-tickets").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + ownerB.cookie())
                .bodyValue(Map.of("kind", "storefront", "contentType", "image/png", "sizeBytes", 1024))
                .exchange().expectStatus().isNotFound();
        client().delete().uri(base + "/" + UUID.randomUUID())
                .header("Cookie", "y1.sid=" + ownerB.cookie())
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("绑定 fail-closed：pending/它店 mediaId 不在上游返回集 → 400 且不落行")
    void bindIsFailClosedForUnresolvedMedia() {
        var owner = seedAccount("sm-ctrl-failclosed-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "fail-closed 主体");
        String storeId = createStore(orgId, owner.cookie(), "fail-closed 门店");
        String pendingMedia = UUID.randomUUID().toString();

        // 上游四重过滤后子集为空（pending 未 confirm / 他人资产 / 错店资产）。
        when(storeMediaClient.downloadUrls(eq(orgId), eq(storeId), anyList()))
                .thenReturn(Mono.just(Map.of()));
        client().post().uri(uri(orgId, storeId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "storefront", "mediaIds", List.of(pendingMedia)))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("媒体不可用或类型不符");

        Long rows = db.sql("SELECT count(*) FROM store_media WHERE store_id = CAST(:store AS uuid)")
                .bind("store", storeId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("绑定往返：返回更新后整组（mediaId/kind/position/快照 mime/size/操作者/下载 URL）")
    void bindRoundTripReturnsFullGroup() {
        var owner = seedAccount("sm-ctrl-roundtrip-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "绑定往返主体");
        String storeId = createStore(orgId, owner.cookie(), "绑定往返门店");
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();

        client().post().uri(uri(orgId, storeId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "environment", "mediaIds", List.of(first, second)))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.storeId").isEqualTo(storeId)
                .jsonPath("$.data.items.length()").isEqualTo(2)
                .jsonPath("$.data.items[0].mediaId").isEqualTo(first)
                .jsonPath("$.data.items[0].kind").isEqualTo("environment")
                .jsonPath("$.data.items[0].position").isEqualTo(1)
                .jsonPath("$.data.items[0].mimeType").isEqualTo("image/png")
                .jsonPath("$.data.items[0].sizeBytes").isEqualTo(1024)
                .jsonPath("$.data.items[0].uploadedByAccountId").isEqualTo(owner.accountId())
                .jsonPath("$.data.items[0].downloadUrl")
                .isEqualTo("https://cdn.example.com/store-media/" + first)
                .jsonPath("$.data.items[1].mediaId").isEqualTo(second)
                .jsonPath("$.data.items[1].position").isEqualTo(2);

        // GET 回显同一整组。
        client().get().uri(uri(orgId, storeId)).header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(2)
                .jsonPath("$.data.items[0].mediaId").isEqualTo(first);
    }

    @Test
    @DisplayName("帽：第 7 张门头 → 409「该分类数量已达上限」，前 6 张不受影响")
    void seventhStorefrontIsConflict() {
        var owner = seedAccount("sm-ctrl-cap-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "帽主体");
        String storeId = createStore(orgId, owner.cookie(), "帽门店");

        List<String> six = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            six.add(UUID.randomUUID().toString());
        }
        client().post().uri(uri(orgId, storeId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "storefront", "mediaIds", six))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(6);

        client().post().uri(uri(orgId, storeId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "storefront", "mediaIds", List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("该分类数量已达上限");

        Long rows = db.sql("SELECT count(*) FROM store_media WHERE store_id = CAST(:store AS uuid)")
                .bind("store", storeId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(rows).isEqualTo(6L);
    }

    @Test
    @DisplayName("开票端点：kind→MIME/帽前置校验（视频 MIME 进图片类 400、超大小帽 400），合法透传票据")
    void uploadTicketValidationAndPassthrough() {
        var owner = seedAccount("sm-ctrl-ticket-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "开票主体");
        String storeId = createStore(orgId, owner.cookie(), "开票门店");
        String ticketUri = uri(orgId, storeId) + "/upload-tickets";

        // 非法 kind → 400。
        client().post().uri(ticketUri).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "poster", "contentType", "image/png", "sizeBytes", 1024))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("媒体分类无效");
        // 图片类塞视频 MIME → 400。
        client().post().uri(ticketUri).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "storefront", "contentType", "video/mp4", "sizeBytes", 1024))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("该分类不支持此文件类型");
        // 图片超 10MB → 400；视频超 20MB → 400。
        client().post().uri(ticketUri).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "menu", "contentType", "image/jpeg",
                        "sizeBytes", 10L * 1024 * 1024 + 1))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("文件大小超出限制");
        client().post().uri(ticketUri).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "video", "contentType", "video/mp4",
                        "sizeBytes", 20L * 1024 * 1024 + 1))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("文件大小超出限制");

        // 合法：ownerAccountId=操作者（org OWNER 隐式 MANAGER），透传 mock 票据。
        UUID ticketId = UUID.randomUUID();
        Instant expiresAt = Instant.parse("2026-08-19T12:00:00Z");
        when(storeMediaClient.createTicket(orgId, owner.accountId(), storeId, "video/mp4", 5120L))
                .thenReturn(Mono.just(new StoreMediaUploadTicket(ticketId,
                        "media/store_media/" + ticketId,
                        URI.create("https://upload.test/store-media/" + ticketId), "PUT",
                        Map.of("Content-Type", "video/mp4"), expiresAt)));
        client().post().uri(ticketUri).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "video", "contentType", "video/mp4", "sizeBytes", 5120))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.id").isEqualTo(ticketId.toString())
                .jsonPath("$.data.uploadUrl").isEqualTo("https://upload.test/store-media/" + ticketId)
                .jsonPath("$.data.method").isEqualTo("PUT")
                .jsonPath("$.data.expiresAt").isEqualTo("2026-08-19T12:00:00Z");

        // 上游 4xx 透传同码 + 中文错误。
        when(storeMediaClient.createTicket(eq(orgId), eq(owner.accountId()), eq(storeId),
                eq("image/webp"), anyLong()))
                .thenReturn(Mono.error(new IdentityException(400, "门店媒体仅支持 JPEG、PNG 或 WebP 图片")));
        client().post().uri(ticketUri).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "storefront", "contentType", "image/webp", "sizeBytes", 1024))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("门店媒体仅支持 JPEG、PNG 或 WebP 图片");
    }

    @Test
    @DisplayName("重排：精确集合匹配（缺项 409），成功返回整组且顺序生效")
    void reorderRequiresExactSet() {
        var owner = seedAccount("sm-ctrl-reorder-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "重排主体");
        String storeId = createStore(orgId, owner.cookie(), "重排门店");
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String c = UUID.randomUUID().toString();
        client().post().uri(uri(orgId, storeId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "menu", "mediaIds", List.of(a, b, c)))
                .exchange().expectStatus().isOk();

        client().put().uri(uri(orgId, storeId) + "/order").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "menu", "orderedMediaIds", List.of(a, b)))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("排序列表与该分类当前媒体不一致");

        // 重复 id 入口即 409（不静默去重，与 repository「缺项/多项/重复→409」口径对齐）。
        client().put().uri(uri(orgId, storeId) + "/order").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "menu", "orderedMediaIds", List.of(a, a, b)))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("排序列表存在重复媒体");

        client().put().uri(uri(orgId, storeId) + "/order").contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "menu", "orderedMediaIds", List.of(c, a, b)))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(3)
                .jsonPath("$.data.items[0].mediaId").isEqualTo(c)
                .jsonPath("$.data.items[1].mediaId").isEqualTo(a)
                .jsonPath("$.data.items[2].mediaId").isEqualTo(b);
    }

    @Test
    @DisplayName("解绑：未绑定本店 404；成功只删绑定行（D6 不调上游删除）且回整组")
    void unbindRemovesBindingOnly() {
        var owner = seedAccount("sm-ctrl-unbind-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "解绑主体");
        String storeId = createStore(orgId, owner.cookie(), "解绑门店");
        String bound = UUID.randomUUID().toString();
        client().post().uri(uri(orgId, storeId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "storefront", "mediaIds", List.of(bound)))
                .exchange().expectStatus().isOk();

        client().delete().uri(uri(orgId, storeId) + "/" + UUID.randomUUID())
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("媒体未绑定该门店");

        client().delete().uri(uri(orgId, storeId) + "/" + bound)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.deleted").isEqualTo(true);

        // 解绑后 GET 整组为空（只删绑定行，D6）。
        client().get().uri(uri(orgId, storeId)).header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(0);

        Long rows = db.sql("SELECT count(*) FROM store_media WHERE store_id = CAST(:store AS uuid)")
                .bind("store", storeId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("绑定请求校验：空列表/非 UUID/超 12 个 → 400")
    void bindRequestValidation() {
        var owner = seedAccount("sm-ctrl-validate-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "绑定校验主体");
        String storeId = createStore(orgId, owner.cookie(), "绑定校验门店");

        client().post().uri(uri(orgId, storeId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "storefront", "mediaIds", List.of()))
                .exchange().expectStatus().isBadRequest();
        client().post().uri(uri(orgId, storeId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "storefront", "mediaIds", List.of("not-a-uuid")))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("媒体不可用或类型不符");

        List<String> thirteen = new ArrayList<>();
        for (int i = 0; i < 13; i++) {
            thirteen.add(UUID.randomUUID().toString());
        }
        client().post().uri(uri(orgId, storeId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "environment", "mediaIds", thirteen))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("一次最多绑定 12 个媒体");
    }

    @Test
    @DisplayName("上游整体故障：GET 管理端点 503 透传")
    void upstreamFailureMaps503OnManageGet() {
        var owner = seedAccount("sm-ctrl-503-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "故障主体");
        String storeId = createStore(orgId, owner.cookie(), "故障门店");
        client().post().uri(uri(orgId, storeId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("kind", "storefront", "mediaIds", List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isOk();

        when(storeMediaClient.downloadUrls(eq(orgId), eq(storeId), any()))
                .thenReturn(Mono.error(new IdentityException(503, "门店媒体服务暂不可用")));
        client().get().uri(uri(orgId, storeId)).header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.error").isEqualTo("门店媒体服务暂不可用");
    }
}
