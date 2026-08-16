package com.grassland.finance.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 推荐官收入统计（任务书 #29+#30 #29）。锁住三件最不能靠人工点的事：
 * <ul>
 *   <li>聚合 == SUM(区间明细)——按月、按 engagement 两个口径都逐行对账；</li>
 *   <li>月界时区——北京时区自然月，UTC 8-31 深夜的流水归 9 月；</li>
 *   <li>self-scoped——accountId 只取断言，任何人只能看自己的统计。</li>
 * </ul>
 */
class WalletStatisticsControllerIT extends FinanceItSupport {

    @Test
    void monthlyAggregationMatchesLedgerRowSum() {
        String rec = UUID.randomUUID().toString();
        String engA = "eng-" + UUID.randomUUID();
        String engB = "eng-" + UUID.randomUUID();
        // 2026-08（北京时间）：两笔 task_payout（含 fee）+ 一笔 commerce_commission + 提现 + 冲正
        seedLedger(rec, "task_payout", 5000, 500, engA, beijing("2026-08-05T10:00:00"));
        seedLedger(rec, "task_payout", 3000, 0, engB, beijing("2026-08-12T10:00:00"));
        seedLedger(rec, "commerce_commission", 1200, 100, engA, beijing("2026-08-20T10:00:00"));
        seedLedger(rec, "withdrawal", -2000, 0, null, beijing("2026-08-22T10:00:00"));
        seedLedger(rec, "clawback", -1000, 0, engA, beijing("2026-08-25T10:00:00"));

        client().get().uri(uri -> uri.path("/api/finance/wallets/me/statistics")
                        .queryParam("from", "2026-08").queryParam("to", "2026-08").build())
                .header("X-Grassland-Identity", sign(rec, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.months[0].month").isEqualTo("2026-08")
                .jsonPath("$.data.months[0].taskPayoutCents").isEqualTo(8000)
                .jsonPath("$.data.months[0].commerceCommissionCents").isEqualTo(1200)
                .jsonPath("$.data.months[0].withdrawalCents").isEqualTo(-2000)
                .jsonPath("$.data.months[0].clawbackCents").isEqualTo(-1000)
                // gross = (5000+500) + (3000+0) + (1200+100) = 9800（只对入账类）
                .jsonPath("$.data.months[0].grossCents").isEqualTo(9800)
                .jsonPath("$.data.months[0].feeCents").isEqualTo(600)
                // net = SUM(amount) = 8000 + 1200 - 2000 - 1000 = 6200
                .jsonPath("$.data.months[0].netCents").isEqualTo(6200);

        // 聚合 == SUM(明细) 不变式：逐行对账
        assertThat(sumLedger(rec, "2026-08")).isEqualTo(6200L);
    }

    @Test
    void byEngagementGroupsAndExcludesWithdrawal() {
        String rec = UUID.randomUUID().toString();
        String engA = "eng-" + UUID.randomUUID();
        String engB = "eng-" + UUID.randomUUID();
        seedLedger(rec, "task_payout", 5000, 500, engA, beijing("2026-08-05T10:00:00"));
        seedLedger(rec, "commerce_commission", 1200, 100, engA, beijing("2026-08-20T10:00:00"));
        seedLedger(rec, "task_payout", 3000, 0, engB, beijing("2026-08-12T10:00:00"));
        seedLedger(rec, "withdrawal", -2000, 0, null, beijing("2026-08-22T10:00:00"));

        client().get().uri(uri -> uri.path("/api/finance/wallets/me/statistics")
                        .queryParam("from", "2026-08").queryParam("to", "2026-08").build())
                .header("X-Grassland-Identity", sign(rec, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                // engA：payout=5000+1200=6200，fee=600，count=2
                .jsonPath("$.data.byEngagement[?(@.engagementRef=='" + engA + "')].payoutCents")
                .isEqualTo(List.of(6200))
                .jsonPath("$.data.byEngagement[?(@.engagementRef=='" + engA + "')].count")
                .isEqualTo(List.of(2))
                // 提现行（engagement_ref 为 null）不进 byEngagement
                .jsonPath("$.data.byEngagement.length()").isEqualTo(2);
    }

    /** 月界时区（D2）：UTC 8-31 23:30 = 北京 9-1 07:30，归 9 月；UTC 切月会错误归 8 月。 */
    @Test
    void monthBoundaryUsesBeijingTime() {
        String rec = UUID.randomUUID().toString();
        // UTC 2026-08-31T23:30Z = 北京 2026-09-01T07:30+08 → 归 9 月
        seedLedger(rec, "task_payout", 1000, 0, "eng-x", Instant.parse("2026-08-31T23:30:00Z"));
        // UTC 2026-08-31T15:30Z = 北京 2026-08-31T23:30+08 → 仍归 8 月
        seedLedger(rec, "task_payout", 2000, 0, "eng-y", Instant.parse("2026-08-31T15:30:00Z"));

        client().get().uri(uri -> uri.path("/api/finance/wallets/me/statistics")
                        .queryParam("from", "2026-08").queryParam("to", "2026-09").build())
                .header("X-Grassland-Identity", sign(rec, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.months[?(@.month=='2026-08')].taskPayoutCents").isEqualTo(List.of(2000))
                .jsonPath("$.data.months[?(@.month=='2026-09')].taskPayoutCents").isEqualTo(List.of(1000));
    }

    /** 空区间补全零值月份（连续月份轴），不 404。 */
    @Test
    void emptyMonthsAreZeroFilled() {
        String rec = UUID.randomUUID().toString();
        seedLedger(rec, "task_payout", 1000, 0, "eng-x", beijing("2026-06-15T10:00:00"));

        client().get().uri(uri -> uri.path("/api/finance/wallets/me/statistics")
                        .queryParam("from", "2026-05").queryParam("to", "2026-07").build())
                .header("X-Grassland-Identity", sign(rec, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.months.length()").isEqualTo(3)
                .jsonPath("$.data.months[?(@.month=='2026-05')].netCents").isEqualTo(List.of(0))
                .jsonPath("$.data.months[?(@.month=='2026-06')].netCents").isEqualTo(List.of(1000))
                .jsonPath("$.data.months[?(@.month=='2026-07')].netCents").isEqualTo(List.of(0));
    }

    @Test
    void monthSpanOverLimitRejected() {
        String rec = UUID.randomUUID().toString();
        client().get().uri(uri -> uri.path("/api/finance/wallets/me/statistics")
                        .queryParam("from", "2025-01").queryParam("to", "2026-08").build())
                .header("X-Grassland-Identity", sign(rec, "recommender", null, null))
                .exchange().expectStatus().isBadRequest();
        // from 晚于 to
        client().get().uri(uri -> uri.path("/api/finance/wallets/me/statistics")
                        .queryParam("from", "2026-08").queryParam("to", "2026-07").build())
                .header("X-Grassland-Identity", sign(rec, "recommender", null, null))
                .exchange().expectStatus().isBadRequest();
        // 非法格式
        client().get().uri(uri -> uri.path("/api/finance/wallets/me/statistics")
                        .queryParam("from", "2026/08").queryParam("to", "2026-08").build())
                .header("X-Grassland-Identity", sign(rec, "recommender", null, null))
                .exchange().expectStatus().isBadRequest();
    }

    /** self-scoped：b 只看到自己的空统计，看不到 a 的流水；服务断言被拒。 */
    @Test
    void statisticsAreSelfScoped() {
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        seedLedger(a, "task_payout", 5000, 0, "eng-a", beijing("2026-08-05T10:00:00"));

        client().get().uri(uri -> uri.path("/api/finance/wallets/me/statistics")
                        .queryParam("from", "2026-08").queryParam("to", "2026-08").build())
                .header("X-Grassland-Identity", sign(b, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.months[0].netCents").isEqualTo(0)
                .jsonPath("$.data.byEngagement.length()").isEqualTo(0);

        client().get().uri(uri -> uri.path("/api/finance/wallets/me/statistics")
                        .queryParam("from", "2026-08").queryParam("to", "2026-08").build())
                .header("X-Grassland-Identity", signService(UUID.randomUUID().toString(), "marketplace"))
                .exchange().expectStatus().isForbidden();
    }

    /** 真实结算链路（capture 分账）产生的流水也能被统计读到——聚合端点与资金闭环一致。 */
    @Test
    void statisticsReflectRealCaptureFlow() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String ref = UUID.randomUUID().toString();
        provision(merchant, org);
        credit(merchant, org, 1000);
        reserve(merchant, org, ref, 600, rec);
        capture(merchant, org, ref);

        String thisMonth = java.time.YearMonth.now(
                java.time.ZoneId.of("Asia/Shanghai")).toString();
        client().get().uri(uri -> uri.path("/api/finance/wallets/me/statistics")
                        .queryParam("from", thisMonth).queryParam("to", thisMonth).build())
                .header("X-Grassland-Identity", sign(rec, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.months[0].taskPayoutCents").isEqualTo(600)
                .jsonPath("$.data.byEngagement[0].engagementRef").isEqualTo(ref);
    }

    // ---------- helpers ----------

    private static Instant beijing(String iso) {
        return OffsetDateTime.parse(iso + "+08:00").toInstant();
    }

    /** 直接落一行流水（控制 created_at，月界/多月测试用）。 */
    private void seedLedger(String accountId, String entryType, long amountCents, long feeCents,
                            String engagementRef, Instant createdAt) {
        var spec = db.sql("""
                INSERT INTO wallet_ledger(id, account_id, entry_type, amount_cents, fee_cents,
                                          commission_bonus_cents, engagement_ref, memo, created_at)
                VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), :type, :amt, :fee, 0, :ref, 'seed', :ts)
                """)
                .bind("id", UUID.randomUUID().toString()).bind("acct", accountId)
                .bind("type", entryType).bind("amt", amountCents).bind("fee", feeCents)
                .bind("ts", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
        spec = engagementRef == null ? spec.bindNull("ref", String.class) : spec.bind("ref", engagementRef);
        spec.then().block();
    }

    /** 区间内逐行 SUM(amount_cents)——聚合不变式的对照口径。 */
    private long sumLedger(String accountId, String month) {
        Instant start = beijing(month + "-01T00:00:00");
        Instant end = java.time.YearMonth.parse(month).plusMonths(1)
                .atDay(1).atStartOfDay(java.time.ZoneId.of("Asia/Shanghai")).toInstant();
        Long v = db.sql("SELECT COALESCE(SUM(amount_cents),0)::bigint AS s FROM wallet_ledger"
                        + " WHERE account_id = CAST(:acct AS uuid) AND created_at >= :f AND created_at < :t")
                .bind("acct", accountId)
                .bind("f", OffsetDateTime.ofInstant(start, ZoneOffset.UTC))
                .bind("t", OffsetDateTime.ofInstant(end, ZoneOffset.UTC))
                .map(r -> r.get("s", Long.class)).one().block();
        return v == null ? 0L : v;
    }

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
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", ref, "amountCents", amount, "payeeAccountId", payee))
                .exchange().expectStatus().isCreated();
    }

    private void capture(String merchant, String org, String ref) {
        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();
    }
}
