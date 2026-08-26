package com.grassland.intelligence.ai.run;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 价目表版本头与逐模型单价（V52）。
 *
 * <p>版本语义与 {@code content_safety_lexicon_version} 同构：draft 可改、active 唯一、retired 保留。
 * <b>retired 必须保留</b>——存量 {@code ai_run.price_table_version} 冻结了 label，结算要按它查回当时的单价。
 */
@Repository
public class PriceTableRepository {

    private final DatabaseClient db;

    public PriceTableRepository(DatabaseClient db) {
        this.db = db;
    }

    public Flux<VersionRow> findAllVersions() {
        return db.sql("""
                        SELECT id::text, label, status, note, created_by, created_at, activated_at
                        FROM price_table_version
                        ORDER BY created_at DESC
                        """)
                .map(PriceTableRepository::mapVersion)
                .all();
    }

    public Mono<VersionRow> findVersionById(UUID id) {
        return db.sql("""
                        SELECT id::text, label, status, note, created_by, created_at, activated_at
                        FROM price_table_version WHERE id = CAST(:id AS uuid)
                        """)
                .bind("id", id.toString())
                .map(PriceTableRepository::mapVersion)
                .one();
    }

    public Mono<VersionRow> findByLabel(String label) {
        return db.sql("""
                        SELECT id::text, label, status, note, created_by, created_at, activated_at
                        FROM price_table_version WHERE label = :label
                        """)
                .bind("label", label)
                .map(PriceTableRepository::mapVersion)
                .one();
    }

    public Mono<VersionRow> findActive() {
        return db.sql("""
                        SELECT id::text, label, status, note, created_by, created_at, activated_at
                        FROM price_table_version WHERE status = 'active'
                        """)
                .map(PriceTableRepository::mapVersion)
                .one();
    }

    public Flux<ModelPriceRow> findModelsByVersion(UUID versionId) {
        return db.sql("""
                        SELECT model_id, capability, provider, cents_per_1k_input_tokens,
                               cents_per_1k_output_tokens, cents_per_image, cents_per_second
                        FROM price_table_model
                        WHERE version_id = CAST(:versionId AS uuid)
                        ORDER BY model_id
                        """)
                .bind("versionId", versionId.toString())
                .map(PriceTableRepository::mapModel)
                .all();
    }

    public Mono<UUID> createVersion(String label, String status, String note, String adminId) {
        return db.sql("""
                        INSERT INTO price_table_version(label, status, note, created_by, activated_at)
                        VALUES (:label, :status, :note, :adminId,
                                CASE WHEN :status = 'active' THEN now() ELSE NULL END)
                        RETURNING id::text
                        """)
                .bind("label", label)
                .bind("status", status)
                .bind("note", nullable(note, String.class))
                .bind("adminId", nullable(adminId, String.class))
                .map((r, m) -> r.get("id", String.class))
                .one()
                .map(UUID::fromString);
    }

    /**
     * 整份覆盖某版本的明细。仅 draft 版本可改——active/retired 的单价必须冻结，
     * 否则存量 Run 按 label 查回来的价会变，等于篡改历史账。调用方须先校验 status。
     */
    public Mono<Void> replaceModels(UUID versionId, List<ModelPriceRow> models) {
        Mono<Void> cleared = db.sql("DELETE FROM price_table_model WHERE version_id = CAST(:versionId AS uuid)")
                .bind("versionId", versionId.toString())
                .then();
        if (models.isEmpty()) {
            return cleared;
        }
        return cleared.thenMany(Flux.fromIterable(models).concatMap(model -> db.sql("""
                        INSERT INTO price_table_model(
                            version_id, model_id, capability, provider, cents_per_1k_input_tokens,
                            cents_per_1k_output_tokens, cents_per_image, cents_per_second
                        ) VALUES (
                            CAST(:versionId AS uuid), :modelId, :capability, :provider,
                            :input, :output, :image, :second
                        )
                        """)
                .bind("versionId", versionId.toString())
                .bind("modelId", model.modelId())
                .bind("capability", model.capability())
                .bind("provider", model.provider())
                .bind("input", model.centsPer1kInputTokens())
                .bind("output", model.centsPer1kOutputTokens())
                .bind("image", model.centsPerImage())
                .bind("second", model.centsPerSecond())
                .then()))
                .then();
    }

    /**
     * 激活一张 draft：先把现有 active 转 retired，再置本版本 active。
     *
     * <p>必须在事务内调用——两步之间若失败会出现零个或两个 active，前者让所有平台档调用 503。
     * 单 active 的部分唯一索引是最后一道闸。
     */
    public Mono<Boolean> activate(UUID versionId) {
        return db.sql("UPDATE price_table_version SET status = 'retired' WHERE status = 'active'")
                .fetch().rowsUpdated()
                .then(db.sql("""
                        UPDATE price_table_version
                        SET status = 'active', activated_at = now()
                        WHERE id = CAST(:id AS uuid) AND status = 'draft'
                        RETURNING id::text
                        """)
                        .bind("id", versionId.toString())
                        .map((r, m) -> r.get("id", String.class))
                        .one()
                        .hasElement());
    }

    /** 删一张 draft（明细随 ON DELETE CASCADE 走）。active/retired 不可删。 */
    public Mono<Boolean> deleteDraft(UUID versionId) {
        return db.sql("""
                        DELETE FROM price_table_version
                        WHERE id = CAST(:id AS uuid) AND status = 'draft'
                        RETURNING id::text
                        """)
                .bind("id", versionId.toString())
                .map((r, m) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    public Mono<Long> countVersions() {
        return db.sql("SELECT COUNT(*) AS n FROM price_table_version")
                .map((r, m) -> r.get("n", Long.class))
                .one();
    }

    private static VersionRow mapVersion(Row row, RowMetadata meta) {
        return new VersionRow(
                UUID.fromString(row.get("id", String.class)),
                row.get("label", String.class),
                row.get("status", String.class),
                row.get("note", String.class),
                row.get("created_by", String.class),
                row.get("created_at", Instant.class),
                row.get("activated_at", Instant.class));
    }

    private static ModelPriceRow mapModel(Row row, RowMetadata meta) {
        return new ModelPriceRow(
                row.get("model_id", String.class),
                row.get("capability", String.class),
                row.get("provider", String.class),
                row.get("cents_per_1k_input_tokens", Integer.class),
                row.get("cents_per_1k_output_tokens", Integer.class),
                row.get("cents_per_image", Integer.class),
                row.get("cents_per_second", Integer.class));
    }

    public record VersionRow(UUID id, String label, String status, String note, String createdBy,
            Instant createdAt, Instant activatedAt) {
    }

    public record ModelPriceRow(String modelId, String capability, String provider,
            int centsPer1kInputTokens, int centsPer1kOutputTokens, int centsPerImage, int centsPerSecond) {
    }
}
