package com.grassland.finance.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

import com.grassland.finance.FinanceItSupport;
import com.grassland.finance.event.EventEnvelope;
import com.grassland.finance.event.OutboxRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Slice 7C：证明 finance controller 写路径的「领域写 + outbox append」在同一 R2DBC 事务。
 *
 * <p>用 {@code @SpyBean} 把 {@link OutboxRepository#append} 针对某事件类型注入失败，
 * 断言领域写（余额 / 预留 / 账户 / 钱包）随之回滚——而非「写了领域态却丢了事件」的静默缺口。
 * 与 7B {@code EscrowLifecycleService} 的 {@code TransactionalOperator} 模式一致，本 slice 推广到 controller。
 */
class OutboxAtomicityIT extends FinanceItSupport {

    @MockitoSpyBean
    OutboxRepository outbox;

    @Test
    void creditRollsBackBalanceWhenOutboxAppendFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        provision(merchant, org);
        creditOk(merchant, org, 500);          // seed（outbox 正常）
        long seeded = balanceOf(org);
        assertThat(seeded).isEqualTo(500L);

        failOutboxOn("AccountCredited");
        credit(merchant, org, 300).expectStatus().is5xxServerError();

        // outbox 失败 → 充值的余额写必须回滚（而非「钱加了但事件丢了」）
        assertThat(balanceOf(org)).isEqualTo(seeded);
        assertThat(outboxCount("AccountCredited", org)).isEqualTo(1);   // 仅 seed 那条
    }

    @Test
    void reserveRollsBackBalanceAndReservationWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        provision(merchant, org);
        creditOk(merchant, org, 1_000);

        failOutboxOn("FundsReserved");
        client().post().uri("/api/finance/accounts/" + org + "/reservations")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", ref, "amountCents", 600))
                .exchange().expectStatus().is5xxServerError();

        assertThat(balanceOf(org)).isEqualTo(1_000L);   // 余额未扣
        assertThat(reservationExists(ref)).isFalse();    // 预留未建
    }

    @Test
    void provisionRollsBackAccountWhenOutboxFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();

        failOutboxOn("AccountProvisioned");
        client().post().uri("/api/finance/accounts")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().is5xxServerError();

        assertThat(accountExists(org)).isFalse();        // 账户未建（而非「户开了事件丢了」）
    }

    @Test
    void withdrawalRollsBackDebitWhenOutboxFails() {
        String user = UUID.randomUUID().toString();
        seedWallet(user, 1_000);

        failOutboxOn("WithdrawalCompleted");
        client().post().uri("/api/finance/wallets/me/withdrawals")
                .header("X-Grassland-Identity", sign(user, "recommender", null, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 400))
                .exchange().expectStatus().is5xxServerError();

        assertThat(walletBalance(user)).isEqualTo(1_000L);   // 钱包未扣、无提现流水
        assertThat(withdrawalEntryCount(user)).isZero();
    }

    private void failOutboxOn(String eventType) {
        doReturn(Mono.<Void>error(new RuntimeException("outbox injected failure")))
                .when(outbox).append(argThat((EventEnvelope e) -> e != null && eventType.equals(e.eventType())));
    }

    private void provision(String merchant, String org) {
        client().post().uri("/api/finance/accounts")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .exchange().expectStatus().isCreated();
    }

    private void creditOk(String merchant, String org, long amount) {
        credit(merchant, org, amount).expectStatus().isOk();
    }

    private WebTestClient.ResponseSpec credit(String merchant, String org, long amount) {
        return client().post().uri("/api/finance/accounts/" + org + "/credit")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", amount))
                .exchange();
    }

    private long balanceOf(String org) {
        return db.sql("SELECT balance_cents FROM finance_account WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", org)
                .map(row -> row.get("balance_cents", Long.class)).one().block();
    }

    private long outboxCount(String eventType, String org) {
        return db.sql("SELECT COUNT(*)::bigint AS c FROM finance_outbox"
                        + " WHERE event_type = :eventType AND payload->>'organizationId' = :org")
                .bind("eventType", eventType)
                .bind("org", org)
                .map(row -> row.get("c", Long.class)).one().block();
    }

    private boolean reservationExists(String ref) {
        Long count = db.sql("SELECT COUNT(*)::bigint AS c FROM funds_reservation WHERE engagement_ref = :ref")
                .bind("ref", ref)
                .map(row -> row.get("c", Long.class)).one().block();
        return count != null && count > 0;
    }

    private boolean accountExists(String org) {
        Long count = db.sql("SELECT COUNT(*)::bigint AS c FROM finance_account WHERE organization_id = CAST(:org AS uuid)")
                .bind("org", org)
                .map(row -> row.get("c", Long.class)).one().block();
        return count != null && count > 0;
    }

    private void seedWallet(String user, long amount) {
        db.sql("INSERT INTO recommender_wallet (account_id, balance_cents) VALUES (CAST(:user AS uuid), :amount)")
                .bind("user", user)
                .bind("amount", amount)
                .fetch().rowsUpdated().block();
    }

    private long walletBalance(String user) {
        return db.sql("SELECT balance_cents FROM recommender_wallet WHERE account_id = CAST(:user AS uuid)")
                .bind("user", user)
                .map(row -> row.get("balance_cents", Long.class)).one().block();
    }

    private long withdrawalEntryCount(String user) {
        Long count = db.sql("SELECT COUNT(*)::bigint AS c FROM wallet_ledger"
                        + " WHERE account_id = CAST(:user AS uuid) AND entry_type = 'withdrawal'")
                .bind("user", user)
                .map(row -> row.get("c", Long.class)).one().block();
        return count == null ? 0L : count;
    }
}
