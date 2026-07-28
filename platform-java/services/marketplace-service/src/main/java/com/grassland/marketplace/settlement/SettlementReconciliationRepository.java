package com.grassland.marketplace.settlement;

import java.time.Duration;
import java.time.Instant;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 结算对账请求仓库（Slice 7B）。复刻 marketplace 既有 DatabaseClient 手写 SQL house style。
 *
 * <p>所有状态迁移用 guarded UPDATE（WHERE status=...），保证并发 dispatcher/重试下幂等：
 * 终态行不可再迁移；{@code markStarted} 仅 pending/started 可命中（确定性 workflow id +
 * {@code WorkflowExecutionAlreadyStarted} 作成功，使多实例/重派发都安全）。
 */
@Component
public class SettlementReconciliationRepository {

    private final DatabaseClient db;

    public SettlementReconciliationRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 消费侧在 Inbox 事务内调用：落一行 pending。重复 source_event_id → 返回 false（幂等去重）。 */
    public Mono<Boolean> enqueue(
            String sourceEventId,
            String disputeId,
            String applicationId,
            String organizationId,
            String finalDecision,
            String workflowId) {
        return db.sql("""
                        INSERT INTO settlement_reconciliation
                            (source_event_id, dispute_id, application_id, organization_id,
                             final_decision, workflow_id, status)
                        VALUES (:sourceEventId, :disputeId, :applicationId, :organizationId,
                                :finalDecision, :workflowId, 'pending')
                        ON CONFLICT (source_event_id) DO NOTHING
                        RETURNING source_event_id
                        """)
                .bind("sourceEventId", sourceEventId)
                .bind("disputeId", disputeId)
                .bind("applicationId", applicationId)
                .bind("organizationId", organizationId == null ? "" : organizationId)
                .bind("finalDecision", finalDecision)
                .bind("workflowId", workflowId)
                .map(row -> true)
                .one()
                .defaultIfEmpty(false);
    }

    public Mono<SettlementReconciliation> findBySourceEventId(String sourceEventId) {
        return db.sql(SELECT + " WHERE source_event_id = :sourceEventId")
                .bind("sourceEventId", sourceEventId)
                .map((row, meta) -> map(row))
                .one();
    }

    public Mono<SettlementReconciliation> findLatestForApplication(String applicationId) {
        return db.sql(SELECT + " WHERE application_id = :applicationId ORDER BY created_at DESC LIMIT 1")
                .bind("applicationId", applicationId)
                .map((row, meta) -> map(row))
                .one();
    }

    /** 可派发行：pending，或 started 但已超过再派发延迟（前次派发后 workflow 失败/未推进）。 */
    public Flux<SettlementReconciliation> findDispatchable(int limit) {
        return db.sql(SELECT + """
                        WHERE status = 'pending'
                           OR (status = 'started' AND next_dispatch_at <= now())
                        ORDER BY next_dispatch_at
                        LIMIT :limit
                        """)
                .bind("limit", limit)
                .map((row, meta) -> map(row))
                .all();
    }

    /** 派发成功后标记 started；幂等（pending/started 均可命中）。 */
    public Mono<Boolean> markStarted(String sourceEventId, int attempt, Duration redispatchDelay) {
        return db.sql("""
                        UPDATE settlement_reconciliation
                        SET status = 'started',
                            started_at = COALESCE(started_at, now()),
                            dispatch_attempt = :attempt,
                            next_dispatch_at = now() + CAST(:redispatchMillis AS bigint) * interval '1 millisecond',
                            updated_at = now()
                        WHERE source_event_id = :sourceEventId
                          AND status IN ('pending', 'started')
                        """)
                .bind("sourceEventId", sourceEventId)
                .bind("attempt", attempt)
                .bind("redispatchMillis", redispatchDelay.toMillis())
                .fetch().rowsUpdated().map(updated -> updated > 0);
    }

    /** 派发失败：保持非终态，延后重试（dispatcher 下轮再选）。 */
    public Mono<Boolean> markStartFailed(String sourceEventId, int attempt, Duration backoff) {
        return db.sql("""
                        UPDATE settlement_reconciliation
                        SET status = 'pending',
                            dispatch_attempt = :attempt,
                            next_dispatch_at = now() + CAST(:backoffMillis AS bigint) * interval '1 millisecond',
                            updated_at = now()
                        WHERE source_event_id = :sourceEventId
                          AND status IN ('pending', 'started')
                        """)
                .bind("sourceEventId", sourceEventId)
                .bind("attempt", attempt)
                .bind("backoffMillis", backoff.toMillis())
                .fetch().rowsUpdated().map(updated -> updated > 0);
    }

    /** workflow 成功：→reconciled。与 EngagementSettled outbox 在同一事务调用。接受 pending/started：
     *  dispatcher 可能在 WorkflowClient.start 与 markStarted 间崩溃，此时行仍 pending 但 workflow 已跑——
     *  activity 终态迁移不应因此卡死。终态行不可再迁移（幂等）。 */
    public Mono<Boolean> markReconciled(String sourceEventId) {
        return db.sql("""
                        UPDATE settlement_reconciliation
                        SET status = 'reconciled', completed_at = now(), updated_at = now()
                        WHERE source_event_id = :sourceEventId AND status IN ('pending', 'started')
                        """)
                .bind("sourceEventId", sourceEventId)
                .fetch().rowsUpdated().map(updated -> updated > 0);
    }

    /** 业务阻断：→blocked + reason。与 SettlementReconciliationBlocked outbox 在同一事务调用（守卫同 markReconciled）。 */
    public Mono<Boolean> markBlocked(String sourceEventId, String reason) {
        return db.sql("""
                        UPDATE settlement_reconciliation
                        SET status = 'blocked', reason = :reason, completed_at = now(), updated_at = now()
                        WHERE source_event_id = :sourceEventId AND status IN ('pending', 'started')
                        """)
                .bind("sourceEventId", sourceEventId)
                .bind("reason", reason)
                .fetch().rowsUpdated().map(updated -> updated > 0);
    }

    private static final String SELECT = """
            SELECT source_event_id, dispute_id, application_id, organization_id,
                   final_decision, workflow_id, status, reason, dispatch_attempt,
                   next_dispatch_at, started_at, completed_at, created_at, updated_at
            FROM settlement_reconciliation
            """;

    private SettlementReconciliation map(io.r2dbc.spi.Row row) {
        return new SettlementReconciliation(
                row.get("source_event_id", String.class),
                row.get("dispute_id", String.class),
                row.get("application_id", String.class),
                row.get("organization_id", String.class),
                row.get("final_decision", String.class),
                row.get("workflow_id", String.class),
                row.get("status", String.class),
                row.get("reason", String.class),
                intOrZero(row.get("dispatch_attempt", Integer.class)),
                row.get("next_dispatch_at", Instant.class),
                row.get("started_at", Instant.class),
                row.get("completed_at", Instant.class),
                row.get("created_at", Instant.class),
                row.get("updated_at", Instant.class));
    }

    private static int intOrZero(Integer value) {
        return value == null ? 0 : value;
    }
}
