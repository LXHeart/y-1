package com.grassland.intelligence.creationstudio.text;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 C101-04（V78）：文本建议仓储。幂等键 UNIQUE(owner_account_id, request_id)；
 * apply_request_id 部分唯一（WHERE NOT NULL）承接同键应用重放。
 */
@Component
public class TextProposalRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SELECT = """
            SELECT id::text, owner_account_id, draft_id::text AS draft_id, request_id, request_hash,
                   action, status, base_draft_version, base_content_hash,
                   source_document_id::text AS source_document_id, selected_blocks_json::text AS selected_blocks_json,
                   input_snapshot_json::text AS input_snapshot_json, prompt_ciphertext, prompt_hash,
                   result_json::text AS result_json, run_id::text AS run_id, error_code, created_at, expires_at,
                   applied_draft_version, apply_request_id, apply_result_json::text AS apply_result_json
            FROM creation_text_proposal
            """;

    private final DatabaseClient db;

    public TextProposalRepository(DatabaseClient db) {
        this.db = db;
    }

    public record ProposalRow(UUID id, String ownerAccountId, UUID draftId, String requestId, String requestHash,
            String action, String status, int baseDraftVersion, String baseContentHash, UUID sourceDocumentId,
            String selectedBlocksJson, String inputSnapshotJson, String promptCiphertext, String promptHash,
            Map<String, Object> result, UUID runId, String errorCode, OffsetDateTime createdAt,
            OffsetDateTime expiresAt, Integer appliedDraftVersion, String applyRequestId,
            Map<String, Object> applyResult) {
    }

    /** 首次占位；同键已存在返回 0（重放/冲突路径由 service 比较 request_hash）。 */
    public Mono<Boolean> insertPlaceholder(ProposalRow row) {
        var spec = db.sql("""
                INSERT INTO creation_text_proposal (
                    id, owner_account_id, draft_id, request_id, request_hash, action, status,
                    base_draft_version, base_content_hash, source_document_id, selected_blocks_json,
                    input_snapshot_json, prompt_ciphertext, prompt_hash, expires_at)
                VALUES (
                    CAST(:id AS uuid), :ownerAccountId, CAST(:draftId AS uuid), :requestId, :requestHash,
                    :action, 'preparing', :baseDraftVersion, :baseContentHash,
                    CAST(:sourceDocumentId AS uuid), CAST(:selectedBlocksJson AS jsonb),
                    CAST(:inputSnapshotJson AS jsonb), :promptCiphertext, :promptHash, :expiresAt)
                ON CONFLICT (owner_account_id, request_id) DO NOTHING
                """).bind("id", row.id().toString()).bind("ownerAccountId", row.ownerAccountId())
                .bind("draftId", row.draftId().toString()).bind("requestId", row.requestId())
                .bind("requestHash", row.requestHash()).bind("action", row.action())
                .bind("baseDraftVersion", row.baseDraftVersion()).bind("baseContentHash", row.baseContentHash())
                .bind("selectedBlocksJson", orEmpty(row.selectedBlocksJson()))
                .bind("inputSnapshotJson", orEmptyObject(row.inputSnapshotJson()))
                .bind("expiresAt", row.expiresAt());
        spec = row.sourceDocumentId() == null ? spec.bindNull("sourceDocumentId", java.util.UUID.class)
                : spec.bind("sourceDocumentId", row.sourceDocumentId());
        spec = row.promptCiphertext() == null ? spec.bindNull("promptCiphertext", String.class)
                : spec.bind("promptCiphertext", row.promptCiphertext());
        spec = row.promptHash() == null ? spec.bindNull("promptHash", String.class)
                : spec.bind("promptHash", row.promptHash());
        return spec.fetch().rowsUpdated().map(count -> count > 0);
    }

    /** 模型调用前持久化 runId（§6.6 onPrepared 契约：失败不调用模型）。 */
    public Mono<Integer> attachRun(UUID id, UUID runId) {
        return db.sql("UPDATE creation_text_proposal SET run_id = CAST(:runId AS uuid) "
                + "WHERE id = CAST(:id AS uuid) AND status = 'preparing'")
                .bind("runId", runId.toString()).bind("id", id.toString()).fetch().rowsUpdated().map(Long::intValue);
    }

    public Mono<Integer> completeReady(UUID id, String resultJson, UUID runId, OffsetDateTime expiresAt) {
        var ready = db.sql("""
                UPDATE creation_text_proposal
                SET status = 'ready', result_json = CAST(:resultJson AS jsonb),
                    run_id = COALESCE(CAST(:runId AS uuid), run_id), error_code = NULL, expires_at = :expiresAt
                WHERE id = CAST(:id AS uuid) AND status = 'preparing'
                """).bind("id", id.toString()).bind("resultJson", resultJson).bind("expiresAt", expiresAt);
        var spec = runId == null ? ready.bindNull("runId", java.util.UUID.class) : ready.bind("runId", runId);
        return spec.fetch().rowsUpdated().map(Long::intValue);
    }

    public Mono<Integer> completeFailed(UUID id, String errorCode, UUID runId) {
        var failed = db.sql("""
                UPDATE creation_text_proposal
                SET status = CASE WHEN run_id IS NOT NULL THEN 'unknown' ELSE 'failed' END,
                    error_code = :errorCode,
                    run_id = COALESCE(CAST(:runId AS uuid), run_id)
                WHERE id = CAST(:id AS uuid) AND status = 'preparing'
                """).bind("id", id.toString()).bind("errorCode", errorCode);
        var spec = runId == null ? failed.bindNull("runId", java.util.UUID.class) : failed.bind("runId", runId);
        return spec.fetch().rowsUpdated().map(Long::intValue);
    }

    /** 模型输出违约（确定性失败：已收到响应并拒绝）——run 保留但状态为 failed。 */
    public Mono<Integer> completeInvalid(UUID id, UUID runId) {
        var invalid = db.sql("""
                UPDATE creation_text_proposal
                SET status = 'failed', error_code = 'STUDIO_INVALID_PLAN',
                    run_id = COALESCE(CAST(:runId AS uuid), run_id)
                WHERE id = CAST(:id AS uuid) AND status = 'preparing'
                """).bind("id", id.toString());
        var spec = runId == null ? invalid.bindNull("runId", java.util.UUID.class) : invalid.bind("runId", runId);
        return spec.fetch().rowsUpdated().map(Long::intValue);
    }

    /** 应用完成（与草稿写同事务调用）。 */
    public Mono<Integer> markApplied(UUID id, int appliedDraftVersion, String applyRequestId, String applyResultJson) {
        return db.sql("""
                UPDATE creation_text_proposal
                SET status = 'applied', applied_draft_version = :appliedDraftVersion,
                    apply_request_id = :applyRequestId, apply_result_json = CAST(:applyResultJson AS jsonb)
                WHERE id = CAST(:id AS uuid) AND status = 'ready'
                """).bind("id", id.toString()).bind("appliedDraftVersion", appliedDraftVersion)
                .bind("applyRequestId", applyRequestId)
                .bind("applyResultJson", applyResultJson == null ? "{}" : applyResultJson).fetch().rowsUpdated()
                .map(Long::intValue);
    }

    public Mono<ProposalRow> findById(UUID id) {
        return db.sql(SELECT + " WHERE id = CAST(:id AS uuid)").bind("id", id.toString())
                .map(TextProposalRepository::map).one();
    }

    public Mono<ProposalRow> findByOwnerAndRequestId(String ownerAccountId, String requestId) {
        return db.sql(SELECT + " WHERE owner_account_id = :owner AND request_id = :requestId")
                .bind("owner", ownerAccountId).bind("requestId", requestId)
                .map(TextProposalRepository::map).one();
    }

    public Mono<ProposalRow> lockById(UUID id) {
        return db.sql(SELECT + " WHERE id = CAST(:id AS uuid) FOR UPDATE").bind("id", id.toString())
                .map(TextProposalRepository::map).one();
    }

    public static ProposalRow map(Readable row) {
        Map<String, Object> result = readJson(row.get("result_json", String.class));
        Map<String, Object> applyResult = readJson(row.get("apply_result_json", String.class));
        return new ProposalRow(uuid(row.get("id", String.class)), row.get("owner_account_id", String.class),
                uuid(row.get("draft_id", String.class)), row.get("request_id", String.class),
                row.get("request_hash", String.class), row.get("action", String.class),
                row.get("status", String.class), row.get("base_draft_version", Integer.class),
                row.get("base_content_hash", String.class), uuid(row.get("source_document_id", String.class)),
                row.get("selected_blocks_json", String.class), row.get("input_snapshot_json", String.class),
                row.get("prompt_ciphertext", String.class), row.get("prompt_hash", String.class),
                result, uuid(row.get("run_id", String.class)), row.get("error_code", String.class),
                row.get("created_at", OffsetDateTime.class), row.get("expires_at", OffsetDateTime.class),
                row.get("applied_draft_version", Integer.class), row.get("apply_request_id", String.class),
                applyResult);
    }

    private static String bindUuid(UUID value) {
        return value == null ? null : value.toString();
    }

    private static String orEmpty(String json) {
        return json == null || json.isBlank() ? "[]" : json;
    }

    private static String orEmptyObject(String json) {
        return json == null || json.isBlank() ? "{}" : json;
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception error) {
            return Map.of();
        }
    }
}
