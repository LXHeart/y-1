package com.grassland.intelligence.creationcontext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import static com.grassland.intelligence.config.R2dbcBindings.nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Persistence for append-only creation context snapshots. */
@Component
public class CreationContextSnapshotRepository {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final String COLS = "id::text, account_id, organization_id, task_id, application_id, "
            + "task_version, platform_id, content_form_id, task_snapshot::text, platform_rules_snapshot::text, "
            + "material_snapshot::text, ai_config_snapshot::text, created_at";

    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public CreationContextSnapshotRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<CreationContextSnapshot> create(CreationContextSnapshot snapshot) {
        var spec = db.sql("""
                INSERT INTO creation_context_snapshot(
                    account_id, organization_id, task_id, application_id, task_version,
                    platform_id, content_form_id, task_snapshot, platform_rules_snapshot,
                    material_snapshot, ai_config_snapshot)
                VALUES (:accountId, :organizationId, :taskId, :applicationId, :taskVersion,
                    :platformId, :contentFormId, CAST(:taskSnapshot AS jsonb),
                    CAST(:platformRules AS jsonb), CAST(:materials AS jsonb), CAST(:aiConfig AS jsonb))
                ON CONFLICT (account_id, application_id, task_version, platform_id, content_form_id)
                DO NOTHING
                """)
                .bind("accountId", snapshot.accountId())
                .bind("organizationId", nullable(snapshot.organizationId(), String.class))
                .bind("taskId", snapshot.taskId())
                .bind("applicationId", snapshot.applicationId())
                .bind("taskVersion", snapshot.taskVersion())
                .bind("platformId", snapshot.platformId())
                .bind("contentFormId", snapshot.contentFormId())
                .bind("taskSnapshot", json(snapshot.taskSnapshot()))
                .bind("platformRules", json(snapshot.platformRulesSnapshot()))
                .bind("materials", json(snapshot.materialSnapshot()))
                .bind("aiConfig", json(snapshot.aiConfigSnapshot()));
        return spec.fetch().rowsUpdated()
                .then(findByKey(snapshot.accountId(), snapshot.applicationId(), snapshot.taskVersion(),
                        snapshot.platformId(), snapshot.contentFormId()));
    }

    public Mono<CreationContextSnapshot> findById(UUID id) {
        return db.sql("SELECT " + COLS + " FROM creation_context_snapshot WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString()).map(this::map).one();
    }

    public Mono<Boolean> belongsTo(UUID id, String accountId) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM creation_context_snapshot WHERE id=CAST(:id AS uuid) AND account_id=:accountId)")
                .bind("id", id.toString()).bind("accountId", accountId)
                .map(row -> Boolean.TRUE.equals(row.get("exists", Boolean.class))).one().defaultIfEmpty(false);
    }

    public Mono<CreationContextSnapshot> findByKey(String accountId, String applicationId, int taskVersion,
                                                   String platformId, String contentFormId) {
        return db.sql("SELECT " + COLS + " FROM creation_context_snapshot"
                        + " WHERE account_id=:accountId AND application_id=:applicationId"
                        + " AND task_version=:taskVersion AND platform_id=:platformId AND content_form_id=:contentFormId")
                .bind("accountId", accountId).bind("applicationId", applicationId)
                .bind("taskVersion", taskVersion).bind("platformId", platformId).bind("contentFormId", contentFormId)
                .map(this::map).one();
    }

    private CreationContextSnapshot map(Row row, RowMetadata metadata) {
        return new CreationContextSnapshot(
                UUID.fromString(row.get("id", String.class)),
                row.get("account_id", String.class), row.get("organization_id", String.class),
                row.get("task_id", String.class), row.get("application_id", String.class),
                row.get("task_version", Integer.class), row.get("platform_id", String.class),
                row.get("content_form_id", String.class), parse(row.get("task_snapshot", String.class)),
                parse(row.get("platform_rules_snapshot", String.class)),
                parse(row.get("material_snapshot", String.class)),
                parse(row.get("ai_config_snapshot", String.class)),
                instant(row.get("created_at", OffsetDateTime.class)));
    }

    private Map<String, Object> parse(String value) {
        try {
            return value == null ? Map.of() : mapper.readValue(value, MAP_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("创作上下文快照损坏", error);
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new IllegalArgumentException("创作上下文快照格式不合法", error);
        }
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
