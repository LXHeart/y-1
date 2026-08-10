package com.grassland.marketplace.taskcatalog;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Transactional capacity counter. Reserving and accepted applications both occupy a slot. */
@Component
public class TaskAcceptanceCounterRepository {

    private final DatabaseClient db;

    public TaskAcceptanceCounterRepository(DatabaseClient db) {
        this.db = db;
    }

    /** Atomically claims one slot. Empty means the configured capacity is full. */
    public Mono<Integer> claim(String taskId) {
        return db.sql("""
                INSERT INTO task_acceptance_counter(task_id, occupied_slots)
                VALUES (CAST(:taskId AS uuid), 0)
                ON CONFLICT (task_id) DO NOTHING
                """)
                .bind("taskId", taskId)
                .then()
                .then(db.sql("""
                        UPDATE task_acceptance_counter counter
                        SET occupied_slots = counter.occupied_slots + 1, updated_at = now()
                        FROM task t
                        WHERE counter.task_id = t.id
                          AND t.id = CAST(:taskId AS uuid)
                          AND (t.max_slots IS NULL OR counter.occupied_slots < t.max_slots)
                        RETURNING counter.occupied_slots
                        """)
                        .bind("taskId", taskId)
                        .map(row -> row.get("occupied_slots", Integer.class))
                        .one());
    }

    /** Releases exactly one slot; the positive guard makes activity retries idempotent with a guarded state change. */
    public Mono<Boolean> release(String taskId) {
        return db.sql("""
                UPDATE task_acceptance_counter
                SET occupied_slots = occupied_slots - 1, updated_at = now()
                WHERE task_id = CAST(:taskId AS uuid) AND occupied_slots > 0
                """)
                .bind("taskId", taskId)
                .fetch().rowsUpdated().map(updated -> updated > 0).defaultIfEmpty(false);
    }

    public Mono<Integer> occupied(String taskId) {
        return db.sql("""
                SELECT COALESCE(counter.occupied_slots, 0)::int AS occupied_slots
                FROM task t
                LEFT JOIN task_acceptance_counter counter ON counter.task_id = t.id
                WHERE t.id = CAST(:taskId AS uuid)
                """)
                .bind("taskId", taskId)
                .map(row -> row.get("occupied_slots", Integer.class))
                .one();
    }
}
