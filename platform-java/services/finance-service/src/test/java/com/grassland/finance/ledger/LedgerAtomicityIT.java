package com.grassland.finance.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

import com.grassland.finance.FinanceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * 账本记账与领域写的**同事务原子性**（同 Slice 7C {@code OutboxAtomicityIT} 的形态，推广到 ledger）。
 *
 * <p>用 {@code @SpyBean} 把 {@link LedgerRepository#postJournal} 针对某 journal 类型注入失败，
 * 断言领域写（余额 / 预留）随之回滚——而非「领域态写了、账本却丢了」的静默不一致。
 */
class LedgerAtomicityIT extends FinanceItSupport {

    private static final String H = "X-Grassland-Identity";

    @MockitoSpyBean
    LedgerRepository ledger;

    @Test
    void creditRollsBackBalanceWhenLedgerPostFails() {
        String m = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(m, org);
        creditOk(m, org, 500);          // seed（ledger 正常，落 1 条 DEPOSIT journal）
        assertThat(balanceOf(org)).isEqualTo(500L);

        failLedgerOn(JournalEntry.Type.DEPOSIT);
        credit(m, org, 300).expectStatus().is5xxServerError();

        // ledger 记账失败 → 充值余额写必须回滚（而非「钱加了、账本丢了」）
        assertThat(balanceOf(org)).isEqualTo(500L);
        assertThat(depositJournalCount(org)).isEqualTo(1);   // 仅 seed 那条
    }

    @Test
    void reserveRollsBackBalanceAndReservationWhenLedgerFails() {
        String m = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(m, org);
        creditOk(m, org, 1_000);

        failLedgerOn(JournalEntry.Type.RESERVE);
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header(H, sign(m, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", ref, "amountCents", 600))
                .exchange().expectStatus().is5xxServerError();

        assertThat(balanceOf(org)).isEqualTo(1_000L);   // 余额未扣
        assertThat(reservationExists(ref)).isFalse();    // 预留未建
    }

    private void failLedgerOn(JournalEntry.Type type) {
        doReturn(Mono.<Void>error(new RuntimeException("ledger injected failure")))
                .when(ledger).postJournal(
                        argThat((JournalEntry j) -> j != null && j.type() == type), anyList());
    }

    private void provision(String m, String org) {
        client().post().uri("/api/finance/accounts")
                .header(H, sign(m, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isCreated();
    }

    private void creditOk(String m, String org, long amount) {
        credit(m, org, amount).expectStatus().isOk();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec credit(String m, String org, long amount) {
        return client().post().uri("/api/finance/accounts/" + org + "/credit")
                .header(H, sign(m, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", amount))
                .exchange();
    }

    private long balanceOf(String org) {
        return db.sql("SELECT balance_cents FROM finance_account WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", org)
                .map(row -> row.get("balance_cents", Long.class)).one().block();
    }

    private long depositJournalCount(String org) {
        return db.sql("SELECT COUNT(*)::bigint AS c FROM journal WHERE journal_type = 'DEPOSIT' AND organization_id = CAST(:org AS uuid)")
                .bind("org", org)
                .map(row -> row.get("c", Long.class)).one().block();
    }

    private boolean reservationExists(String ref) {
        Long count = db.sql("SELECT COUNT(*)::bigint AS c FROM funds_reservation WHERE engagement_ref = :ref")
                .bind("ref", ref)
                .map(row -> row.get("c", Long.class)).one().block();
        return count != null && count > 0;
    }
}
