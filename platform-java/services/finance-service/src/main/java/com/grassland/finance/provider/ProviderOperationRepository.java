package com.grassland.finance.provider;

import com.grassland.finance.security.FinanceException;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Durable provider-operation registry. It is the seam between domain facts and a future PSP. */
@Component
public class ProviderOperationRepository {

    private final DatabaseClient db;

    public ProviderOperationRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<ProviderOperation> register(String provider, String operationId, String operationType,
                                            String reference, long amountCents, String currency,
                                            String providerRef) {
        String normalizedCurrency = currency == null ? "CNY" : currency;
        return registerIfAbsent(provider, operationId, operationType, reference, amountCents,
                        normalizedCurrency, providerRef)
                .switchIfEmpty(findByOperationId(operationId).flatMap(existing ->
                        matches(existing, provider, operationType, reference, amountCents,
                                normalizedCurrency, providerRef)
                                ? Mono.just(existing)
                                : Mono.error(new FinanceException(409, "通道幂等参数冲突"))));
    }

    /** Returns empty when another transaction has already registered the operation id. */
    public Mono<ProviderOperation> registerIfAbsent(
            String provider, String operationId, String operationType, String reference,
            long amountCents, String currency, String providerRef) {
        return db.sql("""
                INSERT INTO finance_provider_operation(
                    provider, operation_id, operation_type, reference, amount_cents,
                    currency, provider_ref, status, completed_at)
                VALUES (:provider, :operationId, :operationType, :reference, :amount,
                        :currency, :providerRef, 'succeeded', now())
                ON CONFLICT (operation_id) DO NOTHING
                RETURNING id::text, provider, operation_id, operation_type, reference,
                          amount_cents, currency, provider_ref, status, created_at, updated_at, completed_at
                """)
                .bind("provider", provider).bind("operationId", operationId)
                .bind("operationType", operationType).bind("reference", reference)
                .bind("amount", amountCents).bind("currency", currency == null ? "CNY" : currency)
                .bind("providerRef", providerRef)
                .map(ProviderOperationRepository::map).one();
    }

    public Mono<ProviderOperation> findByOperationId(String operationId) {
        return db.sql("""
                SELECT id::text, provider, operation_id, operation_type, reference,
                       amount_cents, currency, provider_ref, status, created_at, updated_at, completed_at
                  FROM finance_provider_operation WHERE operation_id = :operationId
                """)
                .bind("operationId", operationId).map(ProviderOperationRepository::map).one();
    }

    public Mono<ProviderOperation> findByProviderRef(String provider, String providerRef) {
        return db.sql("""
                SELECT id::text, provider, operation_id, operation_type, reference,
                       amount_cents, currency, provider_ref, status, created_at, updated_at, completed_at
                  FROM finance_provider_operation
                 WHERE provider = :provider AND provider_ref = :providerRef
                """)
                .bind("provider", provider).bind("providerRef", providerRef)
                .map(ProviderOperationRepository::map).one();
    }

    public Flux<ProviderOperation> list(int limit) {
        return db.sql("""
                SELECT id::text, provider, operation_id, operation_type, reference,
                       amount_cents, currency, provider_ref, status, created_at, updated_at, completed_at
                  FROM finance_provider_operation
                 ORDER BY created_at DESC LIMIT :limit
                """)
                .bind("limit", Math.max(1, Math.min(limit, 200)))
                .map(ProviderOperationRepository::map).all();
    }

    public Mono<ProviderOperation> markStatus(String operationId, String status) {
        return db.sql("""
                UPDATE finance_provider_operation
                   SET status = :status,
                       updated_at = now(),
                       completed_at = CASE WHEN :status IN ('succeeded', 'failed', 'reconciled')
                                           THEN COALESCE(completed_at, now()) ELSE NULL END
                 WHERE operation_id = :operationId
                RETURNING id::text, provider, operation_id, operation_type, reference,
                          amount_cents, currency, provider_ref, status, created_at, updated_at, completed_at
                """)
                .bind("status", status).bind("operationId", operationId)
                .map(ProviderOperationRepository::map).one();
    }

    private static ProviderOperation map(Readable row) {
        return new ProviderOperation(
                UUID.fromString(row.get("id", String.class)),
                row.get("provider", String.class), row.get("operation_id", String.class),
                row.get("operation_type", String.class), row.get("reference", String.class),
                row.get("amount_cents", Long.class), row.get("currency", String.class),
                row.get("provider_ref", String.class), row.get("status", String.class),
                instant(row.get("created_at", OffsetDateTime.class)),
                instant(row.get("updated_at", OffsetDateTime.class)),
                instant(row.get("completed_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public static boolean matches(
            ProviderOperation existing, String provider, String operationType, String reference,
            long amountCents, String currency, String providerRef) {
        return existing.provider().equals(provider)
                && existing.operationType().equals(operationType)
                && existing.reference().equals(reference)
                && existing.amountCents() == amountCents
                && existing.currency().equals(currency)
                && existing.providerRef().equals(providerRef);
    }
}
