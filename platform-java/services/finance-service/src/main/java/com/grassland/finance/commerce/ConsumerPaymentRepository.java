package com.grassland.finance.commerce;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Finance-owned persistence for consumer payment, refund and split facts. */
@Component
public class ConsumerPaymentRepository {

    private final DatabaseClient db;

    public ConsumerPaymentRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Payment> findPayment(String orderRef) {
        return db.sql("""
                SELECT id::text, order_ref, consumer_account_id::text, organization_id::text,
                       amount_cents, refunded_amount_cents, currency, channel, provider_ref, operation_id, status,
                       created_at, refunded_at, updated_at
                  FROM consumer_payment WHERE order_ref = :orderRef
                """)
                .bind("orderRef", orderRef)
                .map(ConsumerPaymentRepository::mapPayment).one();
    }

    public Mono<Payment> insertPayment(
            String orderRef, String consumerAccountId, String organizationId, long amountCents,
            String channel, String providerRef, String operationId) {
        return db.sql("""
                INSERT INTO consumer_payment(
                    id, order_ref, consumer_account_id, organization_id, amount_cents,
                    channel, provider_ref, operation_id, status)
                VALUES (CAST(:id AS uuid), :orderRef, CAST(:consumer AS uuid), CAST(:org AS uuid),
                        :amount, :channel, :providerRef, :operationId, 'succeeded')
                ON CONFLICT (order_ref) DO NOTHING
                RETURNING id::text, order_ref, consumer_account_id::text, organization_id::text,
                          amount_cents, refunded_amount_cents, currency, channel, provider_ref, operation_id, status,
                          created_at, refunded_at, updated_at
                """)
                .bind("id", UUID.randomUUID().toString())
                .bind("orderRef", orderRef)
                .bind("consumer", consumerAccountId)
                .bind("org", organizationId)
                .bind("amount", amountCents)
                .bind("channel", channel)
                .bind("providerRef", providerRef)
                .bind("operationId", operationId)
                .map(ConsumerPaymentRepository::mapPayment).one();
    }

    public Mono<Refund> findRefund(String orderRef) {
        return db.sql("""
                SELECT id::text, order_ref, amount_cents, reason, operation_id, provider_ref,
                       status, created_at
                  FROM consumer_payment_refund WHERE order_ref = :orderRef
                """)
                .bind("orderRef", orderRef)
                .map(ConsumerPaymentRepository::mapRefund).one();
    }

    public Mono<Refund> findRefundByOperation(String operationId) {
        return db.sql("""
                SELECT id::text, order_ref, amount_cents, reason, operation_id, provider_ref,
                       status, created_at
                  FROM consumer_payment_refund WHERE operation_id = :operationId
                """)
                .bind("operationId", operationId)
                .map(ConsumerPaymentRepository::mapRefund).one();
    }

    public Mono<Refund> insertRefund(
            String orderRef, long amountCents, String reason, String operationId, String providerRef) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO consumer_payment_refund(
                    id, order_ref, amount_cents, reason, operation_id, provider_ref, status)
                VALUES (CAST(:id AS uuid), :orderRef, :amount, :reason, :operationId, :providerRef, 'succeeded')
                ON CONFLICT (operation_id) DO NOTHING
                RETURNING id::text, order_ref, amount_cents, reason, operation_id, provider_ref,
                          status, created_at
                """)
                .bind("id", UUID.randomUUID().toString())
                .bind("orderRef", orderRef)
                .bind("amount", amountCents)
                .bind("operationId", operationId)
                .bind("providerRef", providerRef);
        spec = bindNullable(spec, "reason", reason);
        return spec.map(ConsumerPaymentRepository::mapRefund).one();
    }

    public Mono<Payment> reserveRefund(String orderRef, long amountCents, String operationId) {
        return db.sql("""
                UPDATE consumer_payment
                   SET refunded_amount_cents = refunded_amount_cents + :amount,
                       status = CASE WHEN refunded_amount_cents + :amount = amount_cents
                                     THEN 'refunded' ELSE 'partially_refunded' END,
                       refunded_at = CASE WHEN refunded_amount_cents + :amount = amount_cents
                                          THEN now() ELSE refunded_at END,
                       updated_at = now()
                 WHERE order_ref = :orderRef
                   AND status IN ('succeeded', 'partially_refunded')
                   AND refunded_amount_cents + :amount <= amount_cents
                   AND NOT EXISTS (SELECT 1 FROM consumer_payment_refund r
                                   WHERE r.operation_id = :operationId)
                RETURNING id::text, order_ref, consumer_account_id::text, organization_id::text,
                          amount_cents, refunded_amount_cents, currency, channel, provider_ref,
                          operation_id, status, created_at, refunded_at, updated_at
                """)
                .bind("orderRef", orderRef).bind("amount", amountCents).bind("operationId", operationId)
                .map(ConsumerPaymentRepository::mapPayment).one();
    }

    public Mono<Split> findSplit(String orderRef) {
        return db.sql("""
                SELECT id::text, order_ref, recommender_account_id::text, recommender_amount_cents,
                       merchant_amount_cents, platform_fee_cents, operation_id, status,
                       created_at, completed_at
                  FROM consumer_payment_split WHERE order_ref = :orderRef
                """)
                .bind("orderRef", orderRef)
                .map(ConsumerPaymentRepository::mapSplit).one();
    }

    public Mono<Split> insertSplit(
            String orderRef, String recommenderAccountId, long recommenderAmount,
            long merchantAmount, long platformFee, String operationId) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO consumer_payment_split(
                    id, order_ref, recommender_account_id, recommender_amount_cents,
                    merchant_amount_cents, platform_fee_cents, operation_id, status)
                VALUES (CAST(:id AS uuid), :orderRef, CAST(:recommender AS uuid), :recommenderAmount,
                        :merchantAmount, :platformFee, :operationId, 'processing')
                ON CONFLICT (order_ref) DO NOTHING
                RETURNING id::text, order_ref, recommender_account_id::text, recommender_amount_cents,
                          merchant_amount_cents, platform_fee_cents, operation_id, status,
                          created_at, completed_at
                """)
                .bind("id", UUID.randomUUID().toString())
                .bind("orderRef", orderRef)
                .bind("recommenderAmount", recommenderAmount)
                .bind("merchantAmount", merchantAmount)
                .bind("platformFee", platformFee)
                .bind("operationId", operationId);
        spec = recommenderAccountId == null
                ? spec.bindNull("recommender", String.class)
                : spec.bind("recommender", recommenderAccountId);
        return spec.map(ConsumerPaymentRepository::mapSplit).one();
    }

    public Mono<Split> completeSplit(String orderRef) {
        return db.sql("""
                UPDATE consumer_payment_split
                   SET status = 'completed', completed_at = now()
                 WHERE order_ref = :orderRef AND status = 'processing'
                RETURNING id::text, order_ref, recommender_account_id::text, recommender_amount_cents,
                          merchant_amount_cents, platform_fee_cents, operation_id, status,
                          created_at, completed_at
                """)
                .bind("orderRef", orderRef)
                .map(ConsumerPaymentRepository::mapSplit).one();
    }

    public Mono<Void> insertSplitAllocations(String orderRef, String operationId, List<SplitAllocation> allocations) {
        return reactor.core.publisher.Flux.fromIterable(allocations == null ? List.<SplitAllocation>of() : allocations)
                .flatMap(allocation -> db.sql("""
                        INSERT INTO consumer_payment_split_allocation(
                            id, order_ref, split_operation_id, recommender_account_id, amount_cents)
                        VALUES (CAST(:id AS uuid), :orderRef, :operationId, CAST(:account AS uuid), :amount)
                        ON CONFLICT (order_ref, split_operation_id, recommender_account_id) DO NOTHING
                """)
                        .bind("id", UUID.randomUUID().toString()).bind("orderRef", orderRef)
                        .bind("operationId", operationId).bind("account", allocation.recommenderAccountId())
                        .bind("amount", allocation.amountCents()).then()).then();
    }

    public reactor.core.publisher.Flux<SplitAllocation> findSplitAllocations(String orderRef) {
        return db.sql("""
                SELECT recommender_account_id::text, amount_cents
                  FROM consumer_payment_split_allocation
                 WHERE order_ref = :orderRef ORDER BY created_at, id
                """).bind("orderRef", orderRef).map(row -> new SplitAllocation(
                row.get("recommender_account_id", String.class), row.get("amount_cents", Long.class))).all();
    }

    private static Payment mapPayment(Readable row) {
        return new Payment(
                row.get("id", String.class), row.get("order_ref", String.class),
                row.get("consumer_account_id", String.class), row.get("organization_id", String.class),
                row.get("amount_cents", Long.class), row.get("refunded_amount_cents", Long.class),
                row.get("currency", String.class),
                row.get("channel", String.class), row.get("provider_ref", String.class),
                row.get("operation_id", String.class), row.get("status", String.class),
                instant(row.get("created_at", OffsetDateTime.class)),
                instant(row.get("refunded_at", OffsetDateTime.class)),
                instant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Refund mapRefund(Readable row) {
        return new Refund(
                row.get("id", String.class), row.get("order_ref", String.class),
                row.get("amount_cents", Long.class), row.get("reason", String.class),
                row.get("operation_id", String.class), row.get("provider_ref", String.class),
                row.get("status", String.class), instant(row.get("created_at", OffsetDateTime.class)));
    }

    private static Split mapSplit(Readable row) {
        return new Split(
                row.get("id", String.class), row.get("order_ref", String.class),
                row.get("recommender_account_id", String.class),
                row.get("recommender_amount_cents", Long.class),
                row.get("merchant_amount_cents", Long.class), row.get("platform_fee_cents", Long.class),
                row.get("operation_id", String.class), row.get("status", String.class),
                instant(row.get("created_at", OffsetDateTime.class)),
                instant(row.get("completed_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    public record Payment(
            String id, String orderRef, String consumerAccountId, String organizationId,
            long amountCents, long refundedAmountCents, String currency, String channel, String providerRef,
            String operationId, String status, Instant createdAt, Instant refundedAt, Instant updatedAt) {}

    public record Refund(
            String id, String orderRef, long amountCents, String reason, String operationId,
            String providerRef, String status, Instant createdAt) {}

    public record Split(
            String id, String orderRef, String recommenderAccountId, long recommenderAmountCents,
            long merchantAmountCents, long platformFeeCents, String operationId, String status,
            Instant createdAt, Instant completedAt) {}

    public record SplitAllocation(String recommenderAccountId, long amountCents) {}
}
