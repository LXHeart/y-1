package com.grassland.finance.commerce;

import com.grassland.finance.account.AccountRepository;
import com.grassland.finance.event.EventEnvelope;
import com.grassland.finance.event.OutboxRepository;
import com.grassland.finance.ledger.LedgerService;
import com.grassland.finance.payment.PaymentProviderAdapter;
import com.grassland.finance.security.FinanceException;
import com.grassland.finance.wallet.WalletEntryType;
import com.grassland.finance.wallet.WalletRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public ConsumerPaymentService(
            ConsumerPaymentRepository payments, AccountRepository accounts, WalletRepository wallets,
            LedgerService ledger, PaymentProviderAdapter provider, OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.payments = payments;
        this.accounts = accounts;
        this.wallets = wallets;
        this.ledger = ledger;
        this.provider = provider;
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
        return payments.findPayment(orderRef)
                .switchIfEmpty(Mono.error(new FinanceException(404, "支付不存在")))
                .flatMap(payment -> {
                    if (!payment.organizationId().equals(command.organizationId())
                            || payment.amountCents() != command.amountCents()) {
                        return Mono.error(new FinanceException(409, "退款范围与原支付不一致"));
                    }
                    if ("refunded".equals(payment.status())) {
                        return payments.findRefund(orderRef);
                    }
                    return payments.findSplit(orderRef).hasElement().flatMap(splitExists -> {
                        if (splitExists) {
                            return Mono.error(new FinanceException(409, "已分账订单不能走未核销退款"));
                        }
                        String providerRef = provider.channel() + ":refund:" + orderRef;
                        Mono<ConsumerPaymentRepository.Refund> work = payments.insertRefund(
                                        orderRef, command.amountCents(), command.reason(),
                                        command.operationId(), providerRef)
                                .flatMap(refund -> ledger.postConsumerRefund(
                                                payment.organizationId(), orderRef, refund.amountCents())
                                        .then(payments.markPaymentRefunded(orderRef))
                                        .then(outbox.append(event("ConsumerPaymentRefunded", orderRef, Map.of(
                                                "orderRef", orderRef,
                                                "organizationId", payment.organizationId(),
                                                "consumerAccountId", payment.consumerAccountId(),
                                                "amountCents", refund.amountCents(),
                                                "providerRef", refund.providerRef()))))
                                        .thenReturn(refund))
                                .switchIfEmpty(payments.findRefund(orderRef));
                        return transactions.transactional(work);
                    });
                });
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
                    Mono<ConsumerPaymentRepository.Split> work = payments.insertSplit(
                                    orderRef, command.recommenderAccountId(), command.recommenderAmountCents(),
                                    command.merchantAmountCents(), command.platformFeeCents(), command.operationId())
                            .flatMap(split -> applySplitProjections(payment, split)
                                    .then(ledger.postConsumerSplit(
                                            payment.organizationId(), orderRef, payment.amountCents(),
                                            split.recommenderAccountId(), split.recommenderAmountCents(),
                                            split.merchantAmountCents(), split.platformFeeCents()))
                                    .then(payments.completeSplit(orderRef))
                                    .flatMap(completed -> outbox.append(splitEvent(payment, completed))
                                            .thenReturn(completed)))
                            .switchIfEmpty(payments.findSplit(orderRef).map(existing -> {
                                requireSplitMatch(existing, command);
                                return existing;
                            }));
                    return transactions.transactional(work);
                });
    }

    private Mono<Void> applySplitProjections(
            ConsumerPaymentRepository.Payment payment, ConsumerPaymentRepository.Split split) {
        Mono<Void> merchant = split.merchantAmountCents() == 0
                ? Mono.empty()
                : accounts.creditOrCreate(payment.organizationId(), split.merchantAmountCents()).then();
        Mono<Void> recommender = split.recommenderAmountCents() == 0
                ? Mono.empty()
                : wallets.credit(split.recommenderAccountId(), split.recommenderAmountCents())
                        .then(wallets.appendEntry(
                                split.recommenderAccountId(), WalletEntryType.COMMERCE_COMMISSION,
                                split.recommenderAmountCents(), split.platformFeeCents(), orderRef(payment),
                                "消费订单核销佣金"))
                        .then();
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
                || (command.recommenderAmountCents() > 0 && blank(command.recommenderAccountId()))) {
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
            String operationId) {}
}
