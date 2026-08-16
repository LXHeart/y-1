package com.grassland.identity.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 端到端验证 Store（草场身份域 Slice 2F）。继承 {@link IdentityItSupport}（testcontainers + Flyway V1/V2 + 登录态）。
 *
 * <p>建门店需 ADMIN（owner 满足）；列表/单查需 MEMBER；非成员 403；无 cookie 401。outbox 计数按 orgId 限定解耦顺序。
 */
class StoreControllerIT extends IdentityItSupport {

    @Test
    void createReturnsCreatedAndPersistsOutboxEvent() {
        var owner = seedAccount("store-owner@example.com");
        String orgId = createOrg(owner.cookie(), "测试主体");

        client().post().uri("/api/organizations/" + orgId + "/stores")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"name\":\"旗舰店\"}")
                .exchange().expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.name").isEqualTo("旗舰店")
                .jsonPath("$.data.organizationId").isEqualTo(orgId)
                .jsonPath("$.data.status").isEqualTo("active");

        Long count = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'StoreCreated' AND payload->>'organizationId' = :orgId")
                .bind("orgId", orgId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void listAndGetByOrg() {
        var owner = seedAccount("store-list@example.com");
        String orgId = createOrg(owner.cookie(), "列表主体");
        createStore(orgId, owner.cookie(), "门店一");
        String storeId = createStore(orgId, owner.cookie(), "门店二");

        client().get().uri("/api/organizations/" + orgId + "/stores")
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(2)
                .jsonPath("$.data[0].name").isEqualTo("门店一");

        client().get().uri("/api/organizations/" + orgId + "/stores/" + storeId)
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.id").isEqualTo(storeId)
                .jsonPath("$.data.name").isEqualTo("门店二");
    }

    @Test
    void crossOrgStoreLookupReturns404() {
        var ownerA = seedAccount("owner-a@example.com");
        String orgA = createOrg(ownerA.cookie(), "主体A");
        String storeId = createStore(orgA, ownerA.cookie(), "A店");

        var ownerB = seedAccount("owner-b@example.com");
        String orgB = createOrg(ownerB.cookie(), "主体B");

        // ownerB 查 orgB 下不存在的 storeId（该 store 实属 orgA）→ 404（findByOrganizationAndId 跨 org 隔离）
        client().get().uri("/api/organizations/" + orgB + "/stores/" + storeId)
                .header("Cookie", "y1.sid=" + ownerB.cookie()).exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void crossOrgStoreProfileReadWriteAndDeleteReturn404() {
        var victim = seedAccount("store-profile-victim@example.com");
        String victimOrg = createOrg(victim.cookie(), "门店资料主体A");
        String victimStore = createStore(victimOrg, victim.cookie(), "A店");

        client().post().uri("/api/organizations/" + victimOrg + "/stores/" + victimStore + "/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + victim.cookie())
                .bodyValue("{\"address\":\"{\\\"address\\\":\\\"原地址\\\"}\",\"phone\":\"13800000000\"}")
                .exchange().expectStatus().isOk();

        var attacker = seedAccount("store-profile-attacker@example.com");
        String attackerOrg = createOrg(attacker.cookie(), "门店资料主体B");
        String attackerCookie = "y1.sid=" + attacker.cookie();
        String crossOrgUri = "/api/organizations/" + attackerOrg + "/stores/" + victimStore + "/profile";

        client().get().uri(crossOrgUri)
                .header("Cookie", attackerCookie)
                .exchange().expectStatus().isNotFound();

        client().post().uri(crossOrgUri)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", attackerCookie)
                .bodyValue("{\"address\":\"{\\\"address\\\":\\\"攻击者地址\\\"}\"}")
                .exchange().expectStatus().isNotFound();

        client().delete().uri(crossOrgUri)
                .header("Cookie", attackerCookie)
                .exchange().expectStatus().isNotFound();

        client().get().uri("/api/organizations/" + victimOrg + "/stores/" + victimStore + "/profile")
                .header("Cookie", "y1.sid=" + victim.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.address").isEqualTo("{\"address\": \"原地址\"}")
                .jsonPath("$.data.status").isEqualTo("draft");
    }

    @Test
    void missingStoreProfileReturnsNullDataEnvelope() {
        var owner = seedAccount("store-profile-empty@example.com");
        String orgId = createOrg(owner.cookie(), "空门店资料主体");
        String storeId = createStore(orgId, owner.cookie(), "空资料门店");

        client().get().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/profile")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk().expectBody()
                .json("{\"success\":true,\"data\":null}");
    }

    @Test
    void ownerCanDeactivateProfileWithoutErasingAddress() {
        var owner = seedAccount("store-profile-delete@example.com");
        String orgId = createOrg(owner.cookie(), "删除门店资料主体");
        String storeId = createStore(orgId, owner.cookie(), "待停用门店");
        String uri = "/api/organizations/" + orgId + "/stores/" + storeId + "/profile";
        String cookie = "y1.sid=" + owner.cookie();

        client().post().uri(uri).contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", cookie)
                .bodyValue("{\"address\":\"{\\\"address\\\":\\\"南京西路 1 号\\\"}\"}")
                .exchange().expectStatus().isOk();

        client().delete().uri(uri).header("Cookie", cookie)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.deleted").isEqualTo(true);

        client().get().uri(uri).header("Cookie", cookie)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.address").isEqualTo("{\"address\": \"南京西路 1 号\"}")
                .jsonPath("$.data.status").isEqualTo("inactive");
    }

    @Test
    void blankOrMalformedStoreAddressIsBadRequest() {
        var owner = seedAccount("store-profile-address@example.com");
        String orgId = createOrg(owner.cookie(), "门店地址校验主体");
        String storeId = createStore(orgId, owner.cookie(), "地址校验门店");
        String uri = "/api/organizations/" + orgId + "/stores/" + storeId + "/profile";
        String cookie = "y1.sid=" + owner.cookie();

        client().post().uri(uri).contentType(MediaType.APPLICATION_JSON).header("Cookie", cookie)
                .bodyValue("{\"phone\":\"13800000000\"}")
                .exchange().expectStatus().isBadRequest();

        client().post().uri(uri).contentType(MediaType.APPLICATION_JSON).header("Cookie", cookie)
                .bodyValue("{\"address\":\"not-json\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void malformedBusinessHoursIsBadRequest() {
        var owner = seedAccount("store-profile-hours@example.com");
        String orgId = createOrg(owner.cookie(), "门店营业时间校验主体");
        String storeId = createStore(orgId, owner.cookie(), "营业时间校验门店");
        String uri = "/api/organizations/" + orgId + "/stores/" + storeId + "/profile";
        String cookie = "y1.sid=" + owner.cookie();

        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"address\":\"{\\\"address\\\":\\\"南京西路 1 号\\\"}\","
                        + "\"businessHours\":\"not-json\"}")
                .exchange().expectStatus().isBadRequest();

        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"address\":\"{\\\"address\\\":\\\"南京西路 1 号\\\"}\","
                        + "\"businessHours\":\"[{}]\"}")
                .exchange().expectStatus().isBadRequest();

        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"address\":\"{\\\"address\\\":\\\"南京西路 1 号\\\"}\","
                        + "\"businessHours\":\"[{\\\"dayOfWeek\\\":1,\\\"openTime\\\":\\\"18:00\\\","
                        + "\\\"closeTime\\\":\\\"09:00\\\"}]\"}")
                .exchange().expectStatus().isBadRequest();

        for (String invalidHours : new String[]{
                "[{\"dayOfWeek\":1.5,\"openTime\":\"09:00\",\"closeTime\":\"18:00\"}]",
                "[{\"dayOfWeek\":1,\"openTime\":\"09:00\",\"closeTime\":\"18:00\"},"
                        + "{\"dayOfWeek\":1,\"openTime\":\"10:00\",\"closeTime\":\"19:00\"}]",
                "[{\"dayOfWeek\":1,\"openTime\":\"09:00:00\",\"closeTime\":\"18:00\"}]",
                "[{\"dayOfWeek\":1,\"openTime\":\"09:00\",\"closeTime\":\"18:00\",\"timezone\":\"UTC\"}]"
        }) {
            client().post().uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Cookie", cookie)
                    .bodyValue(Map.of(
                            "address", "{\"address\":\"南京西路 1 号\"}",
                            "businessHours", invalidHours))
                    .exchange().expectStatus().isBadRequest();
        }
    }

    @Test
    void nonexistentStoreCannotCreateOrphanProfile() {
        var owner = seedAccount("store-profile-orphan@example.com");
        String orgId = createOrg(owner.cookie(), "孤儿门店资料主体");
        String missingStoreId = java.util.UUID.randomUUID().toString();

        client().post().uri("/api/organizations/" + orgId + "/stores/" + missingStoreId + "/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"address\":\"{\\\"address\\\":\\\"不存在\\\"}\"}")
                .exchange().expectStatus().isNotFound();

        Long count = db.sql("SELECT count(*) FROM store_profile WHERE store_id = CAST(:id AS uuid)")
                .bind("id", missingStoreId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(count).isZero();
    }

    @Test
    void nonMemberGetsForbidden() {
        var owner = seedAccount("store-owner2@example.com");
        String orgId = createOrg(owner.cookie(), "成员守卫主体");
        var outsider = seedAccount("store-outsider@example.com");

        client().post().uri("/api/organizations/" + orgId + "/stores")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + outsider.cookie())
                .bodyValue("{\"name\":\"X\"}").exchange().expectStatus().isForbidden();
        client().get().uri("/api/organizations/" + orgId + "/stores")
                .header("Cookie", "y1.sid=" + outsider.cookie()).exchange().expectStatus().isForbidden();

        String storeId = createStore(orgId, owner.cookie(), "受保护门店");
        client().delete().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/profile")
                .header("Cookie", "y1.sid=" + outsider.cookie()).exchange().expectStatus().isForbidden();
    }

    @Test
    void independentStoreManagerWalksKybLifecycleWithoutOrgMembership() {
        var owner = seedAccount("store-kyb-owner@example.com");
        String orgId = createOrg(owner.cookie(), "独立门店KYB主体");
        String storeId = createStore(orgId, owner.cookie(), "独立门店");
        var manager = seedAccount("store-kyb-manager@example.com");
        var staff = seedAccount("store-kyb-staff@example.com");
        addStoreMember(owner.cookie(), orgId, storeId, manager.accountId(), "manager");
        addStoreMember(owner.cookie(), orgId, storeId, staff.accountId(), "staff");
        String uri = "/api/organizations/" + orgId + "/stores/" + storeId + "/profile";
        String managerCookie = "y1.sid=" + manager.cookie();

        // 纯门店经理（无组织成员行、无 merchant identity）可创建并提交门店资料审核。
        client().post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", managerCookie)
                .bodyValue("{\"address\":\"{\\\"address\\\":\\\"经理填写地址\\\"}\",\"phone\":\"13900000000\"}")
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("draft");

        client().post().uri(uri + "/submit")
                .header("Cookie", managerCookie)
                .exchange().expectStatus().isCreated()
                .expectBody().jsonPath("$.data.status").isEqualTo("pending");

        // 门店 STAFF：可读（200），不可写/不可提交（403）。
        String staffCookie = "y1.sid=" + staff.cookie();
        client().get().uri(uri).header("Cookie", staffCookie)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("pending");
        client().post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", staffCookie)
                .bodyValue("{\"address\":\"{\\\"address\\\":\\\"店员越权\\\"}\"}")
                .exchange().expectStatus().isForbidden();
        client().post().uri(uri + "/submit").header("Cookie", staffCookie)
                .exchange().expectStatus().isForbidden();

        // 组织普通成员（无门店角色）：读可见（回落 MEMBER），写 403。
        var orgMember = seedAccount("store-kyb-org-member@example.com");
        client().post().uri("/api/organizations/" + orgId + "/memberships")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", orgMember.accountId(), "role", "member"))
                .exchange().expectStatus().isCreated();
        String memberCookie = "y1.sid=" + orgMember.cookie();
        client().get().uri(uri).header("Cookie", memberCookie)
                .exchange().expectStatus().isOk();
        client().post().uri(uri)
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", memberCookie)
                .bodyValue("{\"address\":\"{\\\"address\\\":\\\"成员越权\\\"}\"}")
                .exchange().expectStatus().isForbidden();

        // 其他组织的门店 MANAGER 跨 org 仍 404（不泄露存在性）。
        var otherOwner = seedAccount("store-kyb-other@example.com");
        String otherOrg = createOrg(otherOwner.cookie(), "他组织");
        var otherManager = seedAccount("store-kyb-other-manager@example.com");
        String otherStore = createStore(otherOrg, otherOwner.cookie(), "他店");
        addStoreMember(otherOwner.cookie(), otherOrg, otherStore, otherManager.accountId(), "manager");
        client().post().uri("/api/organizations/" + otherOrg + "/stores/" + storeId + "/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + otherManager.cookie())
                .bodyValue("{\"address\":\"{\\\"address\\\":\\\"跨店越权\\\"}\"}")
                .exchange().expectStatus().isNotFound();

        // KYB 请求已入队（STORE_PROFILE 目标）。
        Long queued = db.sql("SELECT COUNT(*)::int AS c FROM kyb_verification_request"
                        + " WHERE verification_type = 'store_profile' AND target_id = CAST(:id AS uuid)")
                .bind("id", storeId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(queued).isEqualTo(1);
    }

    private void addStoreMember(String cookie, String orgId, String storeId, String accountId, String role) {
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .header("Cookie", "y1.sid=" + cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("accountId", accountId, "role", role))
                .exchange().expectStatus().isCreated();
    }

    @Test
    void rejectsRequestsWithoutSessionCookie() {
        var owner = seedAccount("store-noauth@example.com");
        String orgId = createOrg(owner.cookie(), "无鉴权主体");
        client().post().uri("/api/organizations/" + orgId + "/stores")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{\"name\":\"X\"}")
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/organizations/" + orgId + "/stores").exchange().expectStatus().isUnauthorized();
        String storeId = createStore(orgId, owner.cookie(), "无鉴权删除门店");
        client().delete().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/profile")
                .exchange().expectStatus().isUnauthorized();
    }

    // ---- 任务书 #24：门店营销字段（PRD §2.1） ----

    @Test
    void marketingFieldsRoundTripAndKeepKybDraftSemantics() {
        var owner = seedAccount("store-profile-marketing@example.com");
        String orgId = createOrg(owner.cookie(), "门店营销字段主体");
        String storeId = createStore(orgId, owner.cookie(), "营销字段门店");
        String uri = "/api/organizations/" + orgId + "/stores/" + storeId + "/profile";
        String cookie = "y1.sid=" + owner.cookie();

        java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("address", "{\"address\":\"南京西路 1 号\",\"city\":\"上海\"}");
        body.put("phone", "13800000000");
        body.put("description", "老字号火锅");
        body.put("categories", java.util.List.of("火锅", "川菜"));
        body.put("signatureItems", java.util.List.of("招牌毛肚"));
        body.put("sellingPoints", java.util.List.of("现切牛肉"));
        body.put("mustEmphasize", java.util.List.of("锅底现熬"));
        body.put("forbiddenPhrases", java.util.List.of("最好吃"));
        body.put("allowedTags", java.util.List.of("#探店"));
        body.put("brandTone", "温暖亲切");
        body.put("priceRange", "¥30–¥80");
        body.put("averageSpendCents", 6500);
        body.put("visitNotes", "地铁 2 号线直达");

        client().post().uri(uri).contentType(MediaType.APPLICATION_JSON).header("Cookie", cookie)
                .bodyValue(body)
                .exchange().expectStatus().isOk().expectBody()
                // V22 KYB 审核语义不变：编辑仍落 draft，审核列空。
                .jsonPath("$.data.status").isEqualTo("draft")
                .jsonPath("$.data.submittedAt").isEmpty()
                .jsonPath("$.data.categories[0]").isEqualTo("火锅")
                .jsonPath("$.data.categories[1]").isEqualTo("川菜")
                .jsonPath("$.data.signatureItems[0]").isEqualTo("招牌毛肚")
                .jsonPath("$.data.sellingPoints[0]").isEqualTo("现切牛肉")
                .jsonPath("$.data.mustEmphasize[0]").isEqualTo("锅底现熬")
                .jsonPath("$.data.forbiddenPhrases[0]").isEqualTo("最好吃")
                .jsonPath("$.data.allowedTags[0]").isEqualTo("#探店")
                .jsonPath("$.data.brandTone").isEqualTo("温暖亲切")
                .jsonPath("$.data.priceRange").isEqualTo("¥30–¥80")
                .jsonPath("$.data.averageSpendCents").isEqualTo(6500)
                .jsonPath("$.data.visitNotes").isEqualTo("地铁 2 号线直达");

        // 读回端点同样往返。
        client().get().uri(uri).header("Cookie", cookie)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.categories.length()").isEqualTo(2)
                .jsonPath("$.data.averageSpendCents").isEqualTo(6500);

        // 空数组与不传等价（清空语义）：重提不带营销字段 → 全部清空。
        client().post().uri(uri).contentType(MediaType.APPLICATION_JSON).header("Cookie", cookie)
                .bodyValue(Map.of("address", "{\"address\":\"南京西路 1 号\"}"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.categories.length()").isEqualTo(0)
                .jsonPath("$.data.brandTone").isEmpty()
                .jsonPath("$.data.averageSpendCents").isEmpty()
                .jsonPath("$.data.address").isEqualTo("{\"address\": \"南京西路 1 号\"}");
    }

    @Test
    void marketingFieldCapsAreEnforced() {
        var owner = seedAccount("store-profile-marketing-caps@example.com");
        String orgId = createOrg(owner.cookie(), "门店营销帽主体");
        String storeId = createStore(orgId, owner.cookie(), "营销帽门店");
        String uri = "/api/organizations/" + orgId + "/stores/" + storeId + "/profile";
        String cookie = "y1.sid=" + owner.cookie();
        String address = "{\"address\":\"南京西路 1 号\"}";

        // 21 项超帽。
        java.util.List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 21; i++) {
            tooMany.add("品类" + i);
        }
        client().post().uri(uri).contentType(MediaType.APPLICATION_JSON).header("Cookie", cookie)
                .bodyValue(Map.of("address", address, "categories", tooMany))
                .exchange().expectStatus().isBadRequest();

        // 单项超 300 字。
        client().post().uri(uri).contentType(MediaType.APPLICATION_JSON).header("Cookie", cookie)
                .bodyValue(Map.of("address", address, "sellingPoints", java.util.List.of("字".repeat(301))))
                .exchange().expectStatus().isBadRequest();

        // 品牌语气超 500 字。
        client().post().uri(uri).contentType(MediaType.APPLICATION_JSON).header("Cookie", cookie)
                .bodyValue(Map.of("address", address, "brandTone", "字".repeat(501)))
                .exchange().expectStatus().isBadRequest();

        // 价格区间超 50 字。
        client().post().uri(uri).contentType(MediaType.APPLICATION_JSON).header("Cookie", cookie)
                .bodyValue(Map.of("address", address, "priceRange", "字".repeat(51)))
                .exchange().expectStatus().isBadRequest();

        // 到店提示超 1000 字。
        client().post().uri(uri).contentType(MediaType.APPLICATION_JSON).header("Cookie", cookie)
                .bodyValue(Map.of("address", address, "visitNotes", "字".repeat(1001)))
                .exchange().expectStatus().isBadRequest();

        // 人均消费负数。
        client().post().uri(uri).contentType(MediaType.APPLICATION_JSON).header("Cookie", cookie)
                .bodyValue(Map.of("address", address, "averageSpendCents", -1))
                .exchange().expectStatus().isBadRequest();

        // 违规不产生写入。
        client().get().uri(uri).header("Cookie", cookie)
                .exchange().expectStatus().isOk().expectBody()
                .json("{\"success\":true,\"data\":null}");
    }

}
