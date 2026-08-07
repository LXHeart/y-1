package com.grassland.finance.ledger;

import com.grassland.finance.payment.ExternalMovement;
import com.grassland.finance.payment.PaymentProviderAdapter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 账本记账服务（HLD §6.4 双录账本，ADR-D01）。
 *
 * <p>每个资金操作生成一条平衡 journal（借贷合计为零），在调用方既有 {@code TransactionalOperator} 事务内落库——
 * 使「余额守卫条件 UPDATE + 账本记账 + outbox append」同生共死。余额行保留为投影+并发守卫（Approach B）；
 * 账本是不可变真相源 + 可重建投影。
 *
 * <p>借/贷方向按账户语义校准（ESCROW/WALLET/FEE 负债收入类 credit 增、debit 减；RESERVE earmark 池 credit 增；
 * SUBSIDY_EXPENSE 费用类 debit 增、credit 回冲），
 * 投影 {@code SUM(credit) - SUM(debit)} 与既有余额行逐字一致。
 *
 * <p>幂等：reserve/release/capture/reverse 带确定性 operationId（{@code <op>:<engagementRef>}），复用 credit-bridge
 * 预检（既有 journal → skip）。credit/withdraw 一次性用户动作，operationId=null。上游状态守卫是幂等主源
 * （状态已迁移→守卫空→不调本服务），operationId 唯一索引是安全网。
 */
@Component
public class LedgerService {

    private static final String CURRENCY = "CNY";

    private final LedgerRepository ledger;
    private final PaymentProviderAdapter psp;

    public LedgerService(LedgerRepository ledger, PaymentProviderAdapter psp) {
        this.ledger = ledger;
        this.psp = psp;
    }

    /** 充值（资金入托管）：Dr EXTERNAL / Cr ESCROW。一次性动作（operationId=null）。 */
    public Mono<Void> postDeposit(String orgId, long amount) {
        List<Posting> postings = List.of(
                Posting.debit(LedgerAccount.external(psp.channel()), amount),
                Posting.credit(LedgerAccount.escrow(orgId), amount));
        return psp.recordExternalMovement(ExternalMovement.in(amount, CURRENCY, null, "deposit"))
                .then(post(JournalEntry.Type.DEPOSIT, null, orgId, null, "deposit", postings));
    }

    /** 预留（earmark 给 engagement）：Dr ESCROW / Cr RESERVE。 */
    public Mono<Void> postReserve(String orgId, String engagementRef, long amount) {
        List<Posting> postings = List.of(
                Posting.debit(LedgerAccount.escrow(orgId), amount),
                Posting.credit(LedgerAccount.reserve(orgId, engagementRef), amount));
        return post(JournalEntry.Type.RESERVE, "reserve:" + engagementRef, orgId, engagementRef,
                "reserve " + engagementRef, postings);
    }

    /** 释放（返还商家）：Dr RESERVE / Cr ESCROW。 */
    public Mono<Void> postRelease(String orgId, String engagementRef, long amount) {
        List<Posting> postings = List.of(
                Posting.debit(LedgerAccount.reserve(orgId, engagementRef), amount),
                Posting.credit(LedgerAccount.escrow(orgId), amount));
        return post(JournalEntry.Type.RELEASE, "release:" + engagementRef, orgId, engagementRef,
                "release " + engagementRef, postings);
    }

    /**
     * 捕获（结算）：Dr RESERVE / Cr WALLET(payout) + Cr FEE(fee)。
     * payout 为 null/0 或无收款人 → Dr RESERVE / Cr FEE(amount)（商家付款转平台收入，钱留平台账）。
     */
    public Mono<Void> postCapture(String orgId, String engagementRef, long amount,
                                  String payeeAccountId, Long payout) {
        return postCapture(orgId, engagementRef, amount, payeeAccountId, payout, 0L);
    }

    /** 捕获含平台补贴：Dr RESERVE(amount) + Dr SUBSIDY(bonus) / Cr WALLET(total payout) + Cr FEE(fee)。 */
    public Mono<Void> postCapture(String orgId, String engagementRef, long amount,
                                  String payeeAccountId, Long payout, long commissionBonusCents) {
        long netPayout = (payout == null) ? 0L : payout;
        validateSettlementAmounts(amount, netPayout, commissionBonusCents);
        long basePayout = netPayout - commissionBonusCents;
        long fee = amount - basePayout;
        List<Posting> postings = new ArrayList<>(4);
        postings.add(Posting.debit(LedgerAccount.reserve(orgId, engagementRef), amount));
        if (commissionBonusCents > 0) {
            postings.add(Posting.debit(LedgerAccount.subsidy(), commissionBonusCents));
        }
        if (netPayout > 0 && payeeAccountId != null) {
            postings.add(Posting.credit(LedgerAccount.wallet(payeeAccountId), netPayout));
        }
        if (fee > 0) {   // 平台抽成为 0（默认）时不落零额 posting（posting.amount_cents CHECK > 0）
            postings.add(Posting.credit(LedgerAccount.fee(), fee));
        }
        assertBalanced(postings);
        return post(JournalEntry.Type.CAPTURE, "capture:" + engagementRef, orgId, engagementRef,
                "capture " + engagementRef, postings);
    }

    /**
     * 冲正（Reversal Journal，HLD §6.4）：Dr WALLET(payout) + Dr FEE(fee) / Cr ESCROW(amount)。
     * 镜像 capture——全额退商家、回扣 payout + fee。无收款人 → Dr FEE(amount) / Cr ESCROW(amount)。
     */
    public Mono<Void> postReverse(String orgId, String engagementRef, long amount,
                                  String payeeAccountId, Long payout) {
        return postReverse(orgId, engagementRef, amount, payeeAccountId, payout, 0L);
    }

    /** 冲正含平台补贴：完整镜像 capture，但商家仍只收到原赏金 amount。 */
    public Mono<Void> postReverse(String orgId, String engagementRef, long amount,
                                  String payeeAccountId, Long payout, long commissionBonusCents) {
        long netPayout = (payout == null) ? 0L : payout;
        validateSettlementAmounts(amount, netPayout, commissionBonusCents);
        long basePayout = netPayout - commissionBonusCents;
        long fee = amount - basePayout;
        List<Posting> postings = new ArrayList<>(4);
        if (netPayout > 0 && payeeAccountId != null) {
            postings.add(Posting.debit(LedgerAccount.wallet(payeeAccountId), netPayout));
        }
        if (fee > 0) {   // 平台抽成为 0（默认）时不落零额 posting（posting.amount_cents CHECK > 0）
            postings.add(Posting.debit(LedgerAccount.fee(), fee));
        }
        postings.add(Posting.credit(LedgerAccount.escrow(orgId), amount));
        if (commissionBonusCents > 0) {
            postings.add(Posting.credit(LedgerAccount.subsidy(), commissionBonusCents));
        }
        assertBalanced(postings);
        return post(JournalEntry.Type.REVERSE, "reverse:" + engagementRef, orgId, engagementRef,
                "reverse " + engagementRef, postings);
    }

    /** 提现（推荐官出账）：Dr WALLET / Cr EXTERNAL。一次性动作（operationId=null）。 */
    public Mono<Void> postWithdraw(String accountId, long amount) {
        List<Posting> postings = List.of(
                Posting.debit(LedgerAccount.wallet(accountId), amount),
                Posting.credit(LedgerAccount.external(psp.channel()), amount));
        return psp.recordExternalMovement(ExternalMovement.out(amount, CURRENCY, null, "withdraw"))
                .then(post(JournalEntry.Type.WITHDRAW, null, null, null, "withdraw", postings));
    }

    private Mono<Void> post(JournalEntry.Type type, String operationId, String orgId,
                            String engagementRef, String memo, List<Posting> postings) {
        if (operationId == null) {
            return write(type, null, orgId, engagementRef, memo, postings);
        }
        // 幂等预检（credit-bridge 三件套之一）：同一 operationId 已记账 → skip。
        return ledger.findJournalIdByOperationId(operationId)
                .hasElement()
                .flatMap(exists -> exists
                        ? Mono.empty()
                        : write(type, operationId, orgId, engagementRef, memo, postings));
    }

    private Mono<Void> write(JournalEntry.Type type, String operationId, String orgId,
                             String engagementRef, String memo, List<Posting> postings) {
        JournalEntry journal = new JournalEntry(
                UUID.randomUUID(), type, operationId, CURRENCY, orgId, engagementRef, memo, Instant.now());
        return ledger.postJournal(journal, postings);
    }

    /** 校验借贷合计为零（防御性；不平衡说明映射写错，fail-fast 不写脏账）。 */
    private static void assertBalanced(List<Posting> postings) {
        long debits = 0L;
        long credits = 0L;
        for (Posting p : postings) {
            if (p.direction() == Posting.Direction.DEBIT) {
                debits = Math.addExact(debits, p.amountCents());
            } else {
                credits = Math.addExact(credits, p.amountCents());
            }
        }
        if (debits != credits) {
            throw new IllegalStateException("账本不平衡：debits=" + debits + " credits=" + credits);
        }
    }

    private static void validateSettlementAmounts(long amount, long payout, long bonus) {
        if (amount <= 0 || payout < 0 || bonus < 0 || bonus > payout) {
            throw new IllegalArgumentException("invalid settlement amounts");
        }
        long basePayout = payout - bonus;
        if (basePayout > amount) {
            throw new IllegalArgumentException("base payout cannot exceed merchant-funded amount");
        }
        Math.addExact(amount, bonus);
    }
}
