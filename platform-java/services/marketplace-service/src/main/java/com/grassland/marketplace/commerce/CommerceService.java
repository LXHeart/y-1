package com.grassland.marketplace.commerce;

import com.grassland.marketplace.commerce.CommerceModels.OfferDetail;
import com.grassland.marketplace.commerce.CommerceModels.Order;
import com.grassland.marketplace.commerce.CommerceModels.Review;
import com.grassland.marketplace.event.EventEnvelope;
import com.grassland.marketplace.event.OutboxRepository;
import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import com.grassland.marketplace.security.MarketplaceException;
import com.grassland.marketplace.taskcatalog.TaskResourceAuthorization;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Marketplace-owned package, inventory, consumer order, redemption and review lifecycle. */
@Component
public class CommerceService {

    private final CommerceRepository repository;
    private final TaskResourceAuthorization authorization;
    private final RedeemCodeCodec codes;
    private final FinanceCommerceClient finance;
    private final OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public CommerceService(
            CommerceRepository repository, TaskResourceAuthorization authorization,
            RedeemCodeCodec codes, FinanceCommerceClient finance, OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.repository = repository;
        this.authorization = authorization;
        this.codes = codes;
        this.finance = finance;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    public Mono<OfferDetail> createOffer(Caller caller, OfferCommand command) {
        CommerceRepository.OfferInput input = validateOffer(command);
        return authorization.requireScope(
                        caller, command.organizationId(), command.storeId(), "manager")
                .flatMap(scope -> {
                    String packageId = UUID.randomUUID().toString();
                    String versionId = UUID.randomUUID().toString();
                    Mono<OfferDetail> work = repository.insertOffer(
                                    packageId, caller.accountId(), scope.organizationId(),
                                    scope.storeId(), blankToNull(command.taskId()))
                            .then(repository.insertVersion(versionId, packageId, 1, input, caller.accountId()))
                            .then(repository.insertInventory(versionId, input.totalStock()))
                            .then(outbox.append(event("CommercePackageCreated", "CommercePackage", packageId,
                                    Map.of("packageId", packageId, "organizationId", scope.organizationId(),
                                            "version", 1))))
                            .then(repository.findDetail(packageId));
                    return transactions.transactional(work);
                });
    }

    public Mono<OfferDetail> reviseOffer(Caller caller, String packageId, OfferCommand command) {
        CommerceRepository.OfferInput input = validateOffer(command);
        return requireManagedOffer(caller, packageId).flatMap(current -> {
            int nextVersion = current.offer().currentVersion() + 1;
            String versionId = UUID.randomUUID().toString();
            Mono<OfferDetail> work = repository.insertVersion(
                            versionId, packageId, nextVersion, input, caller.accountId())
                    .then(repository.insertInventory(versionId, input.totalStock()))
                    .then(repository.setCurrentVersion(packageId, current.offer().currentVersion(), nextVersion)
                            .switchIfEmpty(Mono.error(new MarketplaceException(409, "套餐版本已变化，请刷新后重试"))))
                    .then(outbox.append(event("CommercePackageRevised", "CommercePackage", packageId,
                            Map.of("packageId", packageId, "version", nextVersion))))
                    .then(repository.findDetail(packageId));
            return transactions.transactional(work);
        });
    }

    public Mono<OfferDetail> publishOffer(Caller caller, String packageId) {
        return requireManagedOffer(caller, packageId).flatMap(detail -> {
            Instant now = Instant.now();
            if (detail.version().fixedRedeemDeadline() != null
                    && !detail.version().fixedRedeemDeadline().isAfter(now)
                    && detail.version().validDaysAfterPurchase() == null) {
                return Mono.error(new MarketplaceException(409, "核销截止时间已过，不能上架"));
            }
            return transactions.transactional(repository.publish(packageId)
                    .then(outbox.append(event("CommercePackagePublished", "CommercePackage", packageId,
                            Map.of("packageId", packageId, "version", detail.version().version()))))
                    .then(repository.findDetail(packageId)));
        });
    }

    public Mono<OfferDetail> offSaleOffer(Caller caller, String packageId) {
        return requireManagedOffer(caller, packageId)
                .flatMap(detail -> transactions.transactional(repository.offSale(packageId)
                        .then(outbox.append(event("CommercePackageOffSale", "CommercePackage", packageId,
                                Map.of("packageId", packageId))))
                        .then(repository.findDetail(packageId))));
    }

    public Mono<OfferDetail> publicOffer(String packageId) {
        return repository.findDetail(packageId)
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "套餐不存在")))
                .filter(detail -> "published".equals(detail.offer().status()))
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "套餐不存在或已下架")));
    }

    public Flux<OfferDetail> listManagedOffers(
            Caller caller, String organizationId, String storeId) {
        return authorization.requireScope(caller, organizationId, storeId, "staff")
                .flatMapMany(scope -> repository.listOffers(scope.organizationId(), scope.storeId()));
    }

    public Mono<Order> createOrder(Caller caller, CreateOrderCommand command) {
        return publicOffer(command.packageId()).flatMap(detail -> {
            Instant now = Instant.now();
            Instant deadline = redeemDeadline(detail, now);
            if (!deadline.isAfter(now)) {
                return Mono.error(new MarketplaceException(409, "套餐已过有效期"));
            }
            String orderId = UUID.randomUUID().toString();
            String recommender = blankToNull(command.recommenderAccountId());
            long platform = basisPoints(detail.version().priceCents(), detail.version().platformFeeBps());
            long recommenderAmount = recommender == null ? 0
                    : basisPoints(detail.version().priceCents(), detail.version().recommenderShareBps());
            long merchant = detail.version().priceCents() - platform - recommenderAmount;
            int recommenderBps = recommender == null ? 0 : detail.version().recommenderShareBps();
            int merchantBps = 10_000 - detail.version().platformFeeBps() - recommenderBps;
            CommerceRepository.NewOrder newOrder = new CommerceRepository.NewOrder(
                    orderId, caller.accountId(), detail.offer().organizationId(), detail.offer().storeId(),
                    detail.offer().taskId(), detail.offer().id(), detail.version().id(),
                    detail.version().version(), detail.version().title(), recommender,
                    detail.version().priceCents(), recommenderBps, detail.version().platformFeeBps(),
                    merchantBps, recommenderAmount, platform, merchant, detail.version().policyVersion(),
                    codes.hash(codes.codeForOrder(orderId)), deadline, "commerce-payment:" + orderId);
            Mono<Order> create = repository.reserveInventory(detail.version().id())
                    .switchIfEmpty(Mono.error(new MarketplaceException(409, "套餐已售罄")))
                    .then(repository.insertOrder(newOrder))
                    .flatMap(order -> outbox.append(orderEvent("ConsumerOrderCreated", order)).thenReturn(order));
            return transactions.transactional(create).flatMap(this::attemptPayment);
        });
    }

    public Mono<Order> findConsumerOrder(Caller caller, String orderId) {
        return repository.findOrder(orderId)
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")))
                .filter(order -> caller.accountId().equals(order.consumerAccountId()))
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "订单不存在")));
    }

    public Flux<Order> listConsumerOrders(Caller caller, int limit) {
        return repository.listConsumerOrders(caller.accountId(), limit);
    }

    public Mono<Order> requestRefund(Caller caller, String orderId, String reason) {
        return findConsumerOrder(caller, orderId).flatMap(order -> {
            if ("refund_pending".equals(order.status())) return attemptRefund(order, reason);
            if (!"paid".equals(order.status())) {
                return Mono.error(new MarketplaceException(409, "当前订单状态不可退款"));
            }
            String operationId = "commerce-refund:" + order.id();
            Mono<Order> request = repository.requestRefund(order.id(), operationId)
                    .switchIfEmpty(Mono.error(new MarketplaceException(409, "订单状态已变化")))
                    .flatMap(updated -> outbox.append(orderEvent("ConsumerOrderRefundRequested", updated))
                            .thenReturn(updated));
            return transactions.transactional(request).flatMap(updated -> attemptRefund(updated, reason));
        });
    }

    public Mono<Order> redeem(Caller caller, String code) {
        String hash = codes.hash(code);
        return repository.findOrderByCodeHash(hash)
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "核销码无效")))
                .flatMap(order -> authorization.requireScope(
                                caller, order.organizationId(), order.storeId(), "staff")
                        .thenReturn(order))
                .flatMap(order -> {
                    if ("redeemed".equals(order.status())) {
                        return Mono.error(new MarketplaceException(409, "该核销码已使用"));
                    }
                    if ("redeeming".equals(order.status())) return attemptSplit(order);
                    if (!"paid".equals(order.status())) {
                        return Mono.error(new MarketplaceException(409, "订单当前不可核销"));
                    }
                    if (!order.redeemDeadline().isAfter(Instant.now())) {
                        return Mono.error(new MarketplaceException(409, "核销码已过期，订单将自动退款"));
                    }
                    Mono<Order> mark = repository.markRedeeming(order.id(), "commerce-split:" + order.id())
                            .switchIfEmpty(Mono.error(new MarketplaceException(409, "订单状态已变化")))
                            .flatMap(updated -> outbox.append(orderEvent("ConsumerOrderRedemptionStarted", updated))
                                    .thenReturn(updated));
                    return transactions.transactional(mark).flatMap(this::attemptSplit);
                });
    }

    public Flux<Order> listMerchantOrders(
            Caller caller, String organizationId, String storeId, int limit) {
        return authorization.requireScope(caller, organizationId, storeId, "staff")
                .flatMapMany(scope -> repository.listMerchantOrders(
                        scope.organizationId(), scope.storeId(), limit));
    }

    public Flux<Order> listAdminOrders(String status, int limit) {
        return repository.listAdminOrders(status, limit);
    }

    public Mono<Review> review(Caller caller, String orderId, ReviewCommand command) {
        if (command.rating() < 1 || command.rating() > 5) {
            return Mono.error(new IllegalArgumentException("评分必须在 1 到 5 之间"));
        }
        return findConsumerOrder(caller, orderId).flatMap(order -> {
            if (!"redeemed".equals(order.status())) {
                return Mono.error(new MarketplaceException(409, "仅已核销订单可评价"));
            }
        Mono<Review> work = repository.insertReview(
                            order.id(), caller.accountId(), command.rating(), blankToNull(command.comment()))
                    .flatMap(review -> outbox.append(event("ConsumerOrderReviewed", "ConsumerOrder", order.id(),
                                    Map.of("orderId", order.id(), "consumerAccountId", caller.accountId(),
                                            "rating", review.rating())))
                            .thenReturn(review))
                    .switchIfEmpty(repository.findReview(order.id()));
            return transactions.transactional(work);
        });
    }

    Mono<Order> attemptPayment(Order order) {
        if (!"pending_payment".equals(order.status())) return Mono.just(order);
        return finance.pay(order)
                .flatMap(providerRef -> transactions.transactional(repository.markPaid(order.id(), providerRef)
                        .flatMap(updated -> outbox.append(orderEvent("ConsumerOrderPaid", updated))
                                .thenReturn(updated))
                        .switchIfEmpty(repository.findOrder(order.id()))))
                .onErrorResume(error -> repository.recordError(order.id(), "pending_payment", error.getMessage())
                        .then(repository.findOrder(order.id())));
    }

    Mono<Order> attemptRefund(Order order, String reason) {
        if (!"refund_pending".equals(order.status())) return Mono.just(order);
        return finance.refund(order, reason == null ? "consumer_request" : reason)
                .then(transactions.transactional(repository.markRefunded(order.id())
                        .flatMap(updated -> repository.replenishInventory(updated.packageVersionId())
                                .then(outbox.append(orderEvent("ConsumerOrderRefunded", updated)))
                                .thenReturn(updated))
                        .switchIfEmpty(repository.findOrder(order.id()))))
                .onErrorResume(error -> repository.recordError(order.id(), "refund_pending", error.getMessage())
                        .then(repository.findOrder(order.id())));
    }

    Mono<Order> attemptSplit(Order order) {
        if (!"redeeming".equals(order.status())) return Mono.just(order);
        return finance.split(order)
                .then(transactions.transactional(repository.markRedeemed(order.id())
                        .flatMap(updated -> outbox.append(orderEvent("ConsumerOrderRedeemed", updated))
                                .thenReturn(updated))
                        .switchIfEmpty(repository.findOrder(order.id()))))
                .onErrorResume(error -> repository.recordError(order.id(), "redeeming", error.getMessage())
                        .then(repository.findOrder(order.id())));
    }

    Flux<Order> claimExpired(int limit) { return repository.claimExpired(limit); }
    Flux<Order> pendingDispatch(int limit) { return repository.pendingDispatch(limit); }

    public String redeemCode(Order order) {
        return switch (order.status()) {
            case "paid", "redeeming" -> codes.codeForOrder(order.id());
            default -> null;
        };
    }

    private Mono<OfferDetail> requireManagedOffer(Caller caller, String packageId) {
        return repository.findDetail(packageId)
                .switchIfEmpty(Mono.error(new MarketplaceException(404, "套餐不存在")))
                .flatMap(detail -> authorization.requireScope(
                                caller, detail.offer().organizationId(), detail.offer().storeId(), "manager")
                        .thenReturn(detail));
    }

    private static CommerceRepository.OfferInput validateOffer(OfferCommand command) {
        if (command == null || blank(command.organizationId()) || blank(command.title())
                || command.priceCents() <= 0 || command.totalStock() < 0) {
            throw new IllegalArgumentException("组织、套餐名称、价格和库存不能为空");
        }
        int recommender = command.recommenderShareBps();
        int platform = command.platformFeeBps();
        if (recommender < 0 || platform < 0 || recommender + platform > 10_000) {
            throw new IllegalArgumentException("分账比例不合法");
        }
        if (command.fixedRedeemDeadline() == null && command.validDaysAfterPurchase() == null) {
            throw new IllegalArgumentException("固定截止日和购买后有效天数至少填写一项");
        }
        if (command.validDaysAfterPurchase() != null && command.validDaysAfterPurchase() <= 0) {
            throw new IllegalArgumentException("购买后有效天数必须大于 0");
        }
        return new CommerceRepository.OfferInput(
                command.title().trim(), blankToNull(command.description()), command.priceCents(),
                command.totalStock(), command.fixedRedeemDeadline(), command.validDaysAfterPurchase(),
                recommender, platform, 10_000 - recommender - platform,
                blank(command.policyVersion()) ? "commerce-v1" : command.policyVersion().trim());
    }

    private static Instant redeemDeadline(OfferDetail detail, Instant purchasedAt) {
        Instant fixed = detail.version().fixedRedeemDeadline();
        Instant rolling = detail.version().validDaysAfterPurchase() == null ? null
                : purchasedAt.plus(detail.version().validDaysAfterPurchase(), ChronoUnit.DAYS);
        if (fixed == null) return rolling;
        if (rolling == null) return fixed;
        return fixed.isBefore(rolling) ? fixed : rolling;
    }

    private static long basisPoints(long amount, int bps) {
        return Math.addExact(Math.multiplyExact(amount / 10_000, bps),
                Math.multiplyExact(amount % 10_000, bps) / 10_000);
    }

    private static EventEnvelope orderEvent(String type, Order order) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("orderId", order.id());
        payload.put("consumerAccountId", order.consumerAccountId());
        payload.put("organizationId", order.organizationId());
        if (order.storeId() != null) payload.put("storeId", order.storeId());
        if (order.recommenderAccountId() != null) payload.put("recommenderAccountId", order.recommenderAccountId());
        payload.put("packageId", order.packageId());
        payload.put("packageVersion", order.packageVersion());
        payload.put("priceCents", order.priceCents());
        payload.put("status", order.status());
        return event(type, "ConsumerOrder", order.id(), payload);
    }

    private static EventEnvelope event(
            String type, String aggregateType, String aggregateId, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID().toString(), type, aggregateType, aggregateId,
                1, Instant.now(), aggregateId, payload);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String blankToNull(String value) { return blank(value) ? null : value.trim(); }

    public record OfferCommand(
            String organizationId, String storeId, String taskId, String title, String description,
            long priceCents, int totalStock, Instant fixedRedeemDeadline,
            Integer validDaysAfterPurchase, int recommenderShareBps,
            int platformFeeBps, String policyVersion) {}
    public record CreateOrderCommand(String packageId, String recommenderAccountId) {}
    public record ReviewCommand(int rating, String comment) {}
}
