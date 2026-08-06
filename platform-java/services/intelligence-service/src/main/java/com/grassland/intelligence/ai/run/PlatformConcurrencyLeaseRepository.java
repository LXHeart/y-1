package com.grassland.intelligence.ai.run;

import java.time.Duration;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** PostgreSQL-backed slot leases shared by every intelligence-service replica. */
@Component
public final class PlatformConcurrencyLeaseRepository {

    private final DatabaseClient db;

    public PlatformConcurrencyLeaseRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<SlotLease> acquire(UUID configId, Duration ttl) {
        UUID token = UUID.randomUUID();
        return db.sql("""
                WITH candidate AS (
                    SELECT config_id, slot_no
                    FROM platform_model_concurrency_slot
                    WHERE config_id = CAST(:configId AS uuid)
                      AND (lease_until IS NULL OR lease_until <= clock_timestamp())
                    ORDER BY slot_no
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE platform_model_concurrency_slot slot
                SET lease_token = CAST(:token AS uuid),
                    lease_until = clock_timestamp() + (:ttlMillis * INTERVAL '1 millisecond'),
                    acquired_at = clock_timestamp()
                FROM candidate
                WHERE slot.config_id = candidate.config_id
                  AND slot.slot_no = candidate.slot_no
                RETURNING slot.slot_no
                """)
                .bind("configId", configId.toString())
                .bind("token", token.toString())
                .bind("ttlMillis", ttl.toMillis())
                .map((row, meta) -> new SlotLease(
                        configId, row.get("slot_no", Integer.class), token))
                .one();
    }

    public Mono<Boolean> release(SlotLease lease) {
        return db.sql("""
                UPDATE platform_model_concurrency_slot
                SET lease_token = NULL, lease_until = NULL, acquired_at = NULL
                WHERE config_id = CAST(:configId AS uuid)
                  AND slot_no = :slotNo
                  AND lease_token = CAST(:token AS uuid)
                RETURNING slot_no
                """)
                .bind("configId", lease.configId().toString())
                .bind("slotNo", lease.slotNo())
                .bind("token", lease.token().toString())
                .map((row, meta) -> row.get("slot_no", Integer.class))
                .one()
                .hasElement();
    }

    public record SlotLease(UUID configId, int slotNo, UUID token) {
    }
}
