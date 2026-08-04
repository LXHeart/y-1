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
                .jsonPath("$.data.status").isEqualTo("active");
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

}
