package com.grassland.finance.commerce;

import com.grassland.finance.account.AccountRepository;
import com.grassland.finance.event.EventEnvelope;
import com.grassland.finance.event.OutboxRepository;
import com.grassland.finance.ledger.LedgerService;
import com.grassland.finance.payment.PaymentProviderAdapter;
import com.grassland.finance.provider.ProviderOperationRepository;
import com.grassland.finance.security.FinanceException;
import com.grassland.finance.wallet.WalletEntryType;
import com.grassland.finance.wallet.WalletRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/** Sandbox-capable consumer payment/refund/split domain service. */
@Component
public class ConsumerPaymentService {

    private final ConsumerPaymentRepository payments;
    private final AccountRepository accounts;
    private final WalletRepository wallets;
    private final LedgerService ledger;
    private final PaymentProviderAdapter provider;
    private final ProviderOperationRepository providerOperations;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public ConsumerPaymentService(
            ConsumerPaymentRepository payments, AccountRepository accounts, WalletRepository wallets,
            LedgerService ledger, PaymentProviderAdapter provider,
            ProviderOperationRepository providerOperations, OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.payments = payments;
        this.accounts = accounts;
        this.wallets = wallets;
        this.ledger = ledger;
        this.provider = provider;
        this.providerOperations = providerOperations;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    public Mono<ConsumerPaymentRepository.Payment> pay(PaymentCommand command) {
        validatePayment(command);
        String providerRef = provider.channel() + ":payment:" + command.orderRef();
        Mono<ConsumerPaymentRepository.Payment> work = payments.insertPayment(
                        command.orderRef(), command.consumerAccountId(), command.organizationId(),
                        command.amountCents(), provider.channel(), providerRef, command.operationId())
                .flatMap(payment -> ledger.postConsumerPayment(
                                payment.organizationId(), payment.orderRef(), payment.amountCents())
                        .then(providerOperations.register(
                                payment.channel(), payment.operationId(), "payment", payment.orderRef(),
                                payment.amountCents(), payment.currency(), payment.providerRef()))
                        .then(outbox.append(event("ConsumerPaymentSucceeded", payment.orderRef(), Map.of(
                                "orderRef", payment.orderRef(),
                                "consumerAccountId", payment.consumerAccountId(),
                                "organizationId", payment.organizationId(),
                                "amountCents", payment.amountCents(),
                                "providerRef", payment.providerRef()))))
                        .thenReturn(payment))
                .switchIfEmpty(payments.findPayment(command.orderRef()).map(existing -> {
                    requirePaymentMatch(existing, command);
                    return existing;
                }));
        return transactions.transactional(work);
    }

    public Mono<ConsumerPaymentRepository.Refund> refund(String orderRef, RefundCommand command) {
        if (command.amountCents() <= 0 || blank(command.operationId())) {
            return Mono.error(new IllegalArgumentException("退款金额和幂等键不能为空"));
        }
        return payments.findRefundByOperation(command.operationId())
                .switchIfEmpty(Mono.defer(() -> refundFresh(orderRef, command)));
    }

    private Mono<ConsumerPaymentRepository.Refund> refundFresh(String orderRef, RefundCommand command) {
        return payments.findPayment(orderRef)
                .switchIfEmpty(Mono.error(new FinanceException(404, "支付不存在")))
                .flatMap(payment -> {
                    if (!payment.organizationId().equals(command.organizationId())
                            || command.amountCents() > payment.amountCents()
                            || command.amountCents() <= 0) {
                        return Mono.error(new FinanceException(409, "退款范围与原支付不一致"));
                    }
                    return payments.findSplit(orderRef).flatMap(split -> {
                        if ("completed".equals(split.status())) {
                            return refundAfterSplit(payment, split, orderRef, command);
                        }
                        return Mono.error(new FinanceException(409, "订单分账处理中，暂不能退款"));
                    }).switchIfEmpty(Mono.defer(() -> {
                        String providerRef = provider.channel() + ":refund:" + command.operationId();
                        Mono<ConsumerPaymentRepository.Refund> work = payments.reserveRefund(
                                        orderRef, command.amountCents(), command.operationId())
                                .switchIfEmpty(Mono.error(new FinanceException(409, "退款金额超过可退余额或支付状态不可退款")))
                                .flatMap(reserved -> payments.insertRefund(
                                                orderRef, command.amountCents(), command.reason(),
                                                command.operationId(), providerRef)
                                        .flatMap(refund -> ledger.postConsumerRefund(
                                                        reserved.organizationId(), orderRef, refund.amountCents(),
                                                        "consumer-refund:" + refund.operationId())
                                                .then(providerOperations.register(
                                                        reserved.channel(), refund.operationId(), "refund", orderRef,
                                                        refund.amountCents(), reserved.currency(), refund.providerRef()))
                                                .then(outbox.append(event("ConsumerPaymentRefunded", orderRef, Map.of(
                                                        "orderRef", orderRef,
                                                        "organizationId", reserved.organizationId(),
                                                        "consumerAccountId", reserved.consumerAccountId(),
                                                        "amountCents", refund.amountCents(),
                                                        "refundedAmountCents", reserved.refundedAmountCents(),
                                                        "paymentStatus", reserved.status(),
                                                        "providerRef", refund.providerRef()))))
                                                .thenReturn(refund)))
                                .switchIfEmpty(payments.findRefundByOperation(command.operationId()));
                        return transactions.transactional(work);
                    }));
                });
    }

    private Mono<ConsumerPaymentRepository.Refund> refundAfterSplit(
            ConsumerPaymentRepository.Payment payment, ConsumerPaymentRepository.Split split,
            String orderRef, RefundCommand command) {
        String providerRef = provider.channel() + ":refund:" + command.operationId();
        Mono<ConsumerPaymentRepository.Refund> work = payments.reserveRefund(
                        orderRef, command.amountCents(), command.operationId())
                .switchIfEmpty(Mono.error(new FinanceException(409, "退款金额超过可退余额或支付状态不可退款")))
                .flatMap(reserved -> payments.findSplitAllocations(orderRef).collectList()
                        .flatMap(rows -> {
                            List<ConsumerPaymentRepository.SplitAllocation> source = rows.isEmpty()
                                    ? (split.recommenderAmountCents() == 0 ? List.of() : List.of(
                                        new ConsumerPaymentRepository.SplitAllocation(
                                            split.recommenderAccountId(), split.recommenderAmountCents()))) : rows;
                            long amount = command.amountCents();
                            List<ConsumerPaymentRepository.SplitAllocation> refunds = new ArrayList<>();
                            long recommenderRefund = 0;
                            for (ConsumerPaymentRepository.SplitAllocation row : source) {
                                long value = Math.min(row.amountCents(), amount * row.amountCents() / payment.amountCents());
                                refunds.add(new ConsumerPaymentRepository.SplitAllocation(row.recommenderAccountId(), value));
                                recommenderRefund += value;
                            }
                            long merchantRefund = Math.min(split.merchantAmountCents(),
                                    amount * split.merchantAmountCents() / payment.amountCents());
                            long platformRefund = amount - recommenderRefund - merchantRefund;
                            if (platformRefund > split.platformFeeCents()) {
                                long overflow = platformRefund - split.platformFeeCents();
                                merchantRefund = Math.min(split.merchantAmountCents(), merchantRefund + overflow);
                                platformRefund = amount - recommenderRefund - merchantRefund;
                            }
                            Mono<Void> walletsDebit = reactor.core.publisher.Flux.fromIterable(refunds)
                                    .filter(r -> r.amountCents() > 0)
                                    .flatMap(r -> wallets.debit(r.recommenderAccountId(), r.amountCents())
                                            .switchIfEmpty(Mono.error(new FinanceException(409, "推荐官余额不足，无法完成售后退款"))))
                                    .then();
                            Mono<Void> merchantDebit = merchantRefund == 0 ? Mono.empty()
                                    : accounts.decrement(payment.organizationId(), merchantRefund)
                                        .switchIfEmpty(Mono.error(new FinanceException(409, "商家余额不足，无法完成售后退款"))).then();
                            return walletsDebit.then(merchantDebit)
                                    .then(ledger.postConsumerSplitRefund(payment.organizationId(), orderRef, amount,
                                            refunds.stream().map(r -> new LedgerService.ConsumerSplitAllocation(
                                                    r.recommenderAccountId(), r.amountCents())).toList(),
                                            merchantRefund, platformRefund, "consumer-refund:" + command.operationId()))
                                    .then(payments.insertRefund(orderRef, amount, command.reason(), command.operationId(), providerRef))
                                    .flatMap(refund -> providerOperations.register(payment.channel(), refund.operationId(), "refund",
                                            orderRef, refund.amountCents(), payment.currency(), refund.providerRef())
                                            .then(outbox.append(event("ConsumerPaymentRefunded", orderRef, Map.of(
                                                    "orderRef", orderRef, "organizationId", payment.organizationId(),
                                                    "consumerAccountId", payment.consumerAccountId(), "amountCents", refund.amountCents(),
                                                    "refundedAmountCents", reserved.refundedAmountCents(), "paymentStatus", reserved.status(),
                                                    "providerRef", refund.providerRef())))).thenReturn(refund));
                        }));
        return transactions.transactional(work);
    }

    public Mono<ConsumerPaymentRepository.Split> split(String orderRef, SplitCommand command) {
        validateSplit(command);
        return payments.findPayment(orderRef)
                .switchIfEmpty(Mono.error(new FinanceException(404, "支付不存在")))
                .flatMap(payment -> {
                    if (!payment.organizationId().equals(command.organizationId())
                            || payment.amountCents() != command.totalAmountCents()
                            || !"succeeded".equals(payment.status())) {
                        return Mono.error(new FinanceException(409, "分账范围或支付状态不匹配"));
                    }
                    long allocationTotal = command.allocations() == null ? command.recommenderAmountCents()
                            : command.allocations().stream().mapToLong(SplitAllocationCommand::amountCents).sum();
                    if (allocationTotal != command.recommenderAmountCents()) {
                        return Mono.error(new FinanceException(409, "推荐官分配合计不匹配"));
                    }
                    List<ConsumerPaymentRepository.SplitAllocation> allocations = command.allocations() == null
                            ? List.of()
                            : command.allocations().stream().map(a -> new ConsumerPaymentRepository.SplitAllocation(
                                    a.recommenderAccountId(), a.amountCents())).toList();
                    Mono<ConsumerPaymentRepository.Split> work = payments.insertSplit(
                                    orderRef, command.recommenderAccountId(), command.recommenderAmountCents(),
                                    command.merchantAmountCents(), command.platformFeeCents(), command.operationId())
                            .flatMap(split -> applySplitProjections(payment, split, allocations)
                                    .then(allocations.isEmpty()
                                            ? ledger.postConsumerSplit(payment.organizationId(), orderRef, payment.amountCents(),
                                                split.recommenderAccountId(), split.recommenderAmountCents(),
                                                split.merchantAmountCents(), split.platformFeeCents())
                                            : ledger.postConsumerSplit(payment.organizationId(), orderRef, payment.amountCents(),
                                                allocations.stream().map(a -> new LedgerService.ConsumerSplitAllocation(
                                                        a.recommenderAccountId(), a.amountCents())).toList(),
                                                split.merchantAmountCents(), split.platformFeeCents(),
                                                "consumer-split:" + orderRef))
                                    .then(payments.insertSplitAllocations(orderRef, split.operationId(), allocations))
                                    .then(payments.completeSplit(orderRef))
                                    .flatMap(completed -> providerOperations.register(
                                                    payment.channel(), completed.operationId(), "split", orderRef,
                                                    payment.amountCents(), payment.currency(),
                                                    provider.channel() + ":split:" + orderRef)
                                            .then(outbox.append(splitEvent(payment, completed)))
                                            .thenReturn(completed)))
                            .switchIfEmpty(payments.findSplit(orderRef).map(existing -> {
                                requireSplitMatch(existing, command);
                                return existing;
                            }));
                    return transactions.transactional(work);
                });
    }

    private Mono<Void> applySplitProjections(
            ConsumerPaymentRepository.Payment payment, ConsumerPaymentRepository.Split split,
            List<ConsumerPaymentRepository.SplitAllocation> allocations) {
        Mono<Void> merchant = split.merchantAmountCents() == 0
                ? Mono.empty()
                : accounts.creditOrCreate(payment.organizationId(), split.merchantAmountCents()).then();
        Mono<Void> recommender = allocations.isEmpty() && split.recommenderAmountCents() == 0
                ? Mono.empty()
                : allocations.isEmpty()
                    ? wallets.credit(split.recommenderAccountId(), split.recommenderAmountCents())
                            .then(wallets.appendEntry(split.recommenderAccountId(), WalletEntryType.COMMERCE_COMMISSION,
                                    split.recommenderAmountCents(), split.platformFeeCents(), orderRef(payment), "消费订单核销佣金")).then()
                    : reactor.core.publisher.Flux.fromIterable(allocations)
                            .flatMap(a -> wallets.credit(a.recommenderAccountId(), a.amountCents())
                                    .then(wallets.appendEntry(a.recommenderAccountId(), WalletEntryType.COMMERCE_COMMISSION,
                                            a.amountCents(), 0, orderRef(payment), "消费订单多推荐官佣金"))).then();
        return merchant.then(recommender);
    }

    private static String orderRef(ConsumerPaymentRepository.Payment payment) {
        return payment.orderRef();
    }

    private static EventEnvelope splitEvent(
            ConsumerPaymentRepository.Payment payment, ConsumerPaymentRepository.Split split) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderRef", payment.orderRef());
        payload.put("organizationId", payment.organizationId());
        if (split.recommenderAccountId() != null) {
            payload.put("recommenderAccountId", split.recommenderAccountId());
        }
        payload.put("recommenderAmountCents", split.recommenderAmountCents());
        payload.put("merchantAmountCents", split.merchantAmountCents());
        payload.put("platformFeeCents", split.platformFeeCents());
        return event("ConsumerPaymentSplitCompleted", payment.orderRef(), payload);
    }

    private static EventEnvelope event(String type, String orderRef, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID().toString(), type, "ConsumerPayment", orderRef,
                1, Instant.now(), orderRef, payload);
    }

    private static void validatePayment(PaymentCommand command) {
        if (blank(command.orderRef()) || blank(command.consumerAccountId())
                || blank(command.organizationId()) || command.amountCents() <= 0
                || blank(command.operationId())) {
            throw new IllegalArgumentException("支付参数不完整");
        }
    }

    private static void validateSplit(SplitCommand command) {
        long sum = Math.addExact(Math.addExact(
                command.recommenderAmountCents(), command.merchantAmountCents()), command.platformFeeCents());
        if (command.totalAmountCents() <= 0 || command.recommenderAmountCents() < 0
                || command.merchantAmountCents() < 0 || command.platformFeeCents() < 0
                || sum != command.totalAmountCents() || blank(command.operationId())
                || (command.recommenderAmountCents() > 0 && blank(command.recommenderAccountId())
                    && (command.allocations() == null || command.allocations().isEmpty()))
                || (command.allocations() != null && command.allocations().stream().anyMatch(a ->
                    blank(a.recommenderAccountId()) || a.amountCents() <= 0))) {
            throw new IllegalArgumentException("分账金额不合法");
        }
    }

    private static void requirePaymentMatch(
            ConsumerPaymentRepository.Payment existing, PaymentCommand command) {
        if (!existing.consumerAccountId().equals(command.consumerAccountId())
                || !existing.organizationId().equals(command.organizationId())
                || existing.amountCents() != command.amountCents()
                || !existing.operationId().equals(command.operationId())) {
            throw new FinanceException(409, "订单支付幂等参数冲突");
        }
    }

    private static void requireSplitMatch(ConsumerPaymentRepository.Split existing, SplitCommand command) {
        if (!java.util.Objects.equals(existing.recommenderAccountId(), command.recommenderAccountId())
                || existing.recommenderAmountCents() != command.recommenderAmountCents()
                || existing.merchantAmountCents() != command.merchantAmountCents()
                || existing.platformFeeCents() != command.platformFeeCents()
                || !existing.operationId().equals(command.operationId())) {
            throw new FinanceException(409, "订单分账幂等参数冲突");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record PaymentCommand(
            String orderRef, String consumerAccountId, String organizationId,
            long amountCents, String operationId) {}

    public record RefundCommand(
            String organizationId, long amountCents, String operationId, String reason) {}

    public record SplitCommand(
            String organizationId, long totalAmountCents, String recommenderAccountId,
            long recommenderAmountCents, long merchantAmountCents, long platformFeeCents,
            String operationId, List<SplitAllocationCommand> allocations) {}

    public record SplitAllocationCommand(String recommenderAccountId, long amountCents) {}
}
