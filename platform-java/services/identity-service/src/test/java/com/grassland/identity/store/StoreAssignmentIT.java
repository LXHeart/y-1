package com.grassland.identity.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 门店分配/移除/调度端到端（任务书 #52 池模型）。继承 {@link IdentityItSupport}。
 *
 * <p>守卫矩阵：仅主体 ADMIN+（member/店长 403）、店须属本组织（404）、分配对象须为池内
 * member（owner/admin 400、外部账号 404）、一店一店长（建号与分配两处同闸，同店改角色排自身）、
 * assign-or-move 原子性（挂他店再分配=移动）、移除回池（组织关系保留）。
 */
class StoreAssignmentIT extends IdentityItSupport {


    @Test
    void assignMoveRemove_roundTrip_withPoolListing() {
        var owner = seedAccount("as-owner@example.com");
        String cookie = owner.cookie();
        String orgId = createOrg(cookie, "分配主体");
        String storeA = createStore(orgId, cookie, "总店");
        String storeB = createStore(orgId, cookie, "分店");

        // 池内未分配成员（member 建号不挂店）
        String poolId = createAccount(orgId, cookie,
                "{\"role\":\"member\",\"loginName\":\"poolguy\",\"displayName\":\"池内成员\"}");
        // 列表回显：未分配（storeId/storeRole/storeName 全 null，#52 增列）——过滤型
        // jsonPath 对 null 断言不可靠，取响应体在 Java 侧判
        Map<String, Object> listBody = client().get().uri("/api/organizations/" + orgId + "/memberships")
                .header("Cookie", "y1.sid=" + cookie).exchange()
                .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> poolRow = ((java.util.List<Map<String, Object>>) listBody.get("data")).stream()
                .filter(row -> poolId.equals(row.get("accountId"))).findFirst().orElseThrow();
        assertThat(poolRow.get("storeId")).isNull();
        assertThat(poolRow.get("storeRole")).isNull();
        assertThat(poolRow.get("storeName")).isNull();

        // 分配为 A 店店员
        assign(orgId, storeA, poolId, "staff", cookie).expectStatus().isOk()
                .expectBody().jsonPath("$.data.role").isEqualTo("staff");
        assertThat(storeRoleOf(orgId, poolId)).isEqualTo("staff");
        assertThat(storeIdOf(orgId, poolId)).isEqualTo(storeA);

        // 调度到 B 店当店长（assign-or-move：A 店行消失）
        assign(orgId, storeB, poolId, "manager", cookie).expectStatus().isOk();
        assertThat(storeIdOf(orgId, poolId)).isEqualTo(storeB);
        assertThat(storeRoleOf(orgId, poolId)).isEqualTo("manager");

        // 同店改角色：manager → staff（排自身的唯一闸不误伤）
        assign(orgId, storeB, poolId, "staff", cookie).expectStatus().isOk();
        assertThat(storeRoleOf(orgId, poolId)).isEqualTo("staff");

        // 移除回池：挂靠清零，组织关系保留（回池语义）
        remove(orgId, storeB, poolId, cookie).expectStatus().isOk();
        assertThat(storeIdOf(orgId, poolId)).isNull();
        Long orgRows = db.sql("SELECT COUNT(*)::int AS c FROM organization_membership"
                        + " WHERE organization_id = CAST(:org AS uuid) AND account_id = CAST(:acct AS uuid)")
                .bind("org", orgId).bind("acct", poolId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(orgRows).isEqualTo(1);

        // 事件留痕
        Long events = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type IN ('StoreMembershipAssigned','StoreMembershipRemoved')"
                        + " AND payload->>'accountId' = :acct")
                .bind("acct", poolId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(events).isEqualTo(4);

        // 移除后再移除 → 404
        remove(orgId, storeB, poolId, cookie).expectStatus().isNotFound();
    }

    @Test
    void uniqueManagerGate_rejectsSecondManager() {
        var owner = seedAccount("as-gate@example.com");
        String cookie = owner.cookie();
        String orgId = createOrg(cookie, "店长闸主体");
        String storeA = createStore(orgId, cookie, "闸店");
        String storeB = createStore(orgId, cookie, "邻店");

        String mgrId = createAccount(orgId, cookie,
                "{\"role\":\"manager\",\"storeId\":\"" + storeA + "\",\"loginName\":\"gatemgr\",\"displayName\":\"在位店长\"}");

        // 分配第二位店长到同店 → 409（决策 B）
        assign(orgId, storeA, createPoolMember(orgId, cookie, "gatepool", "候补"), "manager", cookie)
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("该门店已有店长，请先移除或调度原店长");

        // 调度在位店长到邻店时，邻店已有别人当店长 → 409 且原挂靠不动（原子）
        String otherMgr = createAccount(orgId, cookie,
                "{\"role\":\"manager\",\"storeId\":\"" + storeB + "\",\"loginName\":\"gatemgr2\",\"displayName\":\"邻店店长\"}");
        assign(orgId, storeB, mgrId, "manager", cookie).expectStatus().isEqualTo(409);
        assertThat(storeIdOf(orgId, mgrId)).isEqualTo(storeA);

        // 先移除邻店店长，调度即成功
        remove(orgId, storeB, otherMgr, cookie).expectStatus().isOk();
        assign(orgId, storeB, mgrId, "manager", cookie).expectStatus().isOk();
        assertThat(storeIdOf(orgId, mgrId)).isEqualTo(storeB);
    }

    @Test
    void guards_adminOnly_storeInOrg_poolMemberOnly() {
        var owner = seedAccount("as-guard@example.com");
        String cookie = owner.cookie();
        String orgId = createOrg(cookie, "分配守卫主体");
        String storeA = createStore(orgId, cookie, "守卫店");

        // 店长（member+manager 挂靠）不可分配/移除 → 403（决策 C）
        String mgrId = createAccount(orgId, cookie,
                "{\"role\":\"manager\",\"storeId\":\"" + storeA + "\",\"loginName\":\"gdguard\",\"displayName\":\"守卫店长\"}");
        String poolId = createPoolMember(orgId, cookie, "gdpool", "被分配者");
        var mgrCookie = cookieFor(mgrId);
        assign(orgId, storeA, poolId, "staff", mgrCookie).expectStatus().isForbidden();
        remove(orgId, storeA, mgrId, mgrCookie).expectStatus().isForbidden();

        // 普通组织 member 也不可 → 403
        assign(orgId, storeA, poolId, "staff", cookieFor(poolId)).expectStatus().isForbidden();

        // owner/admin 不可被挂店 → 400
        assign(orgId, storeA, owner.accountId(), "staff", cookie).expectStatus().isBadRequest();

        // 外部账号 → 404 跨主体隔离
        var outsider = seedAccount("as-out@example.com");
        assign(orgId, storeA, outsider.accountId(), "staff", cookie).expectStatus().isNotFound();

        // 跨主体隔离：orgA 操作者在他组织门禁即被拒（403）；换 orgB 自己的 owner 操作
        // orgA 的池内账号，才走到资源校验 → 404
        var ownerB = seedAccount("as-owner-b@example.com");
        String orgB = createOrg(ownerB.cookie(), "他组织");
        String storeB = createStore(orgB, ownerB.cookie(), "他店");
        assign(orgB, storeB, poolId, "staff", cookie).expectStatus().isForbidden();
        assign(orgB, storeB, poolId, "staff", ownerB.cookie()).expectStatus().isNotFound();

        // 非法角色 → 400
        assign(orgId, storeA, poolId, "member", cookie).expectStatus().isBadRequest();
    }

    // ---------- helpers ----------

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec assign(
            String orgId, String storeId, String accountId, String role, String cookie) {
        return client().put()
                .uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships/" + accountId)
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"role\":\"" + role + "\"}").exchange();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec remove(
            String orgId, String storeId, String accountId, String cookie) {
        return client().delete()
                .uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships/" + accountId)
                .header("Cookie", "y1.sid=" + cookie).exchange();
    }

    /** 池内未分配成员（member 建号不挂店），返回 accountId。 */
    private String createPoolMember(String orgId, String cookie, String loginName, String displayName) {
        return createAccount(orgId, cookie,
                "{\"role\":\"member\",\"loginName\":\"" + loginName + "\",\"displayName\":\"" + displayName + "\"}");
    }

    @SuppressWarnings("unchecked")
    private String createAccount(String orgId, String cookie, String json) {
        Map<String, Object> body = client().post().uri("/api/organizations/" + orgId + "/accounts")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue(json).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) ((Map<String, Object>) body.get("data")).get("account")).get("id");
    }

    private String storeIdOf(String orgId, String accountId) {
        return db.sql("""
                        SELECT sm.store_id::text FROM store_membership sm
                        JOIN store s ON s.id = sm.store_id
                        WHERE sm.account_id = CAST(:acct AS uuid) AND s.organization_id = CAST(:org AS uuid)
                        """)
                .bind("acct", accountId).bind("org", orgId)
                .map(r -> r.get(0, String.class)).one().block();
    }

    private String storeRoleOf(String orgId, String accountId) {
        return db.sql("""
                        SELECT sm.role FROM store_membership sm
                        JOIN store s ON s.id = sm.store_id
                        WHERE sm.account_id = CAST(:acct AS uuid) AND s.organization_id = CAST(:org AS uuid)
                        """)
                .bind("acct", accountId).bind("org", orgId)
                .map(r -> r.get(0, String.class)).one().block();
    }
}
