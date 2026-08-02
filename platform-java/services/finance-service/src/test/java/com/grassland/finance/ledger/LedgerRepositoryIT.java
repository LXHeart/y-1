package com.grassland.finance.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link LedgerRepository} 集成：postJournal 持久化、sumBalance 重建、operationId 查找。 */
class LedgerRepositoryIT extends com.grassland.finance.FinanceItSupport {

    @Autowired
    LedgerRepository ledger;

    @Test
    void postJournalPersistsJournalAndPostingsAndSumBalanceRebuilds() {
        String org = UUID.randomUUID().toString();
        JournalEntry journal = new JournalEntry(
                UUID.randomUUID(), JournalEntry.Type.DEPOSIT, "op-deposit-" + org, "CNY",
                org, null, "test deposit", null);
        List<Posting> postings = List.of(
                Posting.debit(LedgerAccount.external("sandbox"), 100),
                Posting.credit(LedgerAccount.escrow(org), 100));

        ledger.postJournal(journal, postings).block();

        assertThat(ledger.sumBalance(LedgerAccount.Type.ESCROW, org).block()).isEqualTo(100L);
        List<Posting> found = ledger.findPostingsByJournal(journal.id()).block();
        assertThat(found).hasSize(2);
    }

    @Test
    void sumBalanceHandlesLiabilityDirectionAndNullOwner() {
        String org = UUID.randomUUID().toString();
        String ref = "eng-" + UUID.randomUUID();
        // 两笔 deposit 进 escrow（credit）+ 一笔 reserve（debit）→ escrow 派生 = 200 - 50 = 150
        post(org, JournalEntry.Type.DEPOSIT, "d1-" + org, List.of(
                Posting.debit(LedgerAccount.external("sandbox"), 100),
                Posting.credit(LedgerAccount.escrow(org), 100)));
        post(org, JournalEntry.Type.DEPOSIT, "d2-" + org, List.of(
                Posting.debit(LedgerAccount.external("sandbox"), 100),
                Posting.credit(LedgerAccount.escrow(org), 100)));
        post(org, JournalEntry.Type.RESERVE, "r1-" + org, List.of(
                Posting.debit(LedgerAccount.escrow(org), 50),
                Posting.credit(LedgerAccount.reserve(org, ref), 50)));

        assertThat(ledger.sumBalance(LedgerAccount.Type.ESCROW, org).block()).isEqualTo(150L);
        // FEE 无 owner：sumBalance(type, null)
        post(org, JournalEntry.Type.CAPTURE, "c1-" + org, List.of(
                Posting.debit(LedgerAccount.reserve(org, ref), 50),
                Posting.credit(LedgerAccount.fee(), 50)));
        assertThat(ledger.sumBalance(LedgerAccount.Type.FEE, null).block()).isEqualTo(50L);
    }

    @Test
    void findJournalIdByOperationIdFindsExistingAndEmptyForMissing() {
        String account = UUID.randomUUID().toString();
        post(UUID.randomUUID().toString(), JournalEntry.Type.WITHDRAW, "op-w-1", List.of(
                Posting.debit(LedgerAccount.wallet(account), 30),
                Posting.credit(LedgerAccount.external("sandbox"), 30)));

        assertThat(ledger.findJournalIdByOperationId("op-w-1").block()).isNotNull();
        assertThat(ledger.findJournalIdByOperationId("op-missing").block()).isNull();
        // null 入参直接 empty（一次性动作不查）
        assertThat(ledger.findJournalIdByOperationId(null).block()).isNull();
    }

    private void post(String org, JournalEntry.Type type, String operationId, List<Posting> postings) {
        JournalEntry journal = new JournalEntry(
                UUID.randomUUID(), type, operationId, "CNY", org, null, "test", null);
        ledger.postJournal(journal, postings).block();
    }
}
