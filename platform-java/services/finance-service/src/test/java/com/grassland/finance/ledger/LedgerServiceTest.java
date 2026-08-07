package com.grassland.finance.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.finance.payment.PaymentProviderAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * {@link LedgerService} 单测：每个操作的 posting 平衡性、账户方向、fee 口径、无收款人边缘、operationId 幂等 skip。
 * LedgerRepository / PaymentProviderAdapter 用 Mockito 打桩，捕获 postJournal 入参断言。
 */
class LedgerServiceTest {

    private LedgerRepository ledger;
    private PaymentProviderAdapter psp;
    private LedgerService service;

    private final List<JournalEntry> journals = new ArrayList<>();
    private final List<List<Posting>> postings = new ArrayList<>();

    @BeforeEach
    void setUp() {
        journals.clear();
        postings.clear();
        ledger = mock(LedgerRepository.class);
        psp = mock(PaymentProviderAdapter.class);
        when(psp.channel()).thenReturn("sandbox");
        when(psp.recordExternalMovement(any())).thenReturn(Mono.empty());
        when(ledger.findJournalIdByOperationId(anyString())).thenReturn(Mono.empty());
        when(ledger.postJournal(any(), any())).thenAnswer(inv -> {
            journals.add(inv.getArgument(0));
            postings.add(inv.getArgument(1));
            return Mono.empty();
        });
        service = new LedgerService(ledger, psp);
    }

    @Test
    void depositDebitsExternalAndCreditsEscrow() {
        StepVerifier.create(service.postDeposit("org-1", 500)).verifyComplete();
        assertOneBalancedJournal(JournalEntry.Type.DEPOSIT, "org-1");
        List<Posting> p = postings.get(0);
        assertThat(find(p, LedgerAccount.Type.EXTERNAL, "sandbox").direction()).isEqualTo(Posting.Direction.DEBIT);
        assertThat(find(p, LedgerAccount.Type.ESCROW, "org-1").direction()).isEqualTo(Posting.Direction.CREDIT);
    }

    @Test
    void reserveDebitsEscrowAndCreditsReserve() {
        StepVerifier.create(service.postReserve("org-1", "eng-9", 300)).verifyComplete();
        assertOneBalancedJournal(JournalEntry.Type.RESERVE, "org-1");
        List<Posting> p = postings.get(0);
        assertThat(find(p, LedgerAccount.Type.ESCROW, "org-1").direction()).isEqualTo(Posting.Direction.DEBIT);
        Posting reserve = find(p, LedgerAccount.Type.RESERVE, "org-1");
        assertThat(reserve.direction()).isEqualTo(Posting.Direction.CREDIT);
        assertThat(reserve.account().ref()).isEqualTo("eng-9");
        assertThat(journals.get(0).operationId()).isEqualTo("reserve:eng-9");
    }

    @Test
    void releaseMirrorsReserve() {
        StepVerifier.create(service.postRelease("org-1", "eng-9", 300)).verifyComplete();
        List<Posting> p = postings.get(0);
        assertThat(find(p, LedgerAccount.Type.RESERVE, "org-1").direction()).isEqualTo(Posting.Direction.DEBIT);
        assertThat(find(p, LedgerAccount.Type.ESCROW, "org-1").direction()).isEqualTo(Posting.Direction.CREDIT);
        assertThat(journals.get(0).operationId()).isEqualTo("release:eng-9");
    }

    @Test
    void captureWithPayoutSplitsToWalletAndFee() {
        // amount 1000, payout 950 → fee 50
        StepVerifier.create(service.postCapture("org-1", "eng-9", 1000, "rec-1", 950L)).verifyComplete();
        List<Posting> p = postings.get(0);
        assertBalanced(p);
        assertThat(find(p, LedgerAccount.Type.RESERVE, "org-1").direction()).isEqualTo(Posting.Direction.DEBIT);
        assertThat(amount(p, LedgerAccount.Type.RESERVE, "org-1")).isEqualTo(1000);
        assertThat(find(p, LedgerAccount.Type.WALLET, "rec-1").direction()).isEqualTo(Posting.Direction.CREDIT);
        assertThat(amount(p, LedgerAccount.Type.WALLET, "rec-1")).isEqualTo(950);
        assertThat(find(p, LedgerAccount.Type.FEE, null).direction()).isEqualTo(Posting.Direction.CREDIT);
        assertThat(amount(p, LedgerAccount.Type.FEE, null)).isEqualTo(50);
    }

    @Test
    void captureCreditsPlatformFundedBonusWithoutReducingFee() {
        // 商家原赏金 1000：基础净额 950、平台费 50；平台另补 100，钱包共到账 1050。
        StepVerifier.create(service.postCapture("org-1", "eng-bonus", 1000, "rec-1", 1050L, 100L))
                .verifyComplete();

        List<Posting> p = postings.get(0);
        assertBalanced(p);
        assertThat(amount(p, LedgerAccount.Type.RESERVE, "org-1")).isEqualTo(1000);
        assertThat(amount(p, LedgerAccount.Type.WALLET, "rec-1")).isEqualTo(1050);
        assertThat(amount(p, LedgerAccount.Type.FEE, null)).isEqualTo(50);
        assertThat(find(p, LedgerAccount.Type.SUBSIDY_EXPENSE, null).direction()).isEqualTo(Posting.Direction.DEBIT);
        assertThat(amount(p, LedgerAccount.Type.SUBSIDY_EXPENSE, null)).isEqualTo(100);
    }

    @Test
    void captureWithoutPayeeCreditsFeeInFull() {
        // 无收款人：商家付款全额转平台收入，钱留平台账（与现状「money stays on platform account」一致）
        StepVerifier.create(service.postCapture("org-1", "eng-9", 1000, null, null)).verifyComplete();
        List<Posting> p = postings.get(0);
        assertBalanced(p);
        assertThat(p).noneMatch(post -> post.account().type() == LedgerAccount.Type.WALLET);
        assertThat(amount(p, LedgerAccount.Type.RESERVE, "org-1")).isEqualTo(1000);
        assertThat(amount(p, LedgerAccount.Type.FEE, null)).isEqualTo(1000);
    }

    @Test
    void captureWithNoPlatformFeeOmitsZeroFeePosting() {
        // 默认 platform-fee-bps=0 → payout=amount, fee=0；不落零额 FEE posting（posting.amount_cents CHECK > 0）
        StepVerifier.create(service.postCapture("org-1", "eng-9", 1000, "rec-1", 1000L)).verifyComplete();
        List<Posting> p = postings.get(0);
        assertBalanced(p);
        assertThat(p).hasSize(2);   // Dr RESERVE / Cr WALLET
        assertThat(p).noneMatch(post -> post.account().type() == LedgerAccount.Type.FEE);
        assertThat(amount(p, LedgerAccount.Type.WALLET, "rec-1")).isEqualTo(1000);
    }

    @Test
    void reverseClawsBackPayoutAndFeeThenCreditsEscrowFull() {
        StepVerifier.create(service.postReverse("org-1", "eng-9", 1000, "rec-1", 950L)).verifyComplete();
        List<Posting> p = postings.get(0);
        assertBalanced(p);
        assertThat(find(p, LedgerAccount.Type.WALLET, "rec-1").direction()).isEqualTo(Posting.Direction.DEBIT);
        assertThat(amount(p, LedgerAccount.Type.WALLET, "rec-1")).isEqualTo(950);
        assertThat(find(p, LedgerAccount.Type.FEE, null).direction()).isEqualTo(Posting.Direction.DEBIT);
        assertThat(amount(p, LedgerAccount.Type.FEE, null)).isEqualTo(50);
        assertThat(find(p, LedgerAccount.Type.ESCROW, "org-1").direction()).isEqualTo(Posting.Direction.CREDIT);
        assertThat(amount(p, LedgerAccount.Type.ESCROW, "org-1")).isEqualTo(1000);   // 全额退商家
    }

    @Test
    void reverseClawsBackBonusButRefundsMerchantOnlyOriginalBounty() {
        StepVerifier.create(service.postReverse("org-1", "eng-bonus", 1000, "rec-1", 1050L, 100L))
                .verifyComplete();

        List<Posting> p = postings.get(0);
        assertBalanced(p);
        assertThat(amount(p, LedgerAccount.Type.WALLET, "rec-1")).isEqualTo(1050);
        assertThat(amount(p, LedgerAccount.Type.FEE, null)).isEqualTo(50);
        assertThat(find(p, LedgerAccount.Type.SUBSIDY_EXPENSE, null).direction()).isEqualTo(Posting.Direction.CREDIT);
        assertThat(amount(p, LedgerAccount.Type.SUBSIDY_EXPENSE, null)).isEqualTo(100);
        assertThat(amount(p, LedgerAccount.Type.ESCROW, "org-1")).isEqualTo(1000);
    }

    @Test
    void reverseWithoutPayeeDebitsFeeInFull() {
        StepVerifier.create(service.postReverse("org-1", "eng-9", 1000, null, null)).verifyComplete();
        List<Posting> p = postings.get(0);
        assertBalanced(p);
        assertThat(amount(p, LedgerAccount.Type.FEE, null)).isEqualTo(1000);
        assertThat(amount(p, LedgerAccount.Type.ESCROW, "org-1")).isEqualTo(1000);
    }

    @Test
    void withdrawDebitsWalletAndCreditsExternal() {
        StepVerifier.create(service.postWithdraw("rec-1", 200)).verifyComplete();
        List<Posting> p = postings.get(0);
        assertBalanced(p);
        assertThat(find(p, LedgerAccount.Type.WALLET, "rec-1").direction()).isEqualTo(Posting.Direction.DEBIT);
        assertThat(find(p, LedgerAccount.Type.EXTERNAL, "sandbox").direction()).isEqualTo(Posting.Direction.CREDIT);
        assertThat(journals.get(0).operationId()).isNull();   // 一次性用户动作，不参与幂等
    }

    @Test
    void reserveSkipsWriteWhenOperationIdAlreadyJournaled() {
        // 同一 operationId 已记账 → 不重复写账（credit-bridge 预检）
        when(ledger.findJournalIdByOperationId("reserve:eng-9"))
                .thenReturn(Mono.just(UUID.randomUUID()));
        StepVerifier.create(service.postReserve("org-1", "eng-9", 300)).verifyComplete();
        verify(ledger, never()).postJournal(any(), any());
        assertThat(journals).isEmpty();
    }

    // ---- helpers ----

    private void assertOneBalancedJournal(JournalEntry.Type type, String orgId) {
        assertThat(journals).hasSize(1);
        assertThat(journals.get(0).type()).isEqualTo(type);
        assertThat(journals.get(0).organizationId()).isEqualTo(orgId);
        assertBalanced(postings.get(0));
    }

    private static void assertBalanced(List<Posting> list) {
        long debits = sum(list, Posting.Direction.DEBIT);
        long credits = sum(list, Posting.Direction.CREDIT);
        assertThat(debits).as("debits").isEqualTo(credits);
    }

    private static long sum(List<Posting> list, Posting.Direction dir) {
        return list.stream().filter(p -> p.direction() == dir).mapToLong(Posting::amountCents).sum();
    }

    private static Posting find(List<Posting> list, LedgerAccount.Type type, String owner) {
        return list.stream()
                .filter(p -> p.account().type() == type && Objects.equals(p.account().owner(), owner))
                .findFirst().orElseThrow();
    }

    private static long amount(List<Posting> list, LedgerAccount.Type type, String owner) {
        return find(list, type, owner).amountCents();
    }
}
