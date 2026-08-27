package com.grassland.identity.organization;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 成员账号前缀改名收归运营的端到端（任务书 #51 第 1 条）。
 *
 * <p>核心断言不是「前缀列变了」，而是<b>连带重写正确</b>：成员登录名跟着改、占位邮箱跟着改、
 * <b>已绑真实邮箱的成员邮箱不动</b>。只改前缀列会让存量成员的登录名永远停在旧前缀上——
 * 那是登录直接坏掉的形态，本文件的第一个用例就是为它存在。
 */
class OrganizationPrefixAdminIT extends IdentityItSupport {

    @Test
    void renameRewritesMemberLoginNames_andLeavesBoundEmailsUntouched() {
        var owner = seedAccount("pfx-owner@example.com");
        var admin = seedAdmin("pfx-admin@example.com");
        String orgId = createOrg(owner.cookie(), "前缀改名主体");
        String storeId = createStore(orgId, owner.cookie(), "前缀店");
        String oldPrefix = accountPrefix(orgId);

        // 两个成员：staff（留占位邮箱）+ member（随后绑真实邮箱，用于验证「邮箱不动」）
        String staffId = createAccount(orgId, owner.cookie(),
                "{\"role\":\"staff\",\"storeId\":\"" + storeId + "\",\"loginName\":\"pfxstaff\","
                        + "\"displayName\":\"店员甲\"}");
        String memberId = createAccount(orgId, owner.cookie(),
                "{\"role\":\"member\",\"loginName\":\"pfxmember\",\"displayName\":\"成员乙\"}");
        // 模拟成员已自行绑定真实邮箱（#49 D10 的换绑结果形态）
        db.sql("UPDATE app_users SET email = :email WHERE id = CAST(:id AS uuid)")
                .bind("email", "pfx-real-bound@example.com").bind("id", memberId).then().block();

        assertThat(usernameOf(staffId)).isEqualTo(oldPrefix + "-pfxstaff");
        assertThat(emailOf(staffId)).isEqualTo(oldPrefix + "-pfxstaff@sub.grassland.invalid");

        Map<String, Object> data = dataOf(patchPrefix(orgId, admin.cookie(), "milkshop")
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody());
        assertThat(data.get("prefix")).isEqualTo("milkshop");
        // 影响面回显：两个成员的登录名都改了，但只有 staff 的占位邮箱被改
        assertThat(((Number) data.get("rewrittenAccounts")).longValue()).isEqualTo(2L);
        assertThat(((Number) data.get("rewrittenPlaceholderEmails")).longValue()).isEqualTo(1L);

        // 前缀列 + 两个成员登录名全部重写
        assertThat(accountPrefix(orgId)).isEqualTo("milkshop");
        assertThat(usernameOf(staffId)).isEqualTo("milkshop-pfxstaff");
        assertThat(usernameOf(memberId)).isEqualTo("milkshop-pfxmember");
        // 占位邮箱跟着改；已绑真实邮箱的成员邮箱原样（他的邮箱是本人资产，与前缀无关）
        assertThat(emailOf(staffId)).isEqualTo("milkshop-pfxstaff@sub.grassland.invalid");
        assertThat(emailOf(memberId)).isEqualTo("pfx-real-bound@example.com");
        // owner 是注册用户（无 account_username 行），邮箱不受影响
        assertThat(emailOf(owner.accountId())).isEqualTo("pfx-owner@example.com");

        // outbox 留痕（运营动作可审计）
        Integer events = db.sql("SELECT COUNT(*)::int AS c FROM outbox"
                        + " WHERE event_type = 'OrganizationAccountPrefixChanged'"
                        + " AND payload->>'organizationId' = :org")
                .bind("org", orgId).map(r -> r.get("c", Integer.class)).one().block();
        assertThat(events).isGreaterThanOrEqualTo(1);
    }

    /**
     * 改名后成员用<b>新账号名</b>能真登录（拍板 C：不动会话，但登录标识已换）。
     *
     * <p>刻意只打成功登录：登录限流按 IP 计失败次数（10 次/60s）且 ITs 共享 JVM+IP，
     * 成功登录会 decrement 而失败会累积——旧账号名失效改在数据层断言（旁表已无该行，
     * 这正是 {@code UserLookup.findByIdentifier} 查不到的原因），不额外消耗失败配额。
     */
    @Test
    void renamedMemberLogsInWithNewUsername_oldUsernameRowGone() {
        var owner = seedAccount("pfx-login-owner@example.com");
        var admin = seedAdmin("pfx-login-admin@example.com");
        String orgId = createOrg(owner.cookie(), "改名登录主体");
        String storeId = createStore(orgId, owner.cookie(), "登录店");
        String oldPrefix = accountPrefix(orgId);

        Map<String, Object> created = createAccountBody(orgId, owner.cookie(),
                "{\"role\":\"staff\",\"storeId\":\"" + storeId + "\",\"loginName\":\"loginguy\","
                        + "\"displayName\":\"登录测试\"}");
        String password = (String) created.get("initialPassword");
        String oldUsername = oldPrefix + "-loginguy";

        patchPrefix(orgId, admin.cookie(), "newshop").expectStatus().isOk();

        // 新账号名可登录，且响应带首登改密标记（子账号形态未被改名破坏）
        client().post().uri("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"email\":\"newshop-loginguy\",\"password\":\"" + password + "\"}")
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.user.username").isEqualTo("newshop-loginguy")
                .jsonPath("$.data.user.mustChangePassword").isEqualTo(true);

        // 旧账号名在旁表已不存在 —— findByIdentifier 因此 miss，登录 401
        Integer oldRows = db.sql("SELECT COUNT(*)::int AS c FROM account_username WHERE username = :name")
                .bind("name", oldUsername).map(r -> r.get("c", Integer.class)).one().block();
        assertThat(oldRows).isZero();
    }

    @Test
    void prefixValidation_conflictAndSameValueAndAuthz() {
        var owner = seedAccount("pfx-guard-owner@example.com");
        var admin = seedAdmin("pfx-guard-admin@example.com");
        String orgId = createOrg(owner.cookie(), "前缀守卫主体");

        // 非法格式 400（连字符是 #49 明确的禁例：账号名靠它分段）
        patchPrefix(orgId, admin.cookie(), "grass-milk").expectStatus().isBadRequest();
        patchPrefix(orgId, admin.cookie(), "ab").expectStatus().isBadRequest();

        // 与当前值相同 400（无意义写不进事务）
        patchPrefix(orgId, admin.cookie(), accountPrefix(orgId)).expectStatus().isBadRequest();

        // 被其他主体占用 409
        var otherOwner = seedAccount("pfx-guard-other@example.com");
        String otherOrg = createOrg(otherOwner.cookie(), "占位主体");
        patchPrefix(orgId, admin.cookie(), accountPrefix(otherOrg)).expectStatus().isEqualTo(409);

        // 商家自己（org owner，非平台 admin）改不动：这是本任务书的要点
        patchPrefix(orgId, owner.cookie(), "ownertry").expectStatus().isForbidden();
        assertThat(accountPrefix(orgId)).isNotEqualTo("ownertry");

        // 不存在的组织 404
        patchPrefix("00000000-0000-0000-0000-000000000000", admin.cookie(), "ghostorg")
                .expectStatus().isNotFound();
    }

    @Test
    void merchantSidePrefixPatchIsGone_readEndpointKept() {
        var owner = seedAccount("pfx-removed@example.com");
        String orgId = createOrg(owner.cookie(), "端点已删主体");

        // 任务书 #51：商家侧 PATCH 已删除（该路径上只剩 GET → 405）
        client().patch().uri("/api/organizations/" + orgId + "/account-prefix")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"prefix\":\"tryme\"}")
                .exchange().expectStatus().isEqualTo(405);

        // 读端点保留（多店建号表单仍需预览前缀）
        client().get().uri("/api/organizations/" + orgId + "/account-prefix")
                .header("Cookie", "y1.sid=" + owner.cookie()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.data.prefix").isNotEmpty();
    }

    @Test
    void adminSearch_matchesNameAndPrefix_escapesWildcards_andRequiresAdmin() {
        var owner = seedAccount("pfx-search-owner@example.com");
        var admin = seedAdmin("pfx-search-admin@example.com");
        String orgId = createOrg(owner.cookie(), "搜索专用奶茶铺");
        String storeId = createStore(orgId, owner.cookie(), "搜索店");
        createAccount(orgId, owner.cookie(),
                "{\"role\":\"staff\",\"storeId\":\"" + storeId + "\",\"loginName\":\"searchstaff\","
                        + "\"displayName\":\"搜索店员\"}");
        String prefix = accountPrefix(orgId);

        // 按主体名命中，且带出成员数（= 改前缀的影响面）
        Map<String, Object> hit = rowsOf(search(admin.cookie(), "搜索专用奶茶铺")).stream()
                .filter(row -> orgId.equals(row.get("id"))).findFirst().orElseThrow();
        assertThat(hit.get("name")).isEqualTo("搜索专用奶茶铺");
        assertThat(hit.get("accountPrefix")).isEqualTo(prefix);
        assertThat(((Number) hit.get("memberCount")).intValue()).isGreaterThanOrEqualTo(1);

        // 按前缀命中
        assertThat(rowsOf(search(admin.cookie(), prefix)))
                .anySatisfy(row -> assertThat(row.get("id")).isEqualTo(orgId));

        // LIKE 元字符被转义：'%' 不再是通配，命不中而不是命中全部
        assertThat(rowsOf(search(admin.cookie(), "%")))
                .noneSatisfy(row -> assertThat(row.get("id")).isEqualTo(orgId));

        // 非 admin 403（商家不得枚举全平台主体）
        search(owner.cookie(), "搜索").expectStatus().isForbidden();
    }

    // ---------- helpers ----------

    private WebTestClient.ResponseSpec patchPrefix(String orgId, String cookie, String prefix) {
        return client().patch().uri("/api/admin/organizations/" + orgId + "/account-prefix")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue("{\"prefix\":\"" + prefix + "\"}").exchange();
    }

    private WebTestClient.ResponseSpec search(String cookie, String query) {
        return client().get()
                .uri(builder -> builder.path("/api/admin/organizations").queryParam("q", query).build())
                .header("Cookie", "y1.sid=" + cookie).exchange();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rowsOf(WebTestClient.ResponseSpec spec) {
        Map<String, Object> body = spec.expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (List<Map<String, Object>>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("data");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createAccountBody(String orgId, String cookie, String json) {
        Map<String, Object> body = client().post().uri("/api/organizations/" + orgId + "/accounts")
                .contentType(MediaType.APPLICATION_JSON).header("Cookie", "y1.sid=" + cookie)
                .bodyValue(json).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) body.get("data");
    }

    /** 建号并返回 accountId。 */
    @SuppressWarnings("unchecked")
    private String createAccount(String orgId, String cookie, String json) {
        Map<String, Object> data = createAccountBody(orgId, cookie, json);
        return (String) ((Map<String, Object>) data.get("account")).get("id");
    }

    private String usernameOf(String accountId) {
        return db.sql("SELECT username FROM account_username WHERE account_id = CAST(:id AS uuid)")
                .bind("id", accountId).map(r -> r.get("username", String.class)).one().block();
    }

    private String emailOf(String accountId) {
        return db.sql("SELECT email FROM app_users WHERE id = CAST(:id AS uuid)")
                .bind("id", accountId).map(r -> r.get("email", String.class)).one().block();
    }

    private String accountPrefix(String orgId) {
        return db.sql("SELECT account_prefix FROM organization WHERE id = CAST(:id AS uuid)")
                .bind("id", orgId).map(r -> r.get("account_prefix", String.class)).one().block();
    }
}
