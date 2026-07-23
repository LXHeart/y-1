package com.grassland.identity.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
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
    void nonMemberGetsForbidden() {
        var owner = seedAccount("store-owner2@example.com");
        String orgId = createOrg(owner.cookie(), "成员守卫主体");
        var outsider = seedAccount("store-outsider@example.com");

        client().post().uri("/api/organizations/" + orgId + "/stores")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + outsider.cookie())
                .bodyValue("{\"name\":\"X\"}").exchange().expectStatus().isForbidden();
        client().get().uri("/api/organizations/" + orgId + "/stores")
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
    }

}
