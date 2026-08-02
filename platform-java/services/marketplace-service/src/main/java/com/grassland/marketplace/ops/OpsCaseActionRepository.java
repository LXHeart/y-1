package com.grassland.marketplace.ops;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * ops_case_action 数据访问（GL-P1-OPS-001 Stage 2）。
 *
 * <p>幂等靠 {@code uq_ops_case_action_operation}：{@link #claim} 用
 * {@code ON CONFLICT (operation_id) DO NOTHING} 抢占，empty 即「这个 operationId 已经用过」，
 * 调用方回读既有行并原样返回，<b>不再调下游</b>。这是 credits bridge 那套（GL-P0-BILL-002）的
 * 同一口径：唯一索引是唯一真相，不靠先查后插。
 */
@Component
public class OpsCaseActionRepository {

    private static final String SELECT_COLS =
            "id::text, case_id::text, operation_id, action, status, requested_by::text,"
                    + " outcome, error, created_at, completed_at";

    private final DatabaseClient db;

    public OpsCaseActionRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 抢占一个 operationId 并落 {@code pending} 行。<b>冲突返回 empty</b>（该 operationId 已用过）。
     *
     * <p>刻意先落 pending 再调下游：反过来会在「下游已执行、进程崩溃」时留下无记录的资金动作，
     * 那种缺口事后无法从本服务侧分辨。
     */
    public Mono<OpsCaseAction> claim(String caseId, String operationId, String action, String requestedBy) {
        return db.sql("""
                INSERT INTO ops_case_action(id, case_id, operation_id, action, requested_by)
                VALUES (CAST(:id AS uuid), CAST(:caseId AS uuid), :operationId, :action, CAST(:by AS uuid))
                ON CONFLICT (operation_id) DO NOTHING
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", UUID.randomUUID().toString())
                .bind("caseId", caseId)
                .bind("operationId", operationId)
                .bind("action", action)
                .bind("by", requestedBy)
                .map(OpsCaseActionRepository::map).one();
    }

    public Mono<OpsCaseAction> findByOperationId(String operationId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM ops_case_action WHERE operation_id = :operationId")
                .bind("operationId", operationId)
                .map(OpsCaseActionRepository::map).one();
    }

    public Flux<OpsCaseAction> listByCase(String caseId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM ops_case_action WHERE case_id = CAST(:caseId AS uuid) ORDER BY created_at, id")
                .bind("caseId", caseId)
                .map(OpsCaseActionRepository::map).all();
    }

    /** 回填结果。只吃 {@code pending}（重复完成 → empty，防止把 failed 改写成 succeeded）。 */
    public Mono<OpsCaseAction> complete(String operationId, boolean succeeded, String outcome, String error) {
        var spec = db.sql("""
                UPDATE ops_case_action SET status = :status, outcome = :outcome, error = :error,
                        completed_at = now()
                WHERE operation_id = :operationId AND status = 'pending'
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("operationId", operationId)
                .bind("status", succeeded ? "succeeded" : "failed");
        spec = bindNullable(spec, "outcome", outcome);
        spec = bindNullable(spec, "error", error);
        return spec.map(OpsCaseActionRepository::map).one();
    }

    private static OpsCaseAction map(Readable row) {
        return new OpsCaseAction(
                row.get("id", String.class),
                row.get("case_id", String.class),
                row.get("operation_id", String.class),
                row.get("action", String.class),
                row.get("status", String.class),
                row.get("requested_by", String.class),
                row.get("outcome", String.class),
                row.get("error", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("completed_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static GenericExecuteSpec bindNullable(GenericExecuteSpec spec, String name, String value) {
        return (value == null || value.isBlank()) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
}
