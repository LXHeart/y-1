package com.grassland.identity.brand;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.grassland.identity.IdentityItSupport;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

/**
 * 组织品牌资料 API 全矩阵（#32 规格 1-8：权限/跨组织/字段校验/乐观锁/Logo 归属/round-trip/
 * 无行 GET/与门店不串 + 票据端点）。Logo 媒体走 {@link BrandLogoMediaClient} 替身，
 * 归属/故障分支由 mock 的返回值刻画（真实过滤逻辑在 intelligence MediaControllerIT 覆盖）。
 */
class BrandProfileControllerIT extends IdentityItSupport {

    private String uri(String orgId) {
        return "/api/organizations/" + orgId + "/brand-profile";
    }

    /** 以指定角色把账号加入组织（owner 由 createOrg 隐式承担）。 */
    private void addMember(String orgId, String accountId, String role) {
        db.sql("INSERT INTO organization_membership(id, organization_id, account_id, role) "
                        + "VALUES (gen_random_uuid(), CAST(:org AS uuid), CAST(:account AS uuid), :role)")
                .bind("org", orgId).bind("account", accountId).bind("role", role)
                .fetch().rowsUpdated().block();
    }

    // ---------- 公开消费端点（缺口清偿之六，#32 D9） ----------

    private String publicUri(String orgId) {
        return "/api/organizations/" + orgId + "/public-brand-profile";
    }

    @Test
    @DisplayName("公开读取：未登录 200 白名单字段 + logoUrl（mock）；不泄露媒体 id 与 version")
    void publicBrandProfileServesWhitelistedFieldsWithoutLogin() {
        var owner = seedAccount("brand-public-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "公开品牌主体");
        String mediaId = UUID.randomUUID().toString();

        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("brandName", "草原咖啡", "description", "社区精品咖啡",
                        "industry", "catering", "brandLogoMediaReferenceId", mediaId,
                        "expectedVersion", 0))
                .exchange().expectStatus().isOk();

        when(brandLogoMediaClient.logoUrlFailSoft(mediaId, orgId))
                .thenReturn(Mono.just("https://cdn.example.com/logo.png"));
        // 无 Cookie：公开端点未登录放行
        client().get().uri(publicUri(orgId))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.organizationId").isEqualTo(orgId)
                .jsonPath("$.data.brandName").isEqualTo("草原咖啡")
                .jsonPath("$.data.description").isEqualTo("社区精品咖啡")
                .jsonPath("$.data.industry").isEqualTo("catering")
                .jsonPath("$.data.logoUrl").isEqualTo("https://cdn.example.com/logo.png")
                .jsonPath("$.data.brandLogoMediaReferenceId").doesNotExist()
                .jsonPath("$.data.version").doesNotExist();
    }

    @Test
    @DisplayName("公开读取：无资料行回全 null；logo fail-soft 置空")
    void publicBrandProfileReturnsNullFieldsWhenProfileAbsent() {
        var owner = seedAccount("brand-public-empty-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "无资料主体");

        client().get().uri(publicUri(orgId))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.organizationId").isEqualTo(orgId)
                .jsonPath("$.data.brandName").isEqualTo(null)
                .jsonPath("$.data.description").isEqualTo(null)
                .jsonPath("$.data.industry").isEqualTo(null)
                .jsonPath("$.data.logoUrl").isEqualTo(null);
    }

    @Test
    @DisplayName("公开 gate：组织不存在/非 UUID/非 active → 404")
    void publicBrandProfileRejectsUnknownOrInactiveOrganization() {
        client().get().uri(publicUri(UUID.randomUUID().toString()))
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.error").isEqualTo("组织不存在");
        client().get().uri(publicUri("not-a-uuid"))
                .exchange().expectStatus().isNotFound();

        var owner = seedAccount("brand-public-suspended-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "停用主体");
        db.sql("UPDATE organization SET status='suspended' WHERE id=CAST(:org AS uuid)")
                .bind("org", orgId).fetch().rowsUpdated().block();
        client().get().uri(publicUri(orgId))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("权限矩阵：owner/admin 可 PUT；member 只读（GET 200 回显版本，PUT 403 权限不足）；未登录 401")
    void permissionMatrix() {
        var owner = seedAccount("brand-perm-owner-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "权限矩阵主体");

        // owner PUT 成功（owner 经 owner_account_id 兜底判定）
        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"brandName\":\"草原咖啡\",\"expectedVersion\":0}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.version").isEqualTo(1);

        // admin PUT 成功（升级既有行）
        var admin = seedAccount("brand-perm-admin-" + UUID.randomUUID() + "@example.com");
        addMember(orgId, admin.accountId(), "admin");
        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue("{\"brandName\":\"草原咖啡·admin\",\"expectedVersion\":1}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.brandName").isEqualTo("草原咖啡·admin");

        // member：GET 200（版本回显）但 PUT 403
        var member = seedAccount("brand-perm-member-" + UUID.randomUUID() + "@example.com");
        addMember(orgId, member.accountId(), "member");
        client().get().uri(uri(orgId)).header("Cookie", "y1.sid=" + member.cookie())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.version").isEqualTo(2)
                .jsonPath("$.data.brandName").isEqualTo("草原咖啡·admin");
        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + member.cookie())
                .bodyValue("{\"brandName\":\"member 越权\",\"expectedVersion\":2}")
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("权限不足");

        // 未登录：GET/PUT 均 401
        client().get().uri(uri(orgId)).exchange().expectStatus().isUnauthorized();
        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"expectedVersion\":0}").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("跨组织：他 org 的 admin 对本 org GET/PUT 均 403 无权访问该组织，无数据泄露")
    void crossOrgAccessIsForbidden() {
        var owner = seedAccount("brand-cross-owner-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "被访问主体");
        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"brandName\":\"本组织品牌\",\"expectedVersion\":0}")
                .exchange().expectStatus().isOk();

        var otherOwner = seedAccount("brand-cross-other-" + UUID.randomUUID() + "@example.com");
        String otherOrg = createOrg(otherOwner.cookie(), "外部主体");
        var otherAdmin = seedAccount("brand-cross-admin-" + UUID.randomUUID() + "@example.com");
        addMember(otherOrg, otherAdmin.accountId(), "admin");

        client().get().uri(uri(orgId)).header("Cookie", "y1.sid=" + otherAdmin.cookie())
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("无权访问该组织");
        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + otherAdmin.cookie())
                .bodyValue("{\"brandName\":\"越权写入\",\"expectedVersion\":0}")
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("无权访问该组织");

        Map<String, Object> row = db.sql("SELECT brand_name FROM organization_brand_profile "
                        + "WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", orgId).fetch().one().block();
        assertThat(row).containsEntry("brand_name", "本组织品牌");
    }

    @Test
    @DisplayName("字段校验：品牌名 101 字/简介 2001 字/经营分类非法 → 400 中文提示")
    void fieldValidationRejectsOversizedAndUnknownValues() {
        var owner = seedAccount("brand-fields-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "字段校验主体");

        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("brandName", "草".repeat(101), "expectedVersion", 0))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("品牌名称最多 100 字");

        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("description", "长".repeat(2001), "expectedVersion", 0))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("品牌简介最多 2000 字");

        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("industry", "not_an_industry", "expectedVersion", 0))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("经营分类无效");

        // 已下架分类：博彩/成人内容（禁止准入）与「其他」同样 400（前端下拉已同口径移除）。
        for (String removed : List.of("gambling", "adult", "other")) {
            client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                    .header("Cookie", "y1.sid=" + owner.cookie())
                    .bodyValue(Map.of("industry", removed, "expectedVersion", 0))
                    .exchange().expectStatus().isBadRequest()
                    .expectBody().jsonPath("$.error").isEqualTo("经营分类不支持该行业");
        }

        // 校验失败的 PUT 不得落行
        Long rows = db.sql("SELECT count(*) FROM organization_brand_profile "
                        + "WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", orgId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("全 null PUT 合法（清空语义）且 version+1")
    void clearingAllFieldsIsLegal() {
        var owner = seedAccount("brand-clear-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "清空语义主体");

        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("brandName", "将被清空", "description", "将被清空的简介",
                        "industry", "catering", "expectedVersion", 0))
                .exchange().expectStatus().isOk();

        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("expectedVersion", 1))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.brandName").isEqualTo(null)
                .jsonPath("$.data.description").isEqualTo(null)
                .jsonPath("$.data.industry").isEqualTo(null)
                .jsonPath("$.data.version").isEqualTo(2);
    }

    @Test
    @DisplayName("乐观锁：成功 PUT 版本 0→1；旧 expectedVersion 再 PUT → 409 且数据未变")
    void staleExpectedVersionIsConflict() {
        var owner = seedAccount("brand-lock-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "乐观锁主体");

        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("brandName", "第一版", "expectedVersion", 0))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.version").isEqualTo(1);

        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("brandName", "过期版本写入", "expectedVersion", 0))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("品牌资料已变更，请刷新后重试");

        Map<String, Object> row = db.sql("SELECT brand_name, version FROM organization_brand_profile "
                        + "WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", orgId).fetch().one().block();
        assertThat(row).containsEntry("brand_name", "第一版").containsEntry("version", 1);
    }

    @Test
    @DisplayName("首次创建 expectedVersion 非 0 → 409")
    void firstCreateWithNonZeroExpectedVersionIsConflict() {
        var owner = seedAccount("brand-first-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "首次创建主体");

        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("brandName", "首建", "expectedVersion", 1))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("品牌资料已变更，请刷新后重试");

        Long rows = db.sql("SELECT count(*) FROM organization_brand_profile "
                        + "WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", orgId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("Logo 归属 fail-closed：mock 404 → 400 品牌媒体不可用；mock 503 → 503 且不落库")
    void logoOwnershipCheckIsFailClosed() {
        var owner = seedAccount("brand-logo-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "Logo 归属主体");
        String foreignLogo = UUID.randomUUID().toString();

        when(brandLogoMediaClient.usableLogoUrl(foreignLogo, orgId)).thenReturn(Mono.empty());
        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("brandLogoMediaReferenceId", foreignLogo, "expectedVersion", 0))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("品牌 Logo 媒体不可用或类型不符");

        when(brandLogoMediaClient.usableLogoUrl(foreignLogo, orgId))
                .thenReturn(Mono.error(new com.grassland.identity.auth.IdentityException(
                        503, "品牌Logo服务暂不可用")));
        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("brandLogoMediaReferenceId", foreignLogo, "expectedVersion", 0))
                .exchange().expectStatus().isEqualTo(503);

        Long rows = db.sql("SELECT count(*) FROM organization_brand_profile "
                        + "WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", orgId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("GET fail-soft：logoUrl 解析失败置 null，其余字段正常返回")
    void logoUrlResolutionIsFailSoftOnGet() {
        var owner = seedAccount("brand-soft-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "fail-soft 主体");
        String mediaId = UUID.randomUUID().toString();

        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("brandName", "可读品牌", "brandLogoMediaReferenceId", mediaId,
                        "expectedVersion", 0))
                .exchange().expectStatus().isOk();

        // 替身连 fail-soft 包装一起替换：包装的真实行为（上游异常 → empty + 日志）由
        // BrandLogoMediaClientTest 覆盖；此处按其输出契约 stub empty，验证控制器的容错渲染。
        when(brandLogoMediaClient.logoUrlFailSoft(mediaId, orgId)).thenReturn(Mono.empty());
        client().get().uri(uri(orgId)).header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.brandName").isEqualTo("可读品牌")
                .jsonPath("$.data.brandLogoMediaReferenceId").isEqualTo(mediaId)
                .jsonPath("$.data.logoUrl").isEqualTo(null)
                .jsonPath("$.data.version").isEqualTo(1);
    }

    @Test
    @DisplayName("round-trip：PUT 全字段 → GET 回显 + logoUrl（mock）+ version 递增")
    void fullRoundTripEchoesFieldsAndLogoUrl() {
        var owner = seedAccount("brand-roundtrip-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "round-trip 主体");
        String mediaId = UUID.randomUUID().toString();

        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of(
                        "brandName", "草原咖啡",
                        "brandLogoMediaReferenceId", mediaId,
                        "description", "主营精品咖啡与轻食",
                        "industry", "catering",
                        "expectedVersion", 0))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.version").isEqualTo(1)
                .jsonPath("$.data.logoUrl").isEqualTo("https://cdn.example.com/brand-logo/" + mediaId);

        client().get().uri(uri(orgId)).header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.brandName").isEqualTo("草原咖啡")
                .jsonPath("$.data.description").isEqualTo("主营精品咖啡与轻食")
                .jsonPath("$.data.industry").isEqualTo("catering")
                .jsonPath("$.data.brandLogoMediaReferenceId").isEqualTo(mediaId)
                .jsonPath("$.data.logoUrl").isEqualTo("https://cdn.example.com/brand-logo/" + mediaId)
                .jsonPath("$.data.version").isEqualTo(1);
    }

    @Test
    @DisplayName("无行 GET：version=0 全空资料")
    void missingRowGetReturnsEmptyProfile() {
        var owner = seedAccount("brand-empty-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "无行主体");

        client().get().uri(uri(orgId)).header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.brandName").isEqualTo(null)
                .jsonPath("$.data.brandLogoMediaReferenceId").isEqualTo(null)
                .jsonPath("$.data.logoUrl").isEqualTo(null)
                .jsonPath("$.data.description").isEqualTo(null)
                .jsonPath("$.data.industry").isEqualTo(null)
                .jsonPath("$.data.version").isEqualTo(0);
    }

    @Test
    @DisplayName("与门店不串：先建 store profile 再 PUT 品牌 → store profile 原样")
    void brandProfileDoesNotClobberStoreProfile() {
        var owner = seedAccount("brand-store-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "门店隔离主体");
        String storeId = createStore(orgId, owner.cookie(), "品牌邻接门店");

        // 照 StoreControllerIT 造数：JSON 地址串 + 电话
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"address\":\"{\\\"address\\\":\\\"南京西路 1 号\\\"}\",\"phone\":\"13800000000\"}")
                .exchange().expectStatus().isOk();

        client().put().uri(uri(orgId)).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue(Map.of("brandName", "另一个品牌域", "expectedVersion", 0))
                .exchange().expectStatus().isOk();

        client().get().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/profile")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.address").isEqualTo("{\"address\": \"南京西路 1 号\"}")
                .jsonPath("$.data.phone").isEqualTo("13800000000")
                .jsonPath("$.data.status").isEqualTo("draft");
        client().get().uri(uri(orgId)).header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.brandName").isEqualTo("另一个品牌域");
    }

    @Test
    @DisplayName("票据端点：member 403；admin 透传 mock 票据；上游 400 → 400 透传中文错误")
    void uploadTicketPermissionsAndPassthrough() {
        var owner = seedAccount("brand-ticket-owner-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "票据主体");
        String ticketUri = uri(orgId) + "/logo/upload-ticket";

        var member = seedAccount("brand-ticket-member-" + UUID.randomUUID() + "@example.com");
        addMember(orgId, member.accountId(), "member");
        client().post().uri(ticketUri).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + member.cookie())
                .bodyValue(Map.of("contentType", "image/png", "sizeBytes", 2048))
                .exchange().expectStatus().isForbidden();

        var admin = seedAccount("brand-ticket-admin-" + UUID.randomUUID() + "@example.com");
        addMember(orgId, admin.accountId(), "admin");
        UUID ticketId = UUID.randomUUID();
        URI uploadUrl = URI.create("https://upload.test/media-pending/" + ticketId);
        Instant expiresAt = Instant.parse("2026-08-18T12:00:00Z");
        when(brandLogoMediaClient.createTicket(orgId, admin.accountId(), "image/png", 2048L))
                .thenReturn(Mono.just(new BrandLogoUploadTicket(ticketId,
                        "media/brand_logo/" + ticketId, uploadUrl, "PUT",
                        Map.of("Content-Type", "image/png"), expiresAt)));

        client().post().uri(ticketUri).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue(Map.of("contentType", "image/png", "sizeBytes", 2048))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.id").isEqualTo(ticketId.toString())
                .jsonPath("$.data.objectKey").isEqualTo("media/brand_logo/" + ticketId)
                .jsonPath("$.data.uploadUrl").isEqualTo(uploadUrl.toString())
                .jsonPath("$.data.method").isEqualTo("PUT")
                .jsonPath("$.data.headers.Content-Type").isEqualTo("image/png")
                .jsonPath("$.data.expiresAt").isEqualTo("2026-08-18T12:00:00Z");

        // 上游 400（如 MIME 超白名单）透传同码 + 上游中文错误
        when(brandLogoMediaClient.createTicket(orgId, admin.accountId(), "image/gif", 2048L))
                .thenReturn(Mono.error(new com.grassland.identity.auth.IdentityException(
                        400, "品牌 Logo 仅支持 PNG、JPEG 或 WebP 图片")));
        client().post().uri(ticketUri).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + admin.cookie())
                .bodyValue(Map.of("contentType", "image/gif", "sizeBytes", 2048))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("品牌 Logo 仅支持 PNG、JPEG 或 WebP 图片");
    }
}
