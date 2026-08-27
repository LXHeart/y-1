package com.grassland.identity.organization.subaccount;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 子账号体系端到端（任务书 #48）。继承 {@link IdentityItSupport}，共享容器数据按邮箱前缀隔离。
 *
 * <p>覆盖矩阵：直建 happy path、邮箱冲突两分支、审核开关驱动的 pending/active、
 * 四守卫（最后经理/owner 自保护/自操作）、纯门店经理权限边界、审批 approve/reject 与终态语义。
 */
class OrgSubAccountControllerIT extends IdentityItSupport {

    // ---------- 建号 ----------

    @Test
    void createManager_subaccountActiveWithOneTimePassword() {
        var owner = seedAccount("sa-owner4@example.com");
        String orgId = createOrg(owner.cookie(), "直建主体");
        String storeId = createStore(orgId, owner.cookie(), "旗舰店");

        Map<String, Object> data = createAccount(orgId, owner.cookie(),
                "{\"role\":\"manager\",\"storeId\":\"" + storeId + "\",\"email\":\"sa-mgr4@example.com\","
                        + "\"displayName\":\"王经理\"}")
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        // expectBody(Map) 拿到的是整包 {success,data}；业务体在 data 键下
        data = (Map<String, Object>) data.get("data");

        assertThat(((Map<?, ?>) data.get("account")).get("status")).isEqualTo("active");
        assertThat((String) data.get("initialPassword")).hasSize(16);

        Boolean flagged = db.sql(
                        "SELECT must_change_password FROM account_flag WHERE account_id = CAST(:id AS uuid)")
                .bind("id", ((Map<?, ?>) data.get("account")).get("id"))
                .map(r -> r.get("must_change_password", Boolean.class)).one().block();
        assertThat(flagged).isTrue();

        Long events = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'OrgSubAccountCreated'"
                        + " AND payload->>'organizationId' = :org")
                .bind("org", orgId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(events).isGreaterThanOrEqualTo(1);
    }

    @Test
    void existingEmail_withoutConfirmRejected_withConfirmBindsSafely() {
        var owner = seedAccount("sa-bind@example.com");
        String orgId = createOrg(owner.cookie(), "关联主体");
        String storeId = createStore(orgId, owner.cookie(), "老店");
        var existing = seedAccount("sa-worker@example.com");

        createAccount(orgId, owner.cookie(),
                "{\"role\":\"staff\",\"storeId\":\"" + storeId + "\",\"email\":\"sa-worker@example.com\","
                        + "\"displayName\":\"张三\"}")
                .expectStatus().isEqualTo(409);

        // 显式确认 → 关联为成员；绝不触碰既有凭据（seed 的占位哈希必须原样）
        Map<String, Object> data = createAccount(orgId, owner.cookie(),
                "{\"role\":\"staff\",\"storeId\":\"" + storeId + "\",\"email\":\"sa-worker@example.com\","
                        + "\"displayName\":\"张三\",\"confirmBindExisting\":true}")
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        data = (Map<String, Object>) data.get("data");

        assertThat(data.get("initialPassword")).isNull();
        assertThat(((Map<?, ?>) data.get("account")).get("id")).isEqualTo(existing.accountId());

        String hash = db.sql("SELECT password_hash FROM app_users WHERE id = CAST(:id AS uuid)")
                .bind("id", existing.accountId())
                .map(r -> r.get("password_hash", String.class)).one().block();
        assertThat(hash).isEqualTo("x");
    }

    @Test
    void reviewToggle_switchesStaffCreationBetweenPendingAndActive() {
        var owner = seedAccount("sa-review@example.com");
        String cookie = owner.cookie();
        String orgId = createOrg(cookie, "审核主体");
        String storeId = createStore(orgId, cookie, "分店A");

        toggleReview(orgId, cookie, true);

        var manager = seedManagerViaStore(orgId, storeId, cookie, "sa-rev-mgr@example.com", "李店长");

        Map<String, Object> created = createStoreAccount(orgId, storeId, manager.cookie(),
                "sa-pending@example.com", "新员工").expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        created = (Map<String, Object>) created.get("data");
        assertThat(((Map<?, ?>) created.get("account")).get("status")).isEqualTo("pending_review");

        // 审批放行 → active
        String pendingId = (String) ((Map<?, ?>) created.get("account")).get("id");
        review(orgId, pendingId, cookie, "approve").expectStatus().isOk();
        assertThat(statusOf(pendingId)).isEqualTo("active");

        // 开关关闭的对照：owner 直建（ADMIN+ 永不 pending，D6）
        toggleReview(orgId, cookie, false);
        String storeB = createStore(orgId, cookie, "分店B");
        Map<String, Object> offCase = createAccount(orgId, cookie,
                staffJson(storeB, "sa-instant@example.com", "即启用员工"))
                .expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        offCase = (Map<String, Object>) offCase.get("data");
        assertThat(((Map<?, ?>) offCase.get("account")).get("status")).isEqualTo("active");

        // 列表带账号状态（任务书 #48 审核闭环的读侧契约）
        client().get().uri("/api/organizations/" + orgId + "/stores/" + storeB + "/memberships")
                .header("Cookie", "y1.sid=" + cookie).exchange()
                .expectStatus().isOk().expectBody()
                .jsonPath("$.data[?(@.role=='staff')].accountStatus").isEqualTo("active");
    }

    // ---------- 四守卫 ----------

    @Test
    void guard_selfOperation_and_ownerProtection() {
        var owner = seedAccount("sa-guard@example.com");
        String orgId = createOrg(owner.cookie(), "守卫主体");

        // ③ 自操作拒绝
        suspend(orgId, owner.accountId(), owner.cookie()).expectStatus().isForbidden();

        // ② OWNER 账号不可被其他管理员停用
        var secondAdmin = seedAccount("sa-guard-admin@example.com");
        grantOrgRole(orgId, owner.cookie(), secondAdmin.accountId(), "admin");
        suspend(orgId, owner.accountId(), secondAdmin.cookie()).expectStatus().isForbidden()
                .expectBody().jsonPath("$.error").isEqualTo("商家主体所有者的账号不可被停用");
    }

    @Test
    void guard_lastActiveManagerCannotBeSuspended_thenRoundTripWorks() {
        var owner = seedAccount("sa-lastmgr@example.com");
        String cookie = owner.cookie();
        String orgId = createOrg(cookie, "末位经理主体");
        String storeId = createStore(orgId, cookie, "独苗店");

        Map<String, Object> mgr = createAccount(orgId, cookie,
                managerJson(storeId, "sa-lm@example.com", "唯一经理"))
                .expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        mgr = (Map<String, Object>) mgr.get("data");
        String mgrId = (String) ((Map<?, ?>) mgr.get("account")).get("id");

        // ① 店内仅此一名经理 → 停用被拒
        suspend(orgId, mgrId, cookie).expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("不能停用最后一个可用门店经理");

        // 补第二位 active 经理后停用成功；恢复一次后再恢复报冲突
        createAccount(orgId, cookie, managerJson(storeId, "sa-lm2@example.com", "替补经理"))
                .expectStatus().isCreated();

        suspend(orgId, mgrId, cookie).expectStatus().isOk();
        assertThat(statusOf(mgrId)).isEqualTo("suspended");

        restore(orgId, mgrId, cookie).expectStatus().isOk();
        assertThat(statusOf(mgrId)).isEqualTo("active");
        restore(orgId, mgrId, cookie).expectStatus().isEqualTo(409);
    }

    // ---------- 纯门店经理权限边界 ----------

    @Test
    void storeManager_canManageOwnStaff_butForeignAccountsAre404() {
        var owner = seedAccount("sa-bnd@example.com");
        String cookie = owner.cookie();
        String orgId = createOrg(cookie, "边界主体");
        String storeId = createStore(orgId, cookie, "边界店");

        var manager = seedManagerViaStore(orgId, storeId, cookie, "sa-bnd-mgr@example.com", "边界店长");
        var outsider = seedAccount("sa-bnd-out@example.com");

        Map<String, Object> staff = createStoreAccount(orgId, storeId, manager.cookie(),
                "sa-bnd-staff@example.com", "受管员工").expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        staff = (Map<String, Object>) staff.get("data");
        String staffId = (String) ((Map<?, ?>) staff.get("account")).get("id");

        // 店长可停用本店员工（免审默认）
        suspend(orgId, staffId, manager.cookie()).expectStatus().isOk();
        assertThat(statusOf(staffId)).isEqualTo("suspended");

        // 恢复本店员工也由店长直接操作（用户拍板②）
        restore(orgId, staffId, manager.cookie()).expectStatus().isOk();

        // 对组织外的账号 → 404 跨主体隔离
        suspend(orgId, outsider.accountId(), manager.cookie()).expectStatus().isNotFound();
    }

    // ---------- reject 终态 ----------

    @Test
    void reviewReject_isTerminal_restoreThenFails() {
        var owner = seedAccount("sa-term@example.com");
        String cookie = owner.cookie();
        String orgId = createOrg(cookie, "终态主体");
        String storeId = createStore(orgId, cookie, "终态店");
        var manager = seedManagerViaStore(orgId, storeId, cookie, "sa-term-mgr@example.com", "终态店长");

        toggleReview(orgId, cookie, true);

        Map<String, Object> created = createStoreAccount(orgId, storeId, manager.cookie(),
                "sa-term-staff@example.com", "待拒员工").expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        created = (Map<String, Object>) created.get("data");
        String targetId = (String) ((Map<?, ?>) created.get("account")).get("id");

        review(orgId, targetId, cookie, "reject").expectStatus().isOk();
        assertThat(statusOf(targetId)).isEqualTo("rejected");

        // rejected 是终态：restore 不复活
        restore(orgId, targetId, cookie).expectStatus().isEqualTo(409);
    }

    // ---------- helpers ----------

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec createAccount(
            String orgId, String cookie, String json) {
        return client().post().uri("/api/organizations/" + orgId + "/accounts")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue(json).exchange();
    }

    private String managerJson(String storeId, String email, String displayName) {
        return "{\"role\":\"manager\",\"storeId\":\"" + storeId + "\",\"email\":\"" + email
                + "\",\"displayName\":\"" + displayName + "\"}";
    }

    private String staffJson(String storeId, String email, String displayName) {
        return "{\"role\":\"staff\",\"storeId\":\"" + storeId + "\",\"email\":\"" + email
                + "\",\"displayName\":\"" + displayName + "\"}";
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec createStoreAccount(
            String orgId, String storeId, String cookie, String email, String displayName) {
        return client().post()
                .uri("/api/organizations/" + orgId + "/stores/" + storeId + "/accounts")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"role\":\"staff\",\"email\":\"" + email + "\",\"displayName\":\"" + displayName
                        + "\"}").exchange();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec suspend(
            String orgId, String accountId, String cookie) {
        return client().post()
                .uri("/api/organizations/" + orgId + "/accounts/" + accountId + "/suspend")
                .header("Cookie", "y1.sid=" + cookie).exchange();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec restore(
            String orgId, String accountId, String cookie) {
        return client().post()
                .uri("/api/organizations/" + orgId + "/accounts/" + accountId + "/restore")
                .header("Cookie", "y1.sid=" + cookie).exchange();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec review(
            String orgId, String accountId, String cookie, String decision) {
        return client().post()
                .uri("/api/organizations/" + orgId + "/accounts/" + accountId + "/review")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"decision\":\"" + decision + "\"}").exchange();
    }

    private void toggleReview(String orgId, String cookie, boolean required) {
        client().patch().uri("/api/organizations/" + orgId + "/member-review-required")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"required\":" + required + "}")
                .exchange().expectStatus().isOk();
    }

    /** 借既有门店成员端点把一个新造账号任命为本店经理（ADMIN+ 门禁），返回其登录态。 */
    private IdentityItSupport.Seeded seedManagerViaStore(String orgId, String storeId, String ownerCookie,
            String email, String displayName) {
        var seeded = seedAccount(email);
        db.sql("UPDATE app_users SET display_name = :name WHERE id = CAST(:id AS uuid)")
                .bind("name", displayName).bind("id", seeded.accountId()).then().block();
        client().post().uri("/api/organizations/" + orgId + "/stores/" + storeId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + ownerCookie)
                .bodyValue("{\"accountId\":\"" + seeded.accountId() + "\",\"role\":\"manager\"}")
                .exchange().expectStatus().isCreated();
        return seeded;
    }

    private void grantOrgRole(String orgId, String operatorCookie, String accountId, String role) {
        client().post().uri("/api/organizations/" + orgId + "/memberships")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + operatorCookie)
                .bodyValue("{\"accountId\":\"" + accountId + "\",\"role\":\"" + role + "\"}")
                .exchange().expectStatus().isCreated();
    }

    private String statusOf(String accountId) {
        return db.sql("SELECT status FROM app_users WHERE id = CAST(:id AS uuid)")
                .bind("id", accountId)
                .map(r -> r.get("status", String.class)).one().block();
    }
}
