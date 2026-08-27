package com.grassland.identity.store;

import com.grassland.identity.IdentityItSupport;
import org.junit.jupiter.api.Test;

/**
 * 门店成员列表端到端（草场身份域 Slice 2G）。继承 {@link IdentityItSupport}。
 *
 * <p>任务书 #49：POST（挂既有账号）/DELETE（解除关系）端点已随挂靠通路下线，相关用例移除；
 * 门店成员的产生走店长直建子账号（OrgSubAccountControllerIT 覆盖），本文件守 GET 读侧
 * 门禁：列表带账号状态、非本组织账号 403、无 cookie 401。
 */
class StoreMembershipControllerIT extends IdentityItSupport {

    @Test
    void listShowsStoreMembersWithAccountStatus() {
        var owner = seedAccount("sm-owner@example.com");
        String orgId = createOrg(owner.cookie(), "门店成员主体");
        String storeId = createStore(orgId, owner.cookie(), "测试门店");

        // 直建一名店员（唯一成员产生通路，#49），列表应回显其账号状态
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/accounts")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"role\":\"staff\",\"loginName\":\"smstaff1\",\"displayName\":\"门店员工\"}")
                .exchange().expectStatus().isCreated();

        client().get().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].role").isEqualTo("staff")
                .jsonPath("$.data[0].accountStatus").isEqualTo("active")
                // 2026-08-28：列表回显账号名（前缀-登录名）——此前 toBody 丢弃，行里只剩 accountId 前 8 位
                .jsonPath("$.data[0].username").value(
                        org.hamcrest.Matchers.matchesRegex("[a-z0-9]+-smstaff1"));
    }

    @Test
    void nonOrgMemberForbidden() {
        var ownerA = seedAccount("sm-a@example.com");
        String orgA = createOrg(ownerA.cookie(), "主体A");
        String storeA = createStore(orgA, ownerA.cookie(), "A店");
        var ownerB = seedAccount("sm-b@example.com"); // 非 orgA 成员
        client().get().uri("/api/organizations/" + orgA + "/stores/" + storeA + "/memberships")
                .header("Cookie", "y1.sid=" + ownerB.cookie()).exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void rejectsRequestsWithoutSessionCookie() {
        var owner = seedAccount("sm-na@example.com");
        String orgId = createOrg(owner.cookie(), "无鉴权门店主体");
        String storeId = createStore(orgId, owner.cookie(), "门店");
        client().get().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .exchange().expectStatus().isUnauthorized();
    }
}
