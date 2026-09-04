package com.grassland.identity.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.grassland.identity.IdentityItSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 治理台用户列表富化/筛选/查看类权限（任务书 #72 卡 A）。
 *
 * <p>
 * 覆盖：①cs/risk 可读列表、普通账号 403 / 匿名 401；②identities 聚合（三身份账号 + 裸推荐官）；
 * ③status / identityType 筛选；④非法 identityType 400；⑤旧调用（无新参）行为不变。
 *
 * <p>
 * 共享单例容器数据累积：断言一律用 {@code q=<唯一 email>} 钉住自己的行，不依赖全局行数。
 */
class AdminUserListingEnrichmentIT extends IdentityItSupport {

    @BeforeEach
    void stubBalances() {
        // 列表 zips fetchBalances；@MockitoBean 未 stub 的 Mono 方法回 null → NPE，必须给默认值
        when(financeCreditsAdminClient.fetchBalances(any())).thenReturn(Mono.just(Map.of()));
    }

    @Test
    void viewerRolesCanListWhilePlainUserCannot() {
        var admin = seedAdmin(uniqueEmail("admin"));
        var cs = seedBackendRoleAccount(uniqueEmail("cs"), "customer_service");
        var risk = seedBackendRoleAccount(uniqueEmail("risk"), "risk");
        var plain = seedAccount(uniqueEmail("plain"));

        list(cs.cookie(), null).expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true);
        list(risk.cookie(), null).expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true);
        list(admin.cookie(), null).expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true);
        list(plain.cookie(), null).expectStatus().isForbidden();
        list(null, null).expectStatus().isUnauthorized();
    }

    @Test
    void identitiesAggregationForTripleIdentityAndBareRecommender() {
        var admin = seedAdmin(uniqueEmail("agg-admin"));
        String tripleEmail = uniqueEmail("triple");
        Seeded triple = seedAccount(tripleEmail);
        String bareEmail = uniqueEmail("bare");
        Seeded bare = seedAccount(bareEmail);
        String orgName = "聚合牧场-" + UUID.randomUUID().toString().substring(0, 8);
        insertOrg(UUID.randomUUID().toString(), triple.accountId(), orgName);
        insertIdentityProfile(triple.accountId(), "recommender");
        insertIdentityProfile(triple.accountId(), "merchant");
        insertMembership(UUID.randomUUID().toString(), triple.accountId());
        insertIdentityProfile(bare.accountId(), "recommender");

        Map<String, Object> tripleItem = fetchItem(admin.cookie(), tripleEmail);
        Map<String, Object> tripleIdentities = identitiesOf(tripleItem);
        assertThat(tripleIdentities.get("recommender")).isEqualTo(true);
        assertThat(tripleIdentities.get("merchant")).isEqualTo(true);
        assertThat(tripleIdentities.get("member")).isEqualTo(true);
        assertThat(tripleIdentities.get("ownedOrgNames")).isEqualTo(orgName);
        // 既有字段不受富化影响
        assertThat(tripleItem.get("id")).isEqualTo(triple.accountId());
        assertThat(tripleItem.get("status")).isEqualTo("active");
        assertThat(tripleItem.get("roles")).isEqualTo(List.of());

        Map<String, Object> bareItem = fetchItem(admin.cookie(), bareEmail);
        Map<String, Object> bareIdentities = identitiesOf(bareItem);
        assertThat(bareIdentities.get("recommender")).isEqualTo(true);
        assertThat(bareIdentities.get("merchant")).isEqualTo(false);
        assertThat(bareIdentities.get("member")).isEqualTo(false);
        assertThat(bareIdentities.get("ownedOrgNames")).isNull();
    }

    @Test
    void statusFilterMatchesExactly() {
        var admin = seedAdmin(uniqueEmail("status-admin"));
        String activeEmail = uniqueEmail("status-active");
        seedAccount(activeEmail);
        String suspendedEmail = uniqueEmail("status-suspended");
        seedSuspendedAccount(suspendedEmail);

        assertThat(listItems(admin.cookie(), "q=" + activeEmail + "&status=active")).isNotEmpty();
        assertThat(listItems(admin.cookie(), "q=" + activeEmail + "&status=suspended")).isEmpty();
        assertThat(listItems(admin.cookie(), "q=" + suspendedEmail + "&status=suspended")).hasSize(1);
        assertThat(listItems(admin.cookie(), "q=" + suspendedEmail + "&status=active")).isEmpty();
    }

    @Test
    void identityTypeFilterHitsMatchingRows() {
        var admin = seedAdmin(uniqueEmail("idt-admin"));
        String merchantEmail = uniqueEmail("idt-merchant");
        Seeded merchant = seedAccount(merchantEmail);
        String memberEmail = uniqueEmail("idt-member");
        Seeded member = seedAccount(memberEmail);
        insertIdentityProfile(merchant.accountId(), "merchant");
        insertMembership(UUID.randomUUID().toString(), member.accountId());

        assertThat(listItems(admin.cookie(), "q=" + merchantEmail + "&identityType=merchant")).hasSize(1);
        assertThat(listItems(admin.cookie(), "q=" + merchantEmail + "&identityType=recommender")).isEmpty();
        assertThat(listItems(admin.cookie(), "q=" + merchantEmail + "&identityType=member")).isEmpty();
        assertThat(listItems(admin.cookie(), "q=" + memberEmail + "&identityType=member")).hasSize(1);
        assertThat(listItems(admin.cookie(), "q=" + memberEmail + "&identityType=merchant")).isEmpty();
    }

    @Test
    void invalidIdentityTypeRejectedAndLegacyCallUnchanged() {
        var admin = seedAdmin(uniqueEmail("legacy-admin"));
        String userEmail = uniqueEmail("legacy-user");
        seedAccount(userEmail);

        list(admin.cookie(), "identityType=influencer").expectStatus().isBadRequest().expectBody()
                .jsonPath("$.error").isEqualTo("identityType 仅支持 recommender/merchant/member");

        // 旧调用（无新参）：行为不变——能查到、含既有字段与新增 identities
        List<Map<String, Object>> items = listItems(admin.cookie(), "q=" + userEmail);
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsKeys("id", "email", "displayName", "role", "status", "createdAt",
                "balance", "totalEarned", "totalSpent", "roles", "identities");
    }

    // ---- 造数与请求辅助 ----

    /** seed 一个带指定后台角色的账号（app_users + backend_role + session）。 */
    private Seeded seedBackendRoleAccount(String email, String role) {
        Seeded seeded = seedAccount(email);
        db.sql("INSERT INTO backend_role(account_id, role) VALUES (CAST(:id AS uuid), :role)")
                .bind("id", seeded.accountId()).bind("role", role).then().block();
        return seeded;
    }

    /** seed 一个 suspended 账号（无登录态——被停用账号不该有可用会话）。 */
    private Seeded seedSuspendedAccount(String email) {
        String accountId = UUID.randomUUID().toString();
        db.sql("INSERT INTO app_users(id, email, password_hash, display_name, role, status) "
                + "VALUES (CAST(:id AS uuid), :email, 'x', 'Suspended Account', 'user', 'suspended')")
                .bind("id", accountId).bind("email", email).then().block();
        return new Seeded(null, accountId);
    }

    private void insertIdentityProfile(String accountId, String identityType) {
        db.sql("INSERT INTO identity_profile(id, account_id, identity_type) "
                + "VALUES (CAST(:id AS uuid), CAST(:account AS uuid), :type)")
                .bind("id", UUID.randomUUID().toString()).bind("account", accountId)
                .bind("type", identityType).then().block();
    }

    private void insertMembership(String membershipId, String accountId) {
        db.sql("INSERT INTO organization_membership(id, organization_id, account_id, role) "
                + "VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:account AS uuid), 'member')")
                .bind("id", membershipId).bind("org", UUID.randomUUID().toString())
                .bind("account", accountId).then().block();
    }

    private void insertOrg(String orgId, String ownerId, String name) {
        // account_prefix NOT NULL + UNIQUE（V43），IT 直插必须带（同款生成规则截短）
        String prefix = "it" + orgId.replace("-", "").substring(0, 8);
        db.sql("INSERT INTO organization(id, owner_account_id, name, account_prefix) "
                + "VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), :name, :prefix)")
                .bind("id", orgId).bind("owner", ownerId).bind("name", name).bind("prefix", prefix)
                .then().block();
    }

    private WebTestClient.ResponseSpec list(String cookie, String queryString) {
        var spec = client().get().uri("/api/admin/users" + (queryString == null ? "" : "?" + queryString));
        if (cookie != null) {
            spec = spec.header("Cookie", "y1.sid=" + cookie);
        }
        return spec.exchange();
    }

    /** 用 q=<email> 钉住唯一行（共享容器数据累积，不依赖分页顺序）。 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listItems(String cookie, String queryString) {
        Map<String, Object> body = list(cookie, queryString).expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(body).isNotNull();
        assertThat(body.get("success")).isEqualTo(true);
        Map<String, Object> data = (Map<String, Object>) body.get("data");
        return (List<Map<String, Object>>) data.get("items");
    }

    /** 用 q=<email> 钉住唯一行（共享容器数据累积，不依赖分页顺序）。 */
    private Map<String, Object> fetchItem(String cookie, String email) {
        List<Map<String, Object>> items = listItems(cookie, "q=" + email);
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> identitiesOf(Map<String, Object> item) {
        return (Map<String, Object>) item.get("identities");
    }

    private String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }
}
