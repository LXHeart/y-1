package com.grassland.identity.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.identity.IdentityItSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 平台级账号/组织管控五件套端到端（任务书 #72 卡 B，D3/D6/D7/D8）。
 *
 * <p>
 * 覆盖：①suspend 后登录 403 + 断言链 403；②owner 停用连带冻结组织 + 成员组织范围排除；
 * ③账号/组织分别恢复；④非法迁移 409；⑤自停 400；⑥reset-password（明文一次性 + 首登改密 +
 * 旧会话失效 + 新密码可登录）；⑦cs/risk 403、匿名 401；⑧outbox 事件落库（含 frozenOrgIds）。
 *
 * <p>
 * 共享单例容器数据累积：outbox/organization 断言一律按本用例的 orgId/accountId 限定。
 */
class AdminAccountAdminIT extends IdentityItSupport {

    @Test
    void suspendBlocksLoginImmediatelyAndAssertionChainRejectsExistingSession() {
        var admin = seedAdmin(uniqueEmail("sus-admin"));
        String targetEmail = uniqueEmail("sus-target");
        Seeded target = seedAccount(targetEmail);
        // 登录拦截在密码校验之后（LoginController.attemptLogin），先给目标账号一个真实密码
        String password = resetPasswordOk(admin.cookie(), target.accountId());

        // 新密码可登录并建一个新会话（seed 会话已被 reset-password 物理删除）
        String liveCookie = login(targetEmail, password);
        assertThat(liveCookie).isNotBlank();

        suspend(admin.cookie(), target.accountId()).expectStatus().isOk().expectBody()
                .jsonPath("$.data.suspended").isEqualTo(true);

        // 密码正确 + suspended → 登录 403（不再是 401，让用户知道找谁解封）
        loginRaw(targetEmail, password).expectStatus().isForbidden();
        // 断言链自查 app_users.status（identity 是权威）：带既有会话 cookie 的请求立即 403
        client().get().uri("/api/me/organization-scopes").header("Cookie", "y1.sid=" + liveCookie)
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void suspendingOwnerFreezesOwnedOrganizationsAndHidesThemFromMemberScopes() {
        var admin = seedAdmin(uniqueEmail("own-admin"));
        Seeded owner = seedAccount(uniqueEmail("own-owner"));
        String orgA = insertOrg(owner.accountId(), "连带冻结甲");
        String orgB = insertOrg(owner.accountId(), "连带冻结乙");
        Seeded member = seedAccount(uniqueEmail("own-member"));
        insertMembership(orgA, member.accountId());

        // 冻结前：成员范围含 orgA
        assertThat(organizationScopeIds(member.cookie())).contains(orgA);

        // 停用 owner → 名下两个仍 active 的组织连带冻结，事件带冻结清单
        suspend(admin.cookie(), owner.accountId()).expectStatus().isOk().expectBody()
                .jsonPath("$.data.frozenOrganizationIds.length()").isEqualTo(2);
        assertThat(orgStatus(orgA)).isEqualTo("suspended");
        assertThat(orgStatus(orgB)).isEqualTo("suspended");
        // 成员侧范围不再含该组织；owner 已被停用，其会话在断言链上直接 403（账号级拦截先于范围解析）
        assertThat(organizationScopeIds(member.cookie())).doesNotContain(orgA);
        client().get().uri("/api/me/organization-scopes").header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isForbidden();
        // ⑧ outbox：AccountSuspended 带 frozenOrgIds
        Map<String, Object> payload = outboxPayload("AccountSuspended", owner.accountId());
        assertThat((List<String>) payload.get("frozenOrgIds")).containsExactlyInAnyOrder(orgA, orgB);
        assertThat(payload.get("operatorId")).isEqualTo(admin.accountId());
        // 成员关系原样保留（D6：停用不触碰 organization_membership 行）
        assertThat(membershipRowExists(orgA, member.accountId())).isTrue();
    }

    @Test
    void accountAndOrganizationRestoreSeparately() {
        var admin = seedAdmin(uniqueEmail("res-admin"));
        Seeded owner = seedAccount(uniqueEmail("res-owner"));
        String orgId = insertOrg(owner.accountId(), "分别恢复牧场");
        Seeded member = seedAccount(uniqueEmail("res-member"));
        insertMembership(orgId, member.accountId());
        suspend(admin.cookie(), owner.accountId()).expectStatus().isOk();
        assertThat(orgStatus(orgId)).isEqualTo("suspended");

        // 恢复账号：组织不随账号恢复（D3 分别恢复——组织冻结是独立处置）
        restoreUser(admin.cookie(), owner.accountId()).expectStatus().isOk();
        assertThat(accountStatus(owner.accountId())).isEqualTo("active");
        assertThat(orgStatus(orgId)).isEqualTo("suspended");
        assertThat(organizationScopeIds(member.cookie())).doesNotContain(orgId);
        assertThat(outboxEventTypeExists("AccountRestored", owner.accountId())).isTrue();

        // 单独恢复组织：成员范围重新可见；OrganizationRestored 落库
        restoreOrg(admin.cookie(), orgId).expectStatus().isOk();
        assertThat(orgStatus(orgId)).isEqualTo("active");
        assertThat(organizationScopeIds(member.cookie())).contains(orgId);
        assertThat(outboxEventTypeExists("OrganizationRestored", orgId)).isTrue();
    }

    @Test
    void invalidTransitionsRejected() {
        var admin = seedAdmin(uniqueEmail("inv-admin"));
        Seeded target = seedAccount(uniqueEmail("inv-target"));

        // active 停两次：第二次 409「已是该状态」
        suspend(admin.cookie(), target.accountId()).expectStatus().isOk();
        suspend(admin.cookie(), target.accountId()).expectStatus().isEqualTo(409).expectBody()
                .jsonPath("$.error").isEqualTo("已是该状态");
        // restore 一个 active 账号 → 409
        Seeded active = seedAccount(uniqueEmail("inv-active"));
        restoreUser(admin.cookie(), active.accountId()).expectStatus().isEqualTo(409);
        // 组织：active 再冻结 → 409；不存在 → 404；非法 UUID → 400
        Seeded owner = seedAccount(uniqueEmail("inv-owner"));
        String orgId = insertOrg(owner.accountId(), "非法迁移牧场");
        client().post().uri("/api/admin/organizations/" + orgId + "/suspend")
                .header("Cookie", "y1.sid=" + admin.cookie()).exchange().expectStatus().isOk();
        client().post().uri("/api/admin/organizations/" + orgId + "/suspend")
                .header("Cookie", "y1.sid=" + admin.cookie()).exchange().expectStatus().isEqualTo(409);
        client().post().uri("/api/admin/organizations/" + UUID.randomUUID() + "/suspend")
                .header("Cookie", "y1.sid=" + admin.cookie()).exchange().expectStatus().isNotFound();
        client().post().uri("/api/admin/organizations/not-a-uuid/suspend")
                .header("Cookie", "y1.sid=" + admin.cookie()).exchange().expectStatus().isBadRequest();
    }

    @Test
    void operatorCannotSuspendSelf() {
        var admin = seedAdmin(uniqueEmail("self-admin"));
        suspend(admin.cookie(), admin.accountId()).expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error").isEqualTo("不能停用自己的账号");
    }

    @Test
    void resetPasswordReturnsPlaintextKillsSessionsAndForcesChange() {
        var admin = seedAdmin(uniqueEmail("rp-admin"));
        String targetEmail = uniqueEmail("rp-target");
        Seeded target = seedAccount(targetEmail);

        Map<String, Object> data = resetPasswordRaw(admin.cookie(), target.accountId()).expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        String initialPassword = ((Map<String, Object>) ((Map<String, Object>) data).get("data"))
                .get("initialPassword").toString();
        assertThat(initialPassword).hasSize(16).doesNotContain(" ");

        // 首登强制改密置位
        Boolean mustChange = db.sql("SELECT must_change_password FROM account_flag"
                        + " WHERE account_id = CAST(:id AS uuid)")
                .bind("id", target.accountId()).map(r -> r.get(0, Boolean.class)).one().block();
        assertThat(mustChange).isTrue();
        // 旧会话（seed 会话）已物理删除 → 401
        client().get().uri("/api/me/organization-scopes").header("Cookie", "y1.sid=" + target.cookie())
                .exchange().expectStatus().isUnauthorized();
        // 新密码可登录且响应带强制改密标记
        loginRaw(targetEmail, initialPassword).expectStatus().isOk().expectBody()
                .jsonPath("$.data.user.mustChangePassword").isEqualTo(true);
        // ⑧ outbox：AccountPasswordReset 存在且 payload 不含明文
        Map<String, Object> payload = outboxPayload("AccountPasswordReset", target.accountId());
        assertThat(payload).doesNotContainKey("initialPassword");
        // 自重置被拒（对齐 OrgSubAccount 先例语义）
        resetPasswordRaw(admin.cookie(), admin.accountId()).expectStatus().isForbidden();
    }

    @Test
    void controlEndpointsArePlatformAdminOnly() {
        var cs = seedBackendRoleAccount(uniqueEmail("perm-cs"), "customer_service");
        var risk = seedBackendRoleAccount(uniqueEmail("perm-risk"), "risk");
        Seeded target = seedAccount(uniqueEmail("perm-target"));

        suspend(cs.cookie(), target.accountId()).expectStatus().isForbidden();
        suspend(risk.cookie(), target.accountId()).expectStatus().isForbidden();
        restoreUser(cs.cookie(), target.accountId()).expectStatus().isForbidden();
        resetPasswordRaw(cs.cookie(), target.accountId()).expectStatus().isForbidden();
        String orgId = insertOrg(target.accountId(), "权限牧场");
        client().post().uri("/api/admin/organizations/" + orgId + "/suspend")
                .header("Cookie", "y1.sid=" + cs.cookie()).exchange().expectStatus().isForbidden();
        // 匿名 401
        suspend(null, target.accountId()).expectStatus().isUnauthorized();
    }

    // ---- 造数与请求辅助 ----

    private Seeded seedBackendRoleAccount(String email, String role) {
        Seeded seeded = seedAccount(email);
        db.sql("INSERT INTO backend_role(account_id, role) VALUES (CAST(:id AS uuid), :role)")
                .bind("id", seeded.accountId()).bind("role", role).then().block();
        return seeded;
    }

    /** 直插组织（account_prefix NOT NULL+UNIQUE，V43），返回 orgId。 */
    private String insertOrg(String ownerId, String name) {
        String orgId = UUID.randomUUID().toString();
        String prefix = "it" + orgId.replace("-", "").substring(0, 8);
        db.sql("INSERT INTO organization(id, owner_account_id, name, account_prefix) "
                + "VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), :name, :prefix)")
                .bind("id", orgId).bind("owner", ownerId).bind("name", name).bind("prefix", prefix)
                .then().block();
        return orgId;
    }

    private void insertMembership(String orgId, String accountId) {
        db.sql("INSERT INTO organization_membership(id, organization_id, account_id, role) "
                + "VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:acct AS uuid), 'member')")
                .bind("id", UUID.randomUUID().toString()).bind("org", orgId).bind("acct", accountId)
                .then().block();
    }

    private WebTestClient.ResponseSpec suspend(String cookie, String targetId) {
        return post(cookie, "/api/admin/users/" + targetId + "/suspend");
    }

    private WebTestClient.ResponseSpec restoreUser(String cookie, String targetId) {
        return post(cookie, "/api/admin/users/" + targetId + "/restore");
    }

    private WebTestClient.ResponseSpec restoreOrg(String cookie, String orgId) {
        return post(cookie, "/api/admin/organizations/" + orgId + "/restore");
    }

    private WebTestClient.ResponseSpec resetPasswordRaw(String cookie, String targetId) {
        return post(cookie, "/api/admin/users/" + targetId + "/reset-password");
    }

    private WebTestClient.ResponseSpec post(String cookie, String uri) {
        var spec = client().post().uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (cookie != null) {
            spec = spec.header("Cookie", "y1.sid=" + cookie);
        }
        return spec.exchange();
    }

    /** 重置密码并断言成功，返回一次性明文。 */
    private String resetPasswordOk(String cookie, String targetId) {
        Map<String, Object> body = resetPasswordRaw(cookie, targetId).expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return (String) data.get("initialPassword");
    }

    private WebTestClient.ResponseSpec loginRaw(String email, String password) {
        return client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}").exchange();
    }

    /** 登录成功后取回 Set-Cookie 里的 y1.sid 值（含签名，可直接回带）。 */
    private String login(String email, String password) {
        List<String> setCookie = loginRaw(email, password).expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseHeaders().get("Set-Cookie");
        assertThat(setCookie).isNotNull();
        return setCookie.stream().filter(c -> c.startsWith("y1.sid=")).findFirst()
                .map(c -> c.split(";")[0].substring("y1.sid=".length())).orElse(null);
    }

    @SuppressWarnings("unchecked")
    private List<String> organizationScopeIds(String cookie) {
        Map<String, Object> body = client().get().uri("/api/me/organization-scopes")
                .header("Cookie", "y1.sid=" + cookie)
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        return ((List<Map<String, Object>>) body.get("data")).stream()
                .map(scope -> (String) scope.get("organizationId")).toList();
    }

    private String orgStatus(String orgId) {
        return db.sql("SELECT status FROM organization WHERE id = CAST(:id AS uuid)")
                .bind("id", orgId).map(r -> r.get(0, String.class)).one().block();
    }

    private String accountStatus(String accountId) {
        return db.sql("SELECT status FROM app_users WHERE id = CAST(:id AS uuid)")
                .bind("id", accountId).map(r -> r.get(0, String.class)).one().block();
    }

    private boolean membershipRowExists(String orgId, String accountId) {
        Long count = db.sql("SELECT count(*) FROM organization_membership"
                        + " WHERE organization_id = CAST(:org AS uuid) AND account_id = CAST(:acct AS uuid)")
                .bind("org", orgId).bind("acct", accountId).map(r -> r.get(0, Long.class)).one().block();
        return count != null && count > 0;
    }

    /** 取该账号指定事件类型的最新 payload（aggregate_id = accountId）；jsonb 落库读 text 再解析，规避驱动映射形态差异。 */
    private Map<String, Object> outboxPayload(String eventType, String aggregateId) {
        String json = db.sql("SELECT payload::text AS p FROM outbox"
                        + " WHERE event_type = :type AND aggregate_id = :agg"
                        + " ORDER BY created_at DESC LIMIT 1")
                .bind("type", eventType).bind("agg", aggregateId)
                .map(r -> r.get("p", String.class)).one().block();
        assertThat(json).isNotNull();
        try {
            return new ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("outbox payload 解析失败: " + json, e);
        }
    }

    private boolean outboxEventTypeExists(String eventType, String aggregateId) {
        Long count = db.sql("SELECT count(*) FROM outbox WHERE event_type = :type AND aggregate_id = :agg")
                .bind("type", eventType).bind("agg", aggregateId).map(r -> r.get(0, Long.class)).one().block();
        return count != null && count > 0;
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
