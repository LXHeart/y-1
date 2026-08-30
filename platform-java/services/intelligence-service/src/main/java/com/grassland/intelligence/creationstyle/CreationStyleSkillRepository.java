package com.grassland.intelligence.creationstyle;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 创作 style skill 仓储（任务书 #57）。{@code DatabaseClient} 裸 SQL + {@code R2dbcBindings} 惯例
 * （照 {@code HomepageHotConfigRepository}）。
 *
 * <p>排序口径：category 升序（TITLE_FORMULA/GENRE/STYLE 枚举序）内按 {@code sort_order, code}。
 */
@Component
public class CreationStyleSkillRepository {

    private static final String COLS = "id, category, code, name, description, prompt_content, "
            + "enabled, sort_order, version, updated_by, updated_at";

    private final DatabaseClient db;

    public CreationStyleSkillRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Long> count() {
        return db.sql("SELECT count(*) AS n FROM creation_style_skill")
                .map((row, meta) -> row.get("n", Long.class)).one();
    }

    /** 目录下发：enabled 行（用户端 chips，绝不含 promptContent——由 Controller 组装响应时裁剪）。 */
    public Flux<CreationStyleSkill> listEnabled(CreationStyleSkillCategory category) {
        String sql = "SELECT " + COLS + " FROM creation_style_skill WHERE enabled = true"
                + (category == null ? "" : " AND category = :category")
                + " ORDER BY category, sort_order, code";
        var spec = db.sql(sql);
        if (category != null) {
            spec = spec.bind("category", category.key());
        }
        return spec.map(CreationStyleSkillRepository::map).all();
    }

    /** 治理台全量（含停用含 promptContent）。 */
    public Flux<CreationStyleSkill> listAll() {
        return db.sql("SELECT " + COLS + " FROM creation_style_skill ORDER BY category, sort_order, code")
                .map(CreationStyleSkillRepository::map).all();
    }

    public Mono<CreationStyleSkill> findById(UUID id) {
        return db.sql("SELECT " + COLS + " FROM creation_style_skill WHERE id = :id")
                .bind("id", id).map(CreationStyleSkillRepository::map).one();
    }

    /** 生成时直读（决策 F 无缓存）：按分类+code 精确取行（停用与否由调用方判定）。 */
    public Mono<CreationStyleSkill> findByCode(CreationStyleSkillCategory category, String code) {
        return db.sql("SELECT " + COLS + " FROM creation_style_skill WHERE category = :category AND code = :code")
                .bind("category", category.key()).bind("code", code)
                .map(CreationStyleSkillRepository::map).one();
    }

    /** 启动种子逐条插入；UNIQUE(category,code) 冲突静默跳过（ON CONFLICT DO NOTHING）。 */
    public Mono<Void> insertSeed(CreationStyleSkillCategory category, String code, String name,
            String description, String promptContent, int sortOrder) {
        return db.sql("""
                        INSERT INTO creation_style_skill(category, code, name, description, prompt_content, sort_order)
                        VALUES (:category, :code, :name, :description, :promptContent, :sortOrder)
                        ON CONFLICT (category, code) DO NOTHING
                        """)
                .bind("category", category.key()).bind("code", code).bind("name", name)
                .bind("description", description == null ? "" : description)
                .bind("promptContent", promptContent).bind("sortOrder", sortOrder).then();
    }

    /**
     * 乐观锁整行 UPDATE（治理台编辑）。{@code AND version = :expected} 命中 0 行 → 空 Mono，
     * 由上层区分「不存在/版本冲突」→ 409。
     */
    public Mono<CreationStyleSkill> updateRow(UUID id, String name, String description, String promptContent,
            boolean enabled, int expectedVersion, UUID updatedBy) {
        return db.sql("""
                        UPDATE creation_style_skill
                        SET name = :name,
                            description = :description,
                            prompt_content = :promptContent,
                            enabled = :enabled,
                            version = version + 1,
                            updated_by = :updatedBy,
                            updated_at = now()
                        WHERE id = :id AND version = :expectedVersion
                        RETURNING """ + " " + COLS)
                .bind("id", id).bind("name", name).bind("description", description == null ? "" : description)
                .bind("promptContent", promptContent).bind("enabled", enabled)
                .bind("expectedVersion", expectedVersion)
                .bind("updatedBy", nullable(updatedBy, java.util.UUID.class))
                .map(CreationStyleSkillRepository::map).one();
    }

    private static CreationStyleSkill map(Row row, RowMetadata meta) {
        return new CreationStyleSkill(
                row.get("id", UUID.class),
                CreationStyleSkillCategory.fromKey(row.get("category", String.class)),
                row.get("code", String.class),
                row.get("name", String.class),
                row.get("description", String.class),
                row.get("prompt_content", String.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                row.get("sort_order", Integer.class),
                row.get("version", Integer.class),
                row.get("updated_by", UUID.class),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
