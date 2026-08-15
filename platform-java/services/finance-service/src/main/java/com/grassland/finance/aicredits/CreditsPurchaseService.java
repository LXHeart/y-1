package com.grassland.finance.aicredits;

import com.grassland.finance.aicredits.CreditsPackageRepository.PackageView;
import com.grassland.finance.aicredits.CreditsPurchaseOrderRepository.PurchaseOrder;
import com.grassland.finance.credits.CreditsService;
import com.grassland.finance.event.EventEnvelope;
import com.grassland.finance.event.OutboxRepository;
import com.grassland.finance.ledger.LedgerService;
import com.grassland.finance.payment.PaymentProviderAdapter;
import com.grassland.finance.provider.ProviderOperationRepository;
import com.grassland.finance.security.FinanceException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 积分包购买编排（AI 套餐 v1 Slice B）。镜像 {@code ConsumerPaymentService.pay} 的单事务范式：
 * 订单落库（operationId 幂等）→ 账本过账（含外部腿记录，sandbox 即时）→ provider operation
 * （对账事实）→ 积分入账（type='purchase'，键 {@code purchase:<orderId>}）→ markPaid → outbox。
 *
 * <p>sandbox 通道下「支付」即账本 {@code EXTERNAL} 腿的记录（无独立外部调用）；
 * 真实 PSP 接入时在 adapter 层替换，本编排不变。生产 overlay 强制非 sandbox 且真实
 * adapter 缺失时启动 fail-fast——购买入口在生产天然关闭，符合「等真实支付再接」决策。
 */
@Service
public class CreditsPurchaseService {

    private final CreditsPackageRepository packages;
    private final CreditsPurchaseOrderRepository orders;
    private final LedgerService ledger;
    private final PaymentProviderAdapter provider;
    private final ProviderOperationRepository providerOperations;
    private final CreditsService credits;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public CreditsPurchaseService(
            CreditsPackageRepository packages, CreditsPurchaseOrderRepository orders,
            LedgerService ledger, PaymentProviderAdapter provider,
            ProviderOperationRepository providerOperations, CreditsService credits,
            OutboxRepository outbox, TransactionalOperator transactions) {
        this.packages = packages;
        this.orders = orders;
        this.ledger = ledger;
        this.provider = provider;
        this.providerOperations = providerOperations;
        this.credits = credits;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    /** 购买积分包。同 operationId 重放返回既有订单（幂等，不双入账）。 */
    public Mono<PurchaseOutcome> purchase(String accountId, String packageId, String operationId) {
        String opId = operationId == null || operationId.isBlank()
                ? UUID.randomUUID().toString() : operationId;
        Mono<PurchaseOutcome> work = packages.findById(packageId)
                .switchIfEmpty(Mono.error(new FinanceException(404, "积分包不存在")))
                .map(pkg -> {
                    if (!"active".equals(pkg.status())) {
                        throw new FinanceException(409, "积分包不在售");
                    }
                    return pkg;
                })
                .flatMap(pkg -> orders.insert(
                                accountId, pkg.id(), pkg.versionId(),
                                pkg.priceCents(), pkg.creditsAmount(), provider.channel(),
                                provider.channel() + ":ai-credit:" + UUID.randomUUID(), opId)
                        .flatMap(order -> settle(accountId, pkg, order))
                        .switchIfEmpty(Mono.defer(() -> replayExisting(opId))));
        return transactions.transactional(work);
    }

    private Mono<PurchaseOutcome> settle(String accountId, PackageView pkg, PurchaseOrder order) {
        return ledger.postAiCreditPurchase(order.id(), order.priceCents())
                .then(providerOperations.register(
                        order.provider(), opKey(order.operationId()), "payment", order.id(),
                        order.priceCents(), "CNY", order.providerRef()))
                .then(credits.purchaseCredit(accountId, order.creditsAmount(),
                        "购买积分包 " + pkg.name() + "（v" + pkg.version() + "）",
                        "purchase:" + order.id()))
                .then(orders.markPaid(order.id()))
                .then(outbox.append(event(order, accountId)))
                .then(credits.balance(accountId)
                        .map(account -> new PurchaseOutcome(order.id(), "paid",
                                order.creditsAmount(), account.balance())));
    }

    /** 幂等回放：返回既有订单（已 paid 则带余额，created 视为异常态直接透出）。 */
    private Mono<PurchaseOutcome> replayExisting(String operationId) {
        return orders.findByOperationId(operationId)
                .map(existing -> new PurchaseOutcome(existing.id(), existing.status(),
                        existing.creditsAmount(), null));
    }

    private static String opKey(String operationId) {
        // provider operation 的 operation_id 与购买幂等键共用（同一次购买一条对账事实）。
        return operationId;
    }

    private static EventEnvelope event(PurchaseOrder order, String accountId) {
        return new EventEnvelope(UUID.randomUUID().toString(), "AiCreditsPurchased",
                "CreditsPurchaseOrder", order.id(), 1, Instant.now(), order.id(),
                Map.of(
                        "orderId", order.id(),
                        "accountId", accountId,
                        "packageId", order.packageId(),
                        "priceCents", order.priceCents(),
                        "creditsAmount", order.creditsAmount()));
    }

    /** 购买结果（balance 仅新购买时带回放为 null，由前端拉取）。 */
    public record PurchaseOutcome(String orderId, String status, int creditsAmount, Integer balance) {}
}
