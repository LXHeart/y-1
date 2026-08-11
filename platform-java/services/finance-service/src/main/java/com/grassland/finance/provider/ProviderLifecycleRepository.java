package com.grassland.finance.provider;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Persistence for provider webhook inboxes and reconciliation statements. */
@Component
public class ProviderLifecycleRepository {

    private final DatabaseClient db;

    public ProviderLifecycleRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<ProviderWebhookEvent> insertWebhook(ProviderWebhookCommand command, String status, String error) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO finance_provider_webhook_event(
                    event_id, provider, event_type, provider_ref, operation_id, payload, status,
                    error_message, processed_at)
                VALUES (:eventId, :provider, :eventType, :providerRef, :operationId,
                        CAST(:payload AS jsonb), :status, :error,
                        CASE WHEN :status <> 'received' THEN now() ELSE NULL END)
                ON CONFLICT (provider, event_id) DO NOTHING
                RETURNING event_id, provider, event_type, provider_ref, operation_id,
                          status, error_message, received_at, processed_at
                """)
                .bind("eventId", command.eventId()).bind("provider", command.provider())
                .bind("eventType", command.eventType()).bind("payload", command.payloadJson())
                .bind("status", status);
        spec = bindNullable(spec, "error", error);
        spec = bindNullable(spec, "providerRef", command.providerRef());
        spec = bindNullable(spec, "operationId", command.operationId());
        return spec.map(ProviderLifecycleRepository::mapWebhook).one()
                .switchIfEmpty(findWebhook(command.provider(), command.eventId()));
    }

    public Mono<ProviderWebhookEvent> findWebhook(String provider, String eventId) {
        return db.sql("""
                SELECT event_id, provider, event_type, provider_ref, operation_id,
                       status, error_message, received_at, processed_at
                  FROM finance_provider_webhook_event
                 WHERE provider = :provider AND event_id = :eventId
                """).bind("provider", provider).bind("eventId", eventId)
                .map(ProviderLifecycleRepository::mapWebhook).one();
    }

    public Flux<ProviderWebhookEvent> listWebhooks(int limit) {
        return db.sql("""
                SELECT event_id, provider, event_type, provider_ref, operation_id,
                       status, error_message, received_at, processed_at
                  FROM finance_provider_webhook_event
                 ORDER BY received_at DESC LIMIT :limit
                """).bind("limit", Math.max(1, Math.min(limit, 200)))
                .map(ProviderLifecycleRepository::mapWebhook).all();
    }

    public Mono<ProviderReconciliation> insertReconciliation(ProviderReconciliationCommand command,
                                                              String status) {
        GenericExecuteSpec spec = db.sql("""
                INSERT INTO finance_provider_reconciliation(
                    provider, statement_ref, provider_ref, operation_id, operation_type,
                    amount_cents, currency, status, payload)
                VALUES (:provider, :statementRef, :providerRef, :operationId, :operationType,
                        :amount, :currency, :status, CAST(:payload AS jsonb))
                ON CONFLICT (provider, statement_ref, provider_ref) DO NOTHING
                RETURNING id::text, provider, statement_ref, provider_ref, operation_id,
                          operation_type, amount_cents, currency, status, created_at
                """)
                .bind("provider", command.provider()).bind("statementRef", command.statementRef())
                .bind("providerRef", command.providerRef()).bind("amount", command.amountCents())
                .bind("currency", command.currency() == null ? "CNY" : command.currency())
                .bind("status", status).bind("payload", command.payloadJson());
        spec = bindNullable(spec, "operationId", command.operationId());
        spec = bindNullable(spec, "operationType", command.operationType());
        return spec.map(ProviderLifecycleRepository::mapReconciliation).one()
                .switchIfEmpty(findReconciliation(command.provider(), command.statementRef(), command.providerRef()));
    }

    public Mono<ProviderReconciliation> findReconciliation(String provider, String statementRef, String providerRef) {
        return db.sql("""
                SELECT id::text, provider, statement_ref, provider_ref, operation_id,
                       operation_type, amount_cents, currency, status, created_at
                  FROM finance_provider_reconciliation
                 WHERE provider = :provider AND statement_ref = :statementRef AND provider_ref = :providerRef
                """).bind("provider", provider).bind("statementRef", statementRef).bind("providerRef", providerRef)
                .map(ProviderLifecycleRepository::mapReconciliation).one();
    }

    public Flux<ProviderReconciliation> listReconciliations(int limit) {
        return db.sql("""
                SELECT id::text, provider, statement_ref, provider_ref, operation_id,
                       operation_type, amount_cents, currency, status, created_at
                  FROM finance_provider_reconciliation ORDER BY created_at DESC LIMIT :limit
                """).bind("limit", Math.max(1, Math.min(limit, 200)))
                .map(ProviderLifecycleRepository::mapReconciliation).all();
    }

    private static ProviderWebhookEvent mapWebhook(Readable row) {
        return new ProviderWebhookEvent(row.get("event_id", String.class), row.get("provider", String.class),
                row.get("event_type", String.class), row.get("provider_ref", String.class),
                row.get("operation_id", String.class), row.get("status", String.class),
                row.get("error_message", String.class), instant(row.get("received_at", OffsetDateTime.class)),
                instant(row.get("processed_at", OffsetDateTime.class)));
    }

    private static ProviderReconciliation mapReconciliation(Readable row) {
        return new ProviderReconciliation(UUID.fromString(row.get("id", String.class)),
                row.get("provider", String.class), row.get("statement_ref", String.class),
                row.get("provider_ref", String.class), row.get("operation_id", String.class),
                row.get("operation_type", String.class), row.get("amount_cents", Long.class),
                row.get("currency", String.class), row.get("status", String.class),
                instant(row.get("created_at", OffsetDateTime.class)));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
