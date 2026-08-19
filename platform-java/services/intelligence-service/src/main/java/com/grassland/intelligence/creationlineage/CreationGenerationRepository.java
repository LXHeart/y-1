package com.grassland.intelligence.creationlineage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.creationlineage.CreationGeneration.Kind;
import com.grassland.intelligence.creationlineage.CreationGeneration.Mode;
import com.grassland.intelligence.creationlineage.CreationGeneration.Resolution;
import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

/** R2DBC repository for append-only creation lineage. */
@Repository
public class CreationGenerationRepository {

    private static final String SELECT_COLUMNS = """
            id::text, owner_account_id, organization_id, kind, mode,
            context_snapshot_id::text, ai_run_id::text, resolution, provider, model,
            platform_model_version, upstream_run_id, prompt_text,
            input_summary::text, to_json(input_media_ids)::text AS input_media_ids,
            result::text, to_json(result_media_ids)::text AS result_media_ids, created_at
            """;
    private static final String SELECT_G_COLUMNS = """
            g.id::text, g.owner_account_id, g.organization_id, g.kind, g.mode,
            g.context_snapshot_id::text, g.ai_run_id::text, g.resolution, g.provider, g.model,
            g.platform_model_version, g.upstream_run_id, g.prompt_text,
            g.input_summary::text, to_json(g.input_media_ids)::text AS input_media_ids,
            g.result::text, to_json(g.result_media_ids)::text AS result_media_ids, g.created_at
            """;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public CreationGenerationRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<CreationGeneration> insert(CreationGeneration value) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                INSERT INTO creation_generation (
                    owner_account_id, organization_id, kind, mode, context_snapshot_id, ai_run_id,
                    resolution, provider, model, platform_model_version, upstream_run_id, prompt_text,
                    input_summary, input_media_ids, result, result_media_ids)
                VALUES (
                    :ownerAccountId, :organizationId, :kind, :mode, CAST(:contextSnapshotId AS uuid),
                    CAST(:aiRunId AS uuid), :resolution, :provider, :model, :platformModelVersion,
                    :upstreamRunId, :promptText, CAST(:inputSummary AS jsonb),
                    CAST(:inputMediaIds AS uuid[]), CAST(:result AS jsonb), CAST(:resultMediaIds AS uuid[]))
                RETURNING %s
                """.formatted(SELECT_COLUMNS))
                .bind("ownerAccountId", value.ownerAccountId())
                .bind("organizationId", nullable(value.organizationId(), String.class))
                .bind("kind", value.kind().db())
                .bind("mode", value.mode().db())
                .bind("contextSnapshotId", nullable(text(value.contextSnapshotId()), String.class))
                .bind("aiRunId", nullable(text(value.aiRunId()), String.class))
                .bind("resolution", value.resolution().db())
                .bind("provider", value.provider())
                .bind("model", nullable(value.model(), String.class))
                .bind("platformModelVersion", nullable(value.platformModelVersion(), Integer.class))
                .bind("upstreamRunId", nullable(value.upstreamRunId(), String.class))
                .bind("promptText", value.promptText())
                .bind("inputSummary", json(value.inputSummary()))
                .bind("inputMediaIds", uuidArray(value.inputMediaIds()))
                .bind("result", json(value.result()))
                .bind("resultMediaIds", uuidArray(value.resultMediaIds()));
        return spec.map((row, metadata) -> map(row)).one();
    }

    public Flux<CreationGeneration> listForOwner(
            String ownerAccountId, Kind kind, int limit, UUID before) {
        String kindFilter = kind == null ? "" : " AND g.kind=:kind";
        String cursorJoin = before == null ? "" : """
                JOIN creation_generation cursor
                  ON cursor.id=CAST(:before AS uuid) AND cursor.owner_account_id=:ownerAccountId
                """;
        String cursorFilter = before == null ? "" : " AND (g.created_at, g.id) < (cursor.created_at, cursor.id)";
        DatabaseClient.GenericExecuteSpec spec = db.sql("SELECT " + SELECT_G_COLUMNS
                        + " FROM creation_generation g " + cursorJoin
                        + " WHERE g.owner_account_id=:ownerAccountId" + kindFilter + cursorFilter
                        + " ORDER BY g.created_at DESC, g.id DESC LIMIT :limit")
                .bind("ownerAccountId", ownerAccountId)
                .bind("limit", limit);
        if (kind != null) spec = spec.bind("kind", kind.db());
        if (before != null) spec = spec.bind("before", before.toString());
        return spec.map((row, metadata) -> map(row)).all();
    }

    public Mono<CreationGeneration> findByIdAndOwner(UUID id, String ownerAccountId) {
        return db.sql("SELECT " + SELECT_COLUMNS + " FROM creation_generation"
                        + " WHERE id=CAST(:id AS uuid) AND owner_account_id=:ownerAccountId")
                .bind("id", id.toString())
                .bind("ownerAccountId", ownerAccountId)
                .map((row, metadata) -> map(row)).one();
    }

    private CreationGeneration map(Readable row) {
        return new CreationGeneration(
                UUID.fromString(row.get("id", String.class)),
                row.get("owner_account_id", String.class),
                row.get("organization_id", String.class),
                Kind.fromDb(row.get("kind", String.class)),
                Mode.fromDb(row.get("mode", String.class)),
                uuid(row.get("context_snapshot_id", String.class)),
                uuid(row.get("ai_run_id", String.class)),
                Resolution.fromDb(row.get("resolution", String.class)),
                row.get("provider", String.class),
                row.get("model", String.class),
                row.get("platform_model_version", Integer.class),
                row.get("upstream_run_id", String.class),
                row.get("prompt_text", String.class),
                mapJson(row.get("input_summary", String.class)),
                uuidList(row.get("input_media_ids", String.class)),
                mapJson(row.get("result", String.class)),
                uuidList(row.get("result_media_ids", String.class)),
                row.get("created_at", OffsetDateTime.class).toInstant());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new IllegalArgumentException("creation generation JSON cannot be serialized", error);
        }
    }

    private Map<String, Object> mapJson(String value) {
        try {
            return value == null ? Map.of() : mapper.readValue(value, MAP_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("creation generation JSON cannot be read", error);
        }
    }

    private List<UUID> uuidList(String value) {
        try {
            if (value == null) return List.of();
            List<String> strings = mapper.readValue(value, STRING_LIST_TYPE);
            List<UUID> result = new ArrayList<>(strings.size());
            strings.forEach(item -> result.add(UUID.fromString(item)));
            return List.copyOf(result);
        } catch (Exception error) {
            throw new IllegalStateException("creation generation media ids cannot be read", error);
        }
    }

    private static String[] uuidArray(List<UUID> values) {
        if (values == null || values.isEmpty()) return new String[0];
        return values.stream().map(UUID::toString).toArray(String[]::new);
    }

    private static String text(UUID value) { return value == null ? null : value.toString(); }
    private static UUID uuid(String value) { return value == null ? null : UUID.fromString(value); }
}
