package com.grassland.intelligence.creationcanvas;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 画布 AI 计划存取（任务书 #100 C100-16 / V76 / API-13/14/15）。
 *
 * <p>(account_id, operation_id) 唯一 = 幂等占位：先落 preparing 行再调模型；同键并发插入
 * 0 行（服务层复读裁决 202/既有终态）。状态转移用 CAS（preparing→ready/clarify/failed、
 * 僵尸 preparing>120s 读取时标失败、ready→applied 带唯一 apply 结果）。
 */
@Component
public class CanvasAgentPlanRepository {

    private final DatabaseClient db;

    public CanvasAgentPlanRepository(DatabaseClient db) {
        this.db = db;
    }

    public record AgentPlanRow(UUID id, String accountId, UUID operationId, String requestHash,
            UUID draftId, UUID storyboardId, int baseDraftVersion, long baseEditVersion,
            long baseCanvasRevision, String status, String selectedNodeIdsJson, String instruction,
            String summary, String clarification, String actionJson, UUID runId, String errorCode,
            String applyResultJson, OffsetDateTime createdAt, OffsetDateTime updatedAt,
            OffsetDateTime expiresAt) {
    }

    private static final String COLS = "id::text, account_id, operation_id::text, request_hash, "
            + "draft_id::text, storyboard_id::text, base_draft_version, base_edit_version, "
            + "base_canvas_revision, status, selected_node_ids::text, instruction, summary, "
            + "clarification, action::text, run_id::text, error_code, apply_result::text, "
            + "created_at, updated_at, expires_at";

    /** 幂等占位插入（preparing，创建+30min 过期占位）；冲突返回 0 行。 */
    public Mono<Long> insertPlaceholder(AgentPlanRow row) {
        return db.sql("INSERT INTO creation_canvas_agent_plan(id, account_id, operation_id, "
                        + "request_hash, draft_id, storyboard_id, base_draft_version, base_edit_version, "
                        + "base_canvas_revision, status, selected_node_ids, instruction, expires_at) "
                        + "VALUES (CAST(:id AS uuid), :account, CAST(:operation AS uuid), :hash, "
                        + "CAST(:draft AS uuid), CAST(:sb AS uuid), :baseDraftVersion, :baseEditVersion, "
                        + ":baseCanvasRevision, 'preparing', CAST(:selected AS jsonb), :instruction, "
                        + ":expiresAt) ON CONFLICT (account_id, operation_id) DO NOTHING")
                .bind("id", row.id().toString())
                .bind("account", row.accountId())
                .bind("operation", row.operationId().toString())
                .bind("hash", row.requestHash())
                .bind("draft", row.draftId().toString())
                .bind("sb", row.storyboardId().toString())
                .bind("baseDraftVersion", row.baseDraftVersion())
                .bind("baseEditVersion", row.baseEditVersion())
                .bind("baseCanvasRevision", row.baseCanvasRevision())
                .bind("selected", row.selectedNodeIdsJson())
                .bind("instruction", row.instruction())
                .bind("expiresAt", row.expiresAt().atZoneSameInstant(java.time.ZoneOffset.UTC)
                        .toOffsetDateTime())
                .fetch().rowsUpdated();
    }

    public Mono<AgentPlanRow> findById(UUID id) {
        return db.sql("SELECT " + COLS + " FROM creation_canvas_agent_plan WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString())
                .map(CanvasAgentPlanRepository::map)
                .one();
    }

    public Mono<AgentPlanRow> findByAccountAndOperation(String accountId, UUID operationId) {
        return db.sql("SELECT " + COLS + " FROM creation_canvas_agent_plan "
                        + "WHERE account_id=:account AND operation_id=CAST(:operation AS uuid)")
                .bind("account", accountId)
                .bind("operation", operationId.toString())
                .map(CanvasAgentPlanRepository::map)
                .one();
    }

    /** 模型完成（ready/clarify）或失败（failed）：preparing CAS；完成时刻起 30 分钟有效。 */
    public Mono<Long> complete(UUID id, String status, String summary, String clarification,
            String actionJson, UUID runId, String errorCode, java.time.Instant expiresAt) {
        org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec spec = db.sql(
                        "UPDATE creation_canvas_agent_plan SET status=:status, summary=:summary, "
                        + "clarification=:clarification, action=CAST(:action AS jsonb), "
                        + "run_id=CAST(:run AS uuid), error_code=:errorCode, expires_at=:expiresAt, "
                        + "updated_at=now() WHERE id=CAST(:id AS uuid) AND status='preparing'")
                .bind("id", id.toString())
                .bind("status", status)
                .bind("summary", summary == null ? "" : summary)
                .bind("expiresAt", expiresAt.atZone(java.time.ZoneOffset.UTC).toOffsetDateTime());
        spec = clarification == null
                ? spec.bindNull("clarification", String.class)
                : spec.bind("clarification", clarification);
        spec = actionJson == null
                ? spec.bindNull("action", String.class)
                : spec.bind("action", actionJson);
        spec = runId == null
                ? spec.bindNull("run", java.util.UUID.class)
                : spec.bind("run", runId.toString());
        spec = errorCode == null
                ? spec.bindNull("errorCode", String.class)
                : spec.bind("errorCode", errorCode);
        return spec.fetch().rowsUpdated();
    }

    /** 僵尸收口：preparing 超过 120s 读取时标失败（保留 runId 追踪）。 */
    public Mono<Long> failZombie(UUID id, java.time.Instant createdBefore) {
        return db.sql("UPDATE creation_canvas_agent_plan SET status='failed', "
                        + "error_code='CANVAS_AGENT_TIMEOUT', updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND status='preparing' AND created_at < :before")
                .bind("id", id.toString())
                .bind("before", createdBefore.atZone(java.time.ZoneOffset.UTC).toOffsetDateTime())
                .fetch().rowsUpdated();
    }

    /** 唯一应用结果：ready→applied CAS + apply_result 持久化（重放返回该结果）。 */
    public Mono<Long> apply(UUID id, String applyResultJson) {
        return db.sql("UPDATE creation_canvas_agent_plan SET status='applied', "
                        + "apply_result=CAST(:result AS jsonb), updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid) AND status='ready'")
                .bind("id", id.toString())
                .bind("result", applyResultJson)
                .fetch().rowsUpdated();
    }

    private static AgentPlanRow map(Row row, RowMetadata meta) {
        String runId = row.get("run_id", String.class);
        return new AgentPlanRow(UUID.fromString(row.get("id", String.class)),
                row.get("account_id", String.class), UUID.fromString(row.get("operation_id", String.class)),
                row.get("request_hash", String.class).trim(),
                UUID.fromString(row.get("draft_id", String.class)),
                UUID.fromString(row.get("storyboard_id", String.class)),
                row.get("base_draft_version", Integer.class),
                row.get("base_edit_version", Long.class),
                row.get("base_canvas_revision", Long.class),
                row.get("status", String.class), row.get("selected_node_ids", String.class),
                row.get("instruction", String.class), row.get("summary", String.class),
                row.get("clarification", String.class), row.get("action", String.class),
                runId == null ? null : UUID.fromString(runId), row.get("error_code", String.class),
                row.get("apply_result", String.class), row.get("created_at", OffsetDateTime.class),
                row.get("updated_at", OffsetDateTime.class), row.get("expires_at", OffsetDateTime.class));
    }
}
