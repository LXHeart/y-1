package com.grassland.finance.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 端到端验证**资金闭环的收款侧**：capture 分账入账 → 钱包可见 → 提现出账；争议冲正按净额扣回。
 *
 * <p>此前的链路走到 capture 就断了：商家余额被扣、预留翻成 captured，但推荐官既没有账户也拿不到钱。
 * 本组测试锁住的正是「钱最终到了谁手上」，属于最不能靠人工点一遍代替的部分。
 */
class WalletControllerIT extends FinanceItSupport {

    @Test
    void captureSplitsFundsIntoRecommenderWallet() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String ref = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600, recommender);
        assertThat(walletBalance(recommender)).isZero();   // 预留阶段钱还在托管，未到账

        capture(merchant, org, ref);

        // 平台抽成默认 0（PRD 前期全免费）→ 毛额全额到账
        assertThat(walletBalance(recommender)).isEqualTo(600L);
        assertThat(outboxCountByPayee("SplitCompleted", recommender)).isEqualTo(1);
        // Slice 12 Stage 3：FundsCaptured 也携带 payeeAccountId，供 identity 解析钱包通知收件人。
        assertThat(outboxCountByPayee("FundsCaptured", recommender)).isEqualTo(1);

        client().get().uri("/api/finance/wallets/me")
                .header("X-Grassland-Identity", sign(recommender, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.balanceCents").isEqualTo(600)
                .jsonPath("$.data.entries[0].entryType").isEqualTo("task_payout")
                .jsonPath("$.data.entries[0].amountCents").isEqualTo(600)
                .jsonPath("$.data.entries[0].feeCents").isEqualTo(0);
    }

    /** 无收款人（存量预留 / 非撮合场景）：capture 维持旧行为，不动任何钱包。 */
    @Test
    void captureWithoutPayeeDoesNotSplit() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 300, null);

        capture(merchant, org, ref);

        assertThat(payoutCents(ref)).isNull();
        assertThat(balanceOf(org)).isEqualTo(700L);   // 商家已扣，钱留在平台账上（无分账对象）
    }

    @Test
    void withdrawalDebitsWalletAndRejectsOverdraw() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String ref = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 500, recommender);
        capture(merchant, org, ref);

        // 超额提现 → 409，且余额不动
        withdraw(recommender, 501).expectStatus().isEqualTo(409);
        assertThat(walletBalance(recommender)).isEqualTo(500L);

        withdraw(recommender, 200).expectStatus().isOk().expectBody()
                .jsonPath("$.data.balanceCents").isEqualTo(300);
        assertThat(walletBalance(recommender)).isEqualTo(300L);
        assertThat(ledgerCount(recommender, "withdrawal")).isEqualTo(1);
    }

    /** 钱包是账号级私有资源：accountId 只取自断言，任何人拿到的都只是自己的钱包。 */
    @Test
    void walletIsPerAccountAndServiceAssertionRejected() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 400, a);
        capture(merchant, org, ref);

        // b 读到的是 b 自己的空钱包，而不是 a 的 400
        client().get().uri("/api/finance/wallets/me")
                .header("X-Grassland-Identity", sign(b, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.balanceCents").isEqualTo(0);

        // 服务断言不得动钱包（服务身份是给 Saga 用的，不该能把钱取走）
        client().post().uri("/api/finance/wallets/me/withdrawals")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", 100))
                .exchange().expectStatus().isForbidden();
    }

    /** 无断言 → 401（钱包不可匿名访问）。 */
    @Test
    void walletRequiresAssertion() {
        client().get().uri("/api/finance/wallets/me").exchange().expectStatus().isUnauthorized();
    }

    /**
     * 争议冲正（D-06）：已分账后判商家胜诉 → 先从推荐官钱包按**净额**扣回，再退商家。
     * 扣的是 payout 而不是毛额，否则会把平台抽成也从推荐官身上扣走。
     */
    @Test
    void reverseClawsBackFromWalletThenRefundsMerchant() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String ref = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 700, recommender);
        capture(merchant, org, ref);
        assertThat(walletBalance(recommender)).isEqualTo(700L);
        assertThat(balanceOf(org)).isEqualTo(300L);

        client().post().uri("/api/finance/reservations/" + ref + "/reverse")
                .header("X-Grassland-Identity", signService(org, "trust"))
                .exchange().expectStatus().isOk();

        assertThat(walletBalance(recommender)).isZero();          // 推荐官被扣回
        assertThat(balanceOf(org)).isEqualTo(1000L);              // 商家收到退款
        assertThat(ledgerCount(recommender, "clawback")).isEqualTo(1);
    }

    /**
     * 推荐官已提现、钱包不够扣回时：**整个冲正中止**（409），商家不退款。
     * 一边退商家一边让推荐官余额变负 = 凭空造钱，宁可挂起等人工处理。
     */
    @Test
    void reverseFailsWhenWalletCannotCoverClawback() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String ref = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 800, recommender);
        capture(merchant, org, ref);
        withdraw(recommender, 800).expectStatus().isOk();          // 提现跑了

        client().post().uri("/api/finance/reservations/" + ref + "/reverse")
                .header("X-Grassland-Identity", signService(org, "trust"))
                .exchange().expectStatus().isEqualTo(409);

        assertThat(balanceOf(org)).isEqualTo(200L);                // 商家**没有**被退款
        assertThat(statusOf(ref)).isEqualTo("captured");           // 预留仍是 captured，未冲正
    }

    // ---------- helpers ----------

    private void provision(String merchant, String org) {
        client().post().uri("/api/finance/accounts")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isCreated();
    }

    private void credit(String merchant, String org, long amount) {
        client().post().uri("/api/finance/accounts/" + org + "/credit")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", amount))
                .exchange().expectStatus().isOk();
    }

    private void reserve(String merchant, String org, String ref, long amount, String payee) {
        Map<String, Object> body = payee == null
                ? Map.of("engagementRef", ref, "amountCents", amount)
                : Map.of("engagementRef", ref, "amountCents", amount, "payeeAccountId", payee);
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated();
    }

    private void capture(String merchant, String org, String ref) {
        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec withdraw(String recommender, long amount) {
        return client().post().uri("/api/finance/wallets/me/withdrawals")
                .header("X-Grassland-Identity", sign(recommender, "recommender", null, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("amountCents", amount))
                .exchange();
    }

    private long walletBalance(String accountId) {
        Long v = db.sql("SELECT balance_cents FROM recommender_wallet WHERE account_id = CAST(:a AS uuid)")
                .bind("a", accountId).map(r -> r.get("balance_cents", Long.class)).one().block();
        return v == null ? 0L : v;
    }

    /** 用 COALESCE 兜底：R2DBC 的 map 函数返回 null 会抛 NPE，不能直接把可空列映出来。 */
    private Long payoutCents(String ref) {
        Long v = db.sql("SELECT COALESCE(payout_cents, -1) AS p FROM funds_reservation WHERE engagement_ref = :r")
                .bind("r", ref).map(r -> r.get("p", Long.class)).one().block();
        return v == null || v < 0 ? null : v;
    }

    private String statusOf(String ref) {
        return db.sql("SELECT status FROM funds_reservation WHERE engagement_ref = :r")
                .bind("r", ref).map(r -> r.get("status", String.class)).one().block();
    }

    private long balanceOf(String org) {
        return db.sql("SELECT balance_cents FROM finance_account WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", org).map(r -> r.get("balance_cents", Long.class)).one().block();
    }

    private long ledgerCount(String accountId, String entryType) {
        return db.sql("SELECT COUNT(*)::int AS c FROM wallet_ledger"
                        + " WHERE account_id = CAST(:a AS uuid) AND entry_type = :t")
                .bind("a", accountId).bind("t", entryType)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    private long outboxCountByPayee(String eventType, String payee) {
        return db.sql("SELECT COUNT(*)::int AS c FROM finance_outbox"
                        + " WHERE event_type = :et AND payload->>'payeeAccountId' = :p")
                .bind("et", eventType).bind("p", payee)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }
}
