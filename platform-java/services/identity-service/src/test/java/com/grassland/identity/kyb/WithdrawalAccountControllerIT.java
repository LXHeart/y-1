package com.grassland.identity.kyb;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.IdentityItSupport;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 收款账户。GL-P3-MERCHANT-001。
 *
 * <p>收款账户是资金出口，被改指向即等于直接资损。此前 update/delete/set-default 只按 id 定位、
 * 不带 organization_id 条件——任意组织 ADMIN 可改写他人收款账号。本类锁住越权与密文语义。
 */
class WithdrawalAccountControllerIT extends IdentityItSupport {

    @DynamicPropertySource
    static void kek(DynamicPropertyRegistry r) {
        r.add("crypto.kek.encoded", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    private static final String ACCOUNT_NUMBER = "6222021234567890123";

    @SuppressWarnings("unchecked")
    private String createAccount(String orgId, String cookie, String accountNumber) {
        var body = client().post().uri("/api/organizations/" + orgId + "/withdrawal-accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + cookie)
                .bodyValue("""
                        {"accountType":"bank","accountName":"草场测试商贸有限公司","accountNumber":"%s",
                         "bankName":"招商银行","branchName":"上海分行"}
                        """.formatted(accountNumber))
                .exchange()
                .expectStatus().isEqualTo(201)
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        return (String) ((Map<String, Object>) body.get("data")).get("id");
    }

    @Test
    @DisplayName("创建：账号密文入库，响应只回末 4 位")
    void createEncryptsAccountNumber() {
        var owner = seedAccount("wd-create-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "Withdrawal Create Org");

        String accountId = createAccount(orgId, owner.cookie(), ACCOUNT_NUMBER);

        client().get().uri("/api/organizations/" + orgId + "/withdrawal-accounts")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].accountNumberMasked").isEqualTo("****0123")
                .jsonPath("$.data[0].accountNumberEncrypted").doesNotExist()
                .jsonPath("$.data[0].accountNumber").doesNotExist();

        String stored = db.sql("SELECT account_number_encrypted FROM withdrawal_account WHERE id = CAST(:id AS uuid)")
                .bind("id", accountId).map(row -> row.get(0, String.class)).one().block();
        assertThat(stored).isNotNull().doesNotContain(ACCOUNT_NUMBER);
    }

    @Test
    @DisplayName("账号留空 → 400")
    void blankAccountNumberIsBadRequest() {
        var owner = seedAccount("wd-blank-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "Withdrawal Blank Org");

        client().post().uri("/api/organizations/" + orgId + "/withdrawal-accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"accountType\":\"bank\",\"accountName\":\"空账号\",\"bankName\":\"招商银行\"}")
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("回归：跨组织改写/删除/置默认他人收款账户全部失败，原账号不变")
    void crossOrgMutationIsBlocked() {
        var victim = seedAccount("wd-victim-" + UUID.randomUUID() + "@example.com");
        String victimOrg = createOrg(victim.cookie(), "Withdrawal Victim Org");
        String accountId = createAccount(victimOrg, victim.cookie(), ACCOUNT_NUMBER);
        String before = db.sql("SELECT account_number_encrypted FROM withdrawal_account WHERE id = CAST(:id AS uuid)")
                .bind("id", accountId).map(row -> row.get(0, String.class)).one().block();

        var attacker = seedAccount("wd-attacker-" + UUID.randomUUID() + "@example.com");
        String attackerOrg = createOrg(attacker.cookie(), "Withdrawal Attacker Org");
        String attackerCookie = "y1.sid=" + attacker.cookie();

        client().put().uri("/api/organizations/" + attackerOrg + "/withdrawal-accounts/" + accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", attackerCookie)
                .bodyValue("""
                        {"accountType":"bank","accountName":"攻击者","accountNumber":"6222029999999999999",
                         "bankName":"攻击者银行"}
                        """)
                .exchange().expectStatus().isEqualTo(409);

        client().delete().uri("/api/organizations/" + attackerOrg + "/withdrawal-accounts/" + accountId)
                .header("Cookie", attackerCookie)
                .exchange().expectStatus().isNotFound();

        client().post().uri("/api/organizations/" + attackerOrg + "/withdrawal-accounts/" + accountId + "/set-default")
                .header("Cookie", attackerCookie)
                .exchange().expectStatus().isNotFound();

        client().post().uri("/api/organizations/" + attackerOrg + "/withdrawal-accounts/" + accountId + "/submit")
                .header("Cookie", attackerCookie)
                .exchange().expectStatus().isNotFound();

        Map<String, Object> after = db.sql("SELECT account_number_encrypted AS num, organization_id::text AS org, "
                        + "status FROM withdrawal_account WHERE id = CAST(:id AS uuid)")
                .bind("id", accountId).fetch().one().block();
        assertThat(after).isNotNull();
        assertThat(after.get("num")).isEqualTo(before);
        assertThat(after.get("org")).isEqualTo(victimOrg);
        assertThat(after.get("status")).isEqualTo("pending");
    }

    @Test
    @DisplayName("提交 → under_review + 写出审核队列行 + outbox；重复提交 409")
    void submitEnqueuesReviewRequest() {
        var owner = seedAccount("wd-submit-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "Withdrawal Submit Org");
        String accountId = createAccount(orgId, owner.cookie(), ACCOUNT_NUMBER);

        client().post().uri("/api/organizations/" + orgId + "/withdrawal-accounts/" + accountId + "/submit")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isOk();

        Long requests = db.sql("SELECT count(*) FROM kyb_verification_request "
                        + "WHERE target_id = CAST(:id AS uuid) AND verification_type = 'withdrawal_account'")
                .bind("id", accountId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(requests).isEqualTo(1L);

        Long events = db.sql("SELECT count(*) FROM outbox WHERE aggregate_id = :id "
                        + "AND aggregate_type = 'WithdrawalAccount'")
                .bind("id", accountId).map(row -> row.get(0, Long.class)).one().block();
        assertThat(events).isEqualTo(1L);

        client().post().uri("/api/organizations/" + orgId + "/withdrawal-accounts/" + accountId + "/submit")
                .header("Cookie", "y1.sid=" + owner.cookie())
                .exchange().expectStatus().isEqualTo(409);

        // 审核中不可编辑（update 的状态谓词只放行 pending/rejected）。
        client().put().uri("/api/organizations/" + orgId + "/withdrawal-accounts/" + accountId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "y1.sid=" + owner.cookie())
                .bodyValue("{\"accountType\":\"bank\",\"accountName\":\"改名\",\"accountNumber\":\"" + ACCOUNT_NUMBER + "\"}")
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("未登录 401；非成员 403")
    void authorization() {
        var owner = seedAccount("wd-authz-" + UUID.randomUUID() + "@example.com");
        String orgId = createOrg(owner.cookie(), "Withdrawal Authz Org");

        client().get().uri("/api/organizations/" + orgId + "/withdrawal-accounts")
                .exchange().expectStatus().isUnauthorized();

        var outsider = seedAccount("wd-out-" + UUID.randomUUID() + "@example.com");
        client().get().uri("/api/organizations/" + orgId + "/withdrawal-accounts")
                .header("Cookie", "y1.sid=" + outsider.cookie())
                .exchange().expectStatus().isForbidden();
    }
}
