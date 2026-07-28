package com.grassland.finance.escrow;

import com.grassland.finance.account.AccountRepository;
import com.grassland.finance.event.EventEnvelope;
import com.grassland.finance.event.OutboxRepository;
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

    public EscrowLifecycleService(
            ReservationRepository reservations,
            AccountRepository accounts,
            WalletRepository wallets,
            OutboxRepository outbox,
            PlatformFeePolicy fees,
            TransactionalOperator transactions) {
        this.reservations = reservations;
        this.accounts = accounts;
        this.wallets = wallets;
        this.outbox = outbox;
        this.fees = fees;
        this.transactions = transactions;
    }

    public Mono<FundsReservation> release(FundsReservation reservation) {
        return transactions.transactional(releaseWork(reservation));
    }

    public Mono<FundsReservation> capture(FundsReservation reservation) {
        return transactions.transactional(captureWork(reservation));
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
                .defaultIfEmpty(new ReconciliationResult(
                        "missing", "reservation_missing", null));
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
                        .then(outbox.append(reservationEnvelope("FundsReleased", released)))
                        .thenReturn(released));
    }

    private Mono<FundsReservation> captureWork(FundsReservation reservation) {
        Long payout = reservation.payeeAccountId() == null
                ? null
                : fees.payoutFor(reservation.amountCents());
        return reservations.capture(reservation.id(), payout)
                .switchIfEmpty(Mono.error(new FinanceException(409, "该预留已处理")))
                .flatMap(this::splitToPayee)
                .flatMap(captured -> outbox.append(reservationEnvelope("FundsCaptured", captured))
                        .thenReturn(captured));
    }

    private Mono<FundsReservation> reverseWork(FundsReservation reservation) {
        return clawbackFromPayee(reservation)
                .then(reservations.reverse(reservation.id()))
                .switchIfEmpty(Mono.error(new FinanceException(409, "该预留不可冲正（须 captured）")))
                .flatMap(refunded -> accounts.credit(
                                reservation.organizationId(), reservation.amountCents())
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
        long fee = captured.amountCents() - payout;
        return wallets.credit(captured.payeeAccountId(), payout)
                .then(wallets.appendEntry(
                        captured.payeeAccountId(),
                        WalletEntryType.TASK_PAYOUT,
                        payout,
                        fee,
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
                                "grossCents", captured.amountCents(),
                                "payoutCents", payout,
                                "platformFeeCents", fee))))
                .thenReturn(captured);
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
                        reservation.engagementRef(),
                        "争议冲正扣回"))
                .then();
    }

    private EventEnvelope reservationEnvelope(String eventType, FundsReservation reservation) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("reservationId", reservation.id());
        payload.put("accountId", reservation.accountId());
        payload.put("organizationId", reservation.organizationId());
        payload.put("engagementRef", reservation.engagementRef());
        payload.put("amountCents", reservation.amountCents());
        payload.put("status", reservation.status());
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
