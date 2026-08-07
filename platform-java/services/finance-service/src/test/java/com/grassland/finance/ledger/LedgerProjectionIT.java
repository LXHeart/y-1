package com.grassland.finance.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 投影重建端到端（HLD §6.4「余额必须可由 Posting 重建」，Approach B 双写投影一致性）。
 *
 * <p>经真实 HTTP 驱动 credit→reserve→capture→reverse 全链路 + withdraw，每步断言账本派生余额 == 物化余额行，
 * 并断言**每条 journal 借贷合计为零**——证明双写投影无漂移、账本平衡。
 */
class LedgerProjectionIT extends FinanceItSupport {

    private static final String H = "X-Grassland-Identity";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired
    LedgerProjectionService projections;

    @Test
    void escrowLifecycleKeepsProjectionsRebuildable() {
        String m = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();

        provision(m, org);
        credit(m, org, 1_000);
        assertThat(projections.reconcileEscrow(org).block())
                .as("credit 后 escrow 投影可重建").isTrue();

        reserve(m, org, ref, 500, rec);
        assertThat(projections.reconcileEscrow(org).block())
                .as("reserve 后 escrow 投影可重建（余额 1000→500）").isTrue();

        capture(m, org, ref);
        assertThat(projections.reconcileEscrow(org).block()).isTrue();
        assertThat(projections.reconcileWallet(rec).block())
                .as("capture 后推荐官钱包投影可重建（+500）").isTrue();

        reverse(org, ref);
        assertThat(projections.reconcileEscrow(org).block())
                .as("reverse 后 escrow 投影可重建（全额退商家 500→1000）").isTrue();
        assertThat(projections.reconcileWallet(rec).block())
                .as("reverse 后钱包投影可重建（回扣 500→0）").isTrue();

        assertAllJournalsBalanced();
    }

    @Test
    void withdrawKeepsWalletProjectionRebuildable() {
        String m = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();

        provision(m, org);
        credit(m, org, 1_000);
        reserve(m, org, ref, 500, rec);
        capture(m, org, ref);             // 钱包入账 500（账本背书，非裸 INSERT）
        assertThat(projections.reconcileWallet(rec).block()).isTrue();

        withdraw(rec, 200);               // 钱包 500→300
        assertThat(projections.reconcileWallet(rec).block())
                .as("withdraw 后钱包投影可重建（500→300）").isTrue();

        assertAllJournalsBalanced();
    }

    /** 断言 ledger 中每条 journal 借贷合计为零（HLD §6.4）。 */
    private void assertAllJournalsBalanced() {
        Long unbalanced = db.sql("""
                SELECT COUNT(*)::bigint AS c FROM (
                    SELECT p.journal_id,
                           SUM(CASE p.direction WHEN 'DEBIT' THEN p.amount_cents ELSE -p.amount_cents END) AS net
                    FROM posting p GROUP BY p.journal_id
                ) s WHERE s.net <> 0
                """)
                .map(row -> row.get("c", Long.class))
                .one()
                .block();
        assertThat(unbalanced).as("借贷不平衡的 journal 数").isZero();
    }

    private void provision(String merchant, String org) {
        client().post().uri("/api/finance/accounts")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isCreated();
    }

    private void credit(String merchant, String org, long amount) {
        client().post().uri("/api/finance/accounts/" + org + "/credit")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(JSON).bodyValue(Map.of("amountCents", amount))
                .exchange().expectStatus().isOk();
    }

    private void reserve(String merchant, String org, String ref, long amount, String payee) {
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header(H, signService(org, "marketplace"))
                .contentType(JSON)
                .bodyValue(Map.of("engagementRef", ref, "amountCents", amount, "payeeAccountId", payee))
                .exchange().expectStatus().isCreated();
    }

    private void capture(String merchant, String org, String ref) {
        client().post().uri("/api/finance/reservations/" + ref + "/capture")
                .header(H, sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isOk();
    }

    /** reverse 仅 trust 服务可调（FinanceCallerResolver.resolveMerchantOrService(TRUST_SERVICE)）。 */
    private void reverse(String org, String ref) {
        client().post().uri("/api/finance/reservations/" + ref + "/reverse")
                .header(H, signService(org, "trust"))
                .exchange().expectStatus().isOk();
    }

    private void withdraw(String user, long amount) {
        client().post().uri("/api/finance/wallets/me/withdrawals")
                .header(H, sign(user, "recommender", null, null))
                .contentType(JSON).bodyValue(Map.of("amountCents", amount))
                .exchange().expectStatus().isOk();
    }
}
