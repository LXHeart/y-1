package com.grassland.finance.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 商家月度账单（任务书 #29+#30 #30）。锁住：
 * <ul>
 *   <li>不变式 Σ flows == 该 org 该月 ESCROW 腿净额 == netEscrowDeltaCents（对 posting 逐行复核）；</li>
 *   <li>科目分解——CAPTURE 不动 ESCROW 腿故不落 flow，平台抽成走 platformFeeCents 单列；</li>
 *   <li>月界时区（北京时间自然月）；跨 org 访问 404。</li>
 * </ul>
 */
class MerchantBillControllerIT extends FinanceItSupport {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @Test
    void monthlyBillDecomposesFlowsAndFee() {
        String org = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        Instant m = beijing("2026-08-10T10:00:00");
        // 充值 +10000（ESCROW 增）
        seedJournal("DEPOSIT", org, m, post("EXTERNAL", "DEBIT", 10000), post("ESCROW", "CREDIT", 10000));
        // 预留 -6000（ESCROW 减）
        seedJournal("RESERVE", org, m, post("ESCROW", "DEBIT", 6000), post("RESERVE", "CREDIT", 6000));
        // 结算：RESERVE→WALLET(5400)+FEE(600)，不动 ESCROW
        seedJournal("CAPTURE", org, m, post("RESERVE", "DEBIT", 6000),
                post("WALLET", "CREDIT", 5400), post("FEE", "CREDIT", 600));
        // 释放 +1000（ESCROW 增）
        seedJournal("RELEASE", org, m, post("RESERVE", "DEBIT", 1000), post("ESCROW", "CREDIT", 1000));

        WebTestClient.ResponseSpec resp = client().get().uri(uri -> uri
                        .path("/api/finance/organizations/" + org + "/monthly-bill")
                        .queryParam("month", "2026-08").build())
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();

        resp.expectBody()
                .jsonPath("$.data.month").isEqualTo("2026-08")
                .jsonPath("$.data.flows[?(@.type=='DEPOSIT')].amountCents").isEqualTo(List.of(10000))
                .jsonPath("$.data.flows[?(@.type=='DEPOSIT')].label").isEqualTo(List.of("充值"))
                .jsonPath("$.data.flows[?(@.type=='RESERVE')].amountCents").isEqualTo(List.of(-6000))
                .jsonPath("$.data.flows[?(@.type=='RELEASE')].amountCents").isEqualTo(List.of(1000))
                // CAPTURE 不落 ESCROW flow
                .jsonPath("$.data.flows[?(@.type=='CAPTURE')].amountCents").isEmpty()
                .jsonPath("$.data.platformFeeCents").isEqualTo(600)
                .jsonPath("$.data.netEscrowDeltaCents").isEqualTo(5000);

        // 不变式：Σ flows == 该 org 该月 ESCROW 腿净额（独立 SQL 复核）
        assertThat(10000L - 6000 + 1000).isEqualTo(escrowLegSum(org, "2026-08"));
    }

    @Test
    void monthlyBillExportKeepsOrgScopeAndSupportsCsvAndXlsx() {
        String org = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        seedJournal("DEPOSIT", org, beijing("2026-08-10T10:00:00"),
                post("EXTERNAL", "DEBIT", 500), post("ESCROW", "CREDIT", 500));
        String assertion = sign(merchant, "merchant", org, "finance_transaction");

        byte[] csv = client().get().uri("/api/finance/organizations/" + org
                        + "/monthly-bill/export?month=2026-08&format=csv")
                .header("X-Grassland-Identity", assertion).exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(new String(csv, StandardCharsets.UTF_8)).contains("DEPOSIT", "500");

        byte[] xlsx = client().get().uri("/api/finance/organizations/" + org
                        + "/monthly-bill/export?month=2026-08&format=xlsx")
                .header("X-Grassland-Identity", assertion).exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(xlsx).startsWith((byte) 'P', (byte) 'K');

        client().get().uri("/api/finance/organizations/" + org
                        + "/monthly-bill/export?month=2026-08&format=csv")
                .header("X-Grassland-Identity", sign(merchant, "merchant", UUID.randomUUID().toString(),
                        "finance_transaction"))
                .exchange().expectStatus().isNotFound();
    }

    /** 冲正：Cr ESCROW（退商家）+ Dr FEE（回冲抽成）。 */
    @Test
    void reverseRefundsMerchantAndClawsBackFee() {
        String org = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        Instant m = beijing("2026-08-10T10:00:00");
        seedJournal("CAPTURE", org, m, post("RESERVE", "DEBIT", 6000),
                post("WALLET", "CREDIT", 5400), post("FEE", "CREDIT", 600));
        seedJournal("REVERSE", org, m, post("WALLET", "DEBIT", 5400),
                post("FEE", "DEBIT", 600), post("ESCROW", "CREDIT", 6000));

        client().get().uri(uri -> uri.path("/api/finance/organizations/" + org + "/monthly-bill")
                        .queryParam("month", "2026-08").build())
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.flows[?(@.type=='REVERSE')].amountCents").isEqualTo(List.of(6000))
                // 平台费净额：+600（capture）-600（reverse 回冲）= 0
                .jsonPath("$.data.platformFeeCents").isEqualTo(0)
                .jsonPath("$.data.netEscrowDeltaCents").isEqualTo(6000);
    }

    @Test
    void monthBoundaryUsesBeijingTime() {
        String org = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        // UTC 8-31 23:30 = 北京 9-1 07:30 → 归 9 月
        seedJournal("DEPOSIT", org, Instant.parse("2026-08-31T23:30:00Z"),
                post("EXTERNAL", "DEBIT", 500), post("ESCROW", "CREDIT", 500));
        // UTC 8-31 15:30 = 北京 8-31 23:30 → 归 8 月
        seedJournal("DEPOSIT", org, Instant.parse("2026-08-31T15:30:00Z"),
                post("EXTERNAL", "DEBIT", 700), post("ESCROW", "CREDIT", 700));

        client().get().uri(uri -> uri.path("/api/finance/organizations/" + org + "/monthly-bill")
                        .queryParam("month", "2026-08").build())
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.flows[?(@.type=='DEPOSIT')].amountCents").isEqualTo(List.of(700));
        client().get().uri(uri -> uri.path("/api/finance/organizations/" + org + "/monthly-bill")
                        .queryParam("month", "2026-09").build())
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.flows[?(@.type=='DEPOSIT')].amountCents").isEqualTo(List.of(500));
    }

    /** 空月：flows 空、净额 0，不 404。 */
    @Test
    void emptyMonthReturnsZero() {
        String org = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        client().get().uri(uri -> uri.path("/api/finance/organizations/" + org + "/monthly-bill")
                        .queryParam("month", "2026-08").build())
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.flows.length()").isEqualTo(0)
                .jsonPath("$.data.platformFeeCents").isEqualTo(0)
                .jsonPath("$.data.netEscrowDeltaCents").isEqualTo(0);
    }

    /** 跨 org 访问 404（不暴露组织存在）；无断言 401；非商家 403。 */
    @Test
    void billIsOrgScoped() {
        String org = UUID.randomUUID().toString();
        String otherOrg = UUID.randomUUID().toString();
        String merchant = UUID.randomUUID().toString();
        seedJournal("DEPOSIT", org, beijing("2026-08-10T10:00:00"),
                post("EXTERNAL", "DEBIT", 100), post("ESCROW", "CREDIT", 100));

        // 别的 org 的商家看本 org 账单 → 404
        client().get().uri(uri -> uri.path("/api/finance/organizations/" + org + "/monthly-bill")
                        .queryParam("month", "2026-08").build())
                .header("X-Grassland-Identity", sign(merchant, "merchant", otherOrg, "finance_transaction"))
                .exchange().expectStatus().isNotFound();

        // 无断言 → 401
        client().get().uri(uri -> uri.path("/api/finance/organizations/" + org + "/monthly-bill")
                        .queryParam("month", "2026-08").build())
                .exchange().expectStatus().isUnauthorized();

        // 推荐官身份（非商家）→ 403
        client().get().uri(uri -> uri.path("/api/finance/organizations/" + org + "/monthly-bill")
                        .queryParam("month", "2026-08").build())
                .header("X-Grassland-Identity", sign(merchant, "recommender", org, null))
                .exchange().expectStatus().isForbidden();

        // 非法月份 → 400
        client().get().uri(uri -> uri.path("/api/finance/organizations/" + org + "/monthly-bill")
                        .queryParam("month", "2026-13").build())
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isBadRequest();
    }

    // ---------- helpers ----------

    private record Post(String accountType, String direction, long amountCents) {
    }

    private static Post post(String accountType, String direction, long amountCents) {
        return new Post(accountType, direction, amountCents);
    }

    private static Instant beijing(String iso) {
        return OffsetDateTime.parse(iso + "+08:00").toInstant();
    }

    /** 直接落一条 journal + 其全部 posting（控制 created_at，月界/科目测试用）。 */
    private void seedJournal(String journalType, String org, Instant createdAt, Post... posts) {
        UUID journalId = UUID.randomUUID();
        db.sql("""
                INSERT INTO journal(id, journal_type, operation_id, currency, organization_id,
                                    engagement_ref, memo, created_at)
                VALUES (CAST(:id AS uuid), :type, NULL, 'CNY', CAST(:org AS uuid), NULL, 'seed', :ts)
                """)
                .bind("id", journalId.toString()).bind("type", journalType).bind("org", org)
                .bind("ts", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                .then().block();
        for (Post p : posts) {
            db.sql("""
                    INSERT INTO posting(id, journal_id, account_type, account_owner, account_ref,
                                        direction, amount_cents, created_at)
                    VALUES (CAST(:id AS uuid), CAST(:j AS uuid), :acct, NULL, NULL, :dir, :amt, :ts)
                    """)
                    .bind("id", UUID.randomUUID().toString()).bind("j", journalId.toString())
                    .bind("acct", p.accountType()).bind("dir", p.direction()).bind("amt", p.amountCents())
                    .bind("ts", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC))
                    .then().block();
        }
    }

    /** 该 org 该月 ESCROW 腿净额（不变式的独立对照口径）。 */
    private long escrowLegSum(String org, String month) {
        Instant start = java.time.YearMonth.parse(month).atDay(1).atStartOfDay(BEIJING).toInstant();
        Instant end = java.time.YearMonth.parse(month).plusMonths(1).atDay(1).atStartOfDay(BEIJING).toInstant();
        Long v = db.sql("""
                SELECT COALESCE(SUM(CASE WHEN p.direction = 'CREDIT' THEN p.amount_cents
                                         ELSE -p.amount_cents END),0)::bigint AS s
                FROM journal j JOIN posting p ON p.journal_id = j.id
                WHERE j.organization_id = CAST(:org AS uuid) AND p.account_type = 'ESCROW'
                  AND j.created_at >= :f AND j.created_at < :t
                """)
                .bind("org", org)
                .bind("f", OffsetDateTime.ofInstant(start, ZoneOffset.UTC))
                .bind("t", OffsetDateTime.ofInstant(end, ZoneOffset.UTC))
                .map(r -> r.get("s", Long.class)).one().block();
        return v == null ? 0L : v;
    }
}
