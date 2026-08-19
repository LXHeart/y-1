package com.grassland.finance.escrow;

import com.grassland.finance.account.AccountRepository;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.finance.freebie.FreebieEscrowLifecycleService;
import com.grassland.finance.ledger.LedgerService;
import com.grassland.finance.security.FinanceException;
import com.grassland.finance.wallet.PlatformFeePolicy;
import com.grassland.finance.wallet.WalletEntryType;
import com.grassland.finance.wallet.WalletRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** Finance-owned escrow transitions and authoritative settlement reconciliation. */
@Component
public class EscrowLifecycleService {

    private final ReservationRepository reservations;
    private final AccountRepository accounts;
    private final WalletRepository wallets;
    private final OutboxRepository outbox;
    private final PlatformFeePolicy fees;
    private final TransactionalOperator transactions;
    private final LedgerService ledger;
    private final FreebieEscrowLifecycleService freebies;

    public EscrowLifecycleService(
            ReservationRepository reservations,
            AccountRepository accounts,
            WalletRepository wallets,
            OutboxRepository outbox,
            PlatformFeePolicy fees,
            TransactionalOperator transactions,
            LedgerService ledger,
            FreebieEscrowLifecycleService freebies) {
        this.reservations = reservations;
        this.accounts = accounts;
        this.wallets = wallets;
        this.outbox = outbox;
        this.fees = fees;
        this.transactions = transactions;
        this.ledger = ledger;
        this.freebies = freebies;
    }

    public Mono<FundsReservation> release(FundsReservation reservation) {
        return transactions.transactional(releaseWork(reservation));
    }

    public Mono<FundsReservation> capture(FundsReservation reservation) {
        return capture(reservation, null);
    }

    /** Capture the selected gross tier and return any reserved remainder to the merchant. */
    public Mono<FundsReservation> capture(FundsReservation reservation, Long settlementAmountCents) {
        return transactions.transactional(captureWork(reservation, settlementAmountCents));
    }

    public Mono<FundsReservation> reverse(FundsReservation reservation) {
        return transactions.transactional(reverseWork(reservation));
    }

    public Mono<ReconciliationResult> reconcile(
            String organizationId, String engagementRef, String finalDecision) {
        return reservations.findByEngagementRef(engagementRef)
                .flatMap(reservation -> {
                    if (!organizationId.equals(reservation.organizationId())) {
                        return Mono.error(new FinanceException(403, "无权操作该组织预留"));
                    }
                    return switch (finalDecision) {
                        case "for_merchant" -> reconcileForMerchant(reservation);
                        case "for_recommender" -> reconcileForRecommender(reservation);
                        default -> Mono.error(new FinanceException(400, "未知终局判决"));
                    };
                })
                // ADR-D12：无 funds_reservation 时回落到霸王餐押金对账（freebie 任务没有商家预留，
                // 归因矩阵反向：for_merchant → 补偿商家、for_recommender → 退推荐官）。调用方零改动。
                // 两表皆缺保持既有契约 reason=reservation_missing（ReservationReconciliationIT 锁定）。
                .switchIfEmpty(Mono.defer(() -> freebies.reconcile(
                                organizationId, engagementRef, finalDecision))
                        .map(outcome -> "missing".equals(outcome.outcome())
                                ? new ReconciliationResult("missing", "reservation_missing", null)
                                : new ReconciliationResult(outcome.outcome(), outcome.reason(), null)));
    }

    private Mono<ReconciliationResult> reconcileForMerchant(FundsReservation reservation) {
        return switch (reservation.status()) {
            case "reserved" -> release(reservation)
                    .map(updated -> repaired("released", updated));
            case "captured" -> reverse(reservation)
                    .map(updated -> repaired("refunded", updated))
                    .onErrorResume(FinanceException.class, error -> error.status() == 409
                            ? Mono.just(new ReconciliationResult(
                                    "blocked", "manual_clawback_required", reservation))
                            : Mono.error(error));
            case "released", "refunded" -> Mono.just(new ReconciliationResult(
                    "verified", "already_refunded", reservation));
            default -> Mono.just(new ReconciliationResult(
                    "conflict", "unexpected_reservation_state", reservation));
        };
    }

    private Mono<ReconciliationResult> reconcileForRecommender(FundsReservation reservation) {
        return switch (reservation.status()) {
            case "reserved" -> capture(reservation)
                    .map(updated -> repaired("captured", updated));
            case "captured" -> Mono.just(new ReconciliationResult(
                    "verified", "already_captured", reservation));
            case "released", "refunded" -> Mono.just(new ReconciliationResult(
                    "conflict", "released_but_recommender_won", reservation));
            default -> Mono.just(new ReconciliationResult(
                    "conflict", "unexpected_reservation_state", reservation));
        };
    }

    private Mono<FundsReservation> releaseWork(FundsReservation reservation) {
        return reservations.release(reservation.id())
                .switchIfEmpty(Mono.error(new FinanceException(409, "该预留已处理")))
                .flatMap(released -> accounts.credit(
                                reservation.organizationId(), reservation.amountCents())
                        .then(ledger.postRelease(
                                reservation.organizationId(), reservation.engagementRef(), reservation.amountCents()))
                        .then(outbox.append(reservationEnvelope("FundsReleased", released)))
                        .thenReturn(released));
    }

    private Mono<FundsReservation> captureWork(FundsReservation reservation, Long requestedSettlementAmountCents) {
        long settlementAmount = requestedSettlementAmountCents == null
                ? reservation.amountCents() : requestedSettlementAmountCents;
        if (settlementAmount < 1 || settlementAmount > reservation.amountCents()) {
            return Mono.error(new FinanceException(400, "阶梯结算金额必须在预留金额范围内"));
        }
        long settlementBonus = reservation.payeeAccountId() == null
                ? 0 : CommissionBonusPolicy.calculateCents(settlementAmount, reservation.commissionBonusBps());
        Long payout = reservation.payeeAccountId() == null ? null : Math.addExact(
                fees.payoutFor(settlementAmount), settlementBonus);
        return reservations.capture(reservation.id(), payout, settlementAmount, settlementBonus)
                .switchIfEmpty(Mono.error(new FinanceException(409, "该预留已处理")))
                .flatMap(this::splitToPayee)
                .flatMap(captured -> ledger.postCapture(
                                captured.organizationId(), captured.engagementRef(),
                                settledGross(captured), captured.payeeAccountId(), captured.payoutCents(),
                                captured.effectiveSettlementCommissionBonusCents())
                        .then(releaseRemainder(captured))
                        .then(outbox.append(reservationEnvelope("FundsCaptured", captured)))
                        .thenReturn(captured));
    }

    private Mono<FundsReservation> reverseWork(FundsReservation reservation) {
        return clawbackFromPayee(reservation)
                .then(reservations.reverse(reservation.id()))
                .switchIfEmpty(Mono.error(new FinanceException(409, "该预留不可冲正（须 captured）")))
                .flatMap(refunded -> accounts.credit(
                                reservation.organizationId(), settledGross(reservation))
                        .then(ledger.postReverse(
                                reservation.organizationId(), reservation.engagementRef(),
                                settledGross(reservation), reservation.payeeAccountId(), reservation.payoutCents(),
                                reservation.effectiveSettlementCommissionBonusCents()))
                        .then(outbox.append(reservationEnvelope("FundsReversed", refunded)))
                        .thenReturn(refunded));
    }

    private Mono<FundsReservation> splitToPayee(FundsReservation captured) {
        if (captured.payeeAccountId() == null
                || captured.payoutCents() == null
                || captured.payoutCents() <= 0) {
            return Mono.just(captured);
        }
        long payout = captured.payoutCents();
        long basePayout = payout - captured.effectiveSettlementCommissionBonusCents();
        long fee = settledGross(captured) - basePayout;
        return wallets.credit(captured.payeeAccountId(), payout)
                .then(wallets.appendEntry(
                        captured.payeeAccountId(),
                        WalletEntryType.TASK_PAYOUT,
                        payout,
                        fee,
                        captured.effectiveSettlementCommissionBonusCents(),
                        captured.engagementRef(),
                        "任务结算入账"))
                .then(outbox.append(new EventEnvelope(
                        UUID.randomUUID().toString(),
                        "SplitCompleted",
                        "FundsReservation",
                        captured.id(),
                        1,
                        Instant.now(),
                        null,
                        Map.of(
                                "engagementRef", String.valueOf(captured.engagementRef()),
                                "payeeAccountId", captured.payeeAccountId(),
                                "grossCents", settledGross(captured),
                                "reservedGrossCents", captured.amountCents(),
                                "basePayoutCents", basePayout,
                                "payoutCents", payout,
                                "platformFeeCents", fee,
                                "commissionBonusBps", captured.commissionBonusBps(),
                                "commissionBonusCents", captured.effectiveSettlementCommissionBonusCents()))))
                .thenReturn(captured);
    }

    private Mono<Void> releaseRemainder(FundsReservation captured) {
        long remainder = captured.amountCents() - settledGross(captured);
        if (remainder == 0) return Mono.empty();
        return accounts.credit(captured.organizationId(), remainder)
                .then(ledger.postRelease(captured.organizationId(), captured.engagementRef(), remainder))
                .then(outbox.append(reservationEnvelope("FundsPartiallyReleased", captured)))
                .then();
    }

    private static long settledGross(FundsReservation reservation) {
        return reservation.effectiveSettlementAmountCents();
    }

    private Mono<Void> clawbackFromPayee(FundsReservation reservation) {
        if (reservation.payeeAccountId() == null
                || reservation.payoutCents() == null
                || reservation.payoutCents() <= 0) {
            return Mono.empty();
        }
        long payout = reservation.payoutCents();
        return wallets.debit(reservation.payeeAccountId(), payout)
                .switchIfEmpty(Mono.error(new FinanceException(
                        409, "推荐官余额不足以冲正（可能已提现），需人工处理")))
                .flatMap(wallet -> wallets.appendEntry(
                        reservation.payeeAccountId(),
                        WalletEntryType.CLAWBACK,
                        -payout,
                        0,
                        reservation.effectiveSettlementCommissionBonusCents(),
                        reservation.engagementRef(),
                        "争议冲正扣回"))
                .then();
    }

    /** {@code payeeAccountId} 是收款推荐官的用户账号（非 finance ledger account），
     *  供 identity 通知中心解析钱包类通知收件人（Slice 12 Stage 3）；无分账对象时不携带。 */
    private EventEnvelope reservationEnvelope(String eventType, FundsReservation reservation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", reservation.id());
        payload.put("accountId", reservation.accountId());
        payload.put("organizationId", reservation.organizationId());
        payload.put("engagementRef", reservation.engagementRef());
        payload.put("amountCents", reservation.amountCents());
        payload.put("status", reservation.status());
        payload.put("commissionBonusBps", reservation.commissionBonusBps());
        payload.put("commissionBonusCents", reservation.commissionBonusCents());
        payload.put("settlementAmountCents", reservation.settlementAmountCents());
        payload.put("settlementCommissionBonusCents", reservation.effectiveSettlementCommissionBonusCents());
        if (reservation.payeeAccountId() != null) {
            payload.put("payeeAccountId", reservation.payeeAccountId());
        }
        if (reservation.payoutCents() != null) {
            payload.put("payoutCents", reservation.payoutCents());
            long basePayout = reservation.payoutCents() - reservation.effectiveSettlementCommissionBonusCents();
            payload.put("basePayoutCents", basePayout);
            payload.put("platformFeeCents", settledGross(reservation) - basePayout);
        }
        return new EventEnvelope(
                UUID.randomUUID().toString(),
                eventType,
                "FundsReservation",
                reservation.id(),
                1,
                Instant.now(),
                null,
                payload);
    }

    private static ReconciliationResult repaired(String reason, FundsReservation reservation) {
        return new ReconciliationResult("repaired", reason, reservation);
    }

    public record ReconciliationResult(
            String outcome, String reason, FundsReservation reservation) {}
}
