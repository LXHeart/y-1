package com.grassland.marketplace.taskcatalog;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Persistent idempotency ledger and durable dispatch intent for application acceptance. */
@Component
public class AcceptanceCommandRepository {

    private static final String SELECT_COLS =
            "id::text, actor_account_id::text, idempotency_key, task_id::text, application_id::text,"
                    + " workflow_id, merchant_account_id::text, organization_id::text, amount_cents, status,"
                    + " failure_reason, workflow_started_at, completed_at, created_at, updated_at";

    private final DatabaseClient db;

    public AcceptanceCommandRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<AcceptanceCommand> create(AcceptanceCommand command) {
        var spec = db.sql("""
                INSERT INTO task_acceptance_command(
                    id, actor_account_id, idempotency_key, task_id, application_id, workflow_id,
                    merchant_account_id, organization_id, amount_cents, status, completed_at)
                VALUES (CAST(:id AS uuid), CAST(:actor AS uuid), :key, CAST(:taskId AS uuid),
                    CAST(:applicationId AS uuid), :workflowId, CAST(:merchant AS uuid), CAST(:organization AS uuid),
                    :amount, :status, CASE WHEN :status = 'accepted' THEN now() ELSE NULL END)
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", command.id()).bind("actor", command.actorAccountId())
                .bind("key", command.idempotencyKey()).bind("taskId", command.taskId())
                .bind("applicationId", command.applicationId()).bind("merchant", command.merchantAccountId())
                .bind("organization", command.organizationId()).bind("amount", command.amountCents())
                .bind("status", command.status());
        spec = command.workflowId() == null
                ? spec.bindNull("workflowId", String.class)
                : spec.bind("workflowId", command.workflowId());
        return spec.map(AcceptanceCommandRepository::map).one();
    }

    public Mono<AcceptanceCommand> findByActorAndKey(String actorAccountId, String key) {
        return db.sql("SELECT " + SELECT_COLS + " FROM task_acceptance_command"
                        + " WHERE actor_account_id = CAST(:actor AS uuid) AND idempotency_key = :key")
                .bind("actor", actorAccountId).bind("key", key)
                .map(AcceptanceCommandRepository::map).one();
    }

    public Mono<AcceptanceCommand> findById(String id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM task_acceptance_command WHERE id = CAST(:id AS uuid)")
                .bind("id", id).map(AcceptanceCommandRepository::map).one();
    }

    public Flux<AcceptanceCommand> findDispatchable(int limit) {
        return db.sql("SELECT " + SELECT_COLS + " FROM task_acceptance_command"
                        + " WHERE status = 'pending_dispatch' ORDER BY created_at, id LIMIT :limit")
                .bind("limit", Math.max(1, limit)).map(AcceptanceCommandRepository::map).all();
    }

    public Mono<Boolean> markStarted(String id) {
        return transition(id, "pending_dispatch", "started", null, false);
    }

    public Mono<Boolean> markAccepted(String id) {
        return complete(id, "accepted", null);
    }

    public Mono<Boolean> markCompensated(String id, String reason) {
        return complete(id, "compensated", reason);
    }

    public Mono<Boolean> markAborted(String id, String reason) {
        return complete(id, "aborted", reason);
    }

    private Mono<Boolean> complete(String id, String status, String reason) {
        var spec = db.sql("""
                UPDATE task_acceptance_command
                SET status = :status, failure_reason = :reason, completed_at = COALESCE(completed_at, now()),
                    updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status IN ('pending_dispatch', 'started')
                """).bind("id", id).bind("status", status);
        spec = reason == null ? spec.bindNull("reason", String.class) : spec.bind("reason", reason);
        return spec.fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    private Mono<Boolean> transition(String id, String from, String to, String reason, boolean complete) {
        var spec = db.sql("""
                UPDATE task_acceptance_command
                SET status = :to, failure_reason = :reason,
                    workflow_started_at = CASE WHEN :to = 'started' THEN COALESCE(workflow_started_at, now())
                                               ELSE workflow_started_at END,
                    completed_at = CASE WHEN :complete THEN COALESCE(completed_at, now()) ELSE completed_at END,
                    updated_at = now()
                WHERE id = CAST(:id AS uuid) AND status = :from
                """).bind("id", id).bind("from", from).bind("to", to).bind("complete", complete);
        spec = reason == null ? spec.bindNull("reason", String.class) : spec.bind("reason", reason);
        return spec.fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    private static AcceptanceCommand map(Readable row) {
        return new AcceptanceCommand(
                row.get("id", String.class), row.get("actor_account_id", String.class),
                row.get("idempotency_key", String.class), row.get("task_id", String.class),
                row.get("application_id", String.class), row.get("workflow_id", String.class),
                row.get("merchant_account_id", String.class), row.get("organization_id", String.class),
                valueOrZero(row.get("amount_cents", Long.class)), row.get("status", String.class),
                row.get("failure_reason", String.class), instant(row, "workflow_started_at"),
                instant(row, "completed_at"), instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private static Instant instant(Readable row, String column) {
        OffsetDateTime value = row.get(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
