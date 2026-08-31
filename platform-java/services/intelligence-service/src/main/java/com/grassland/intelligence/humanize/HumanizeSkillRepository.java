package com.grassland.intelligence.humanize;

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
 * 去AI味 skill 仓储（任务书 #61）。{@code DatabaseClient} 裸 SQL 惯例 （照
 * {@code CreationStyleSkillRepository}）。
 */
@Component
public class HumanizeSkillRepository {

	static final String COLS = "id, code, display_name, description, prompt_content, "
			+ "source_repo, source_license, enabled, version, updated_by, updated_at";

	/**
	 * JOIN 场景的 skill 侧投影：{@code humanize_config} 与 {@code humanize_skill} 同名列有
	 * id/version/updated_by/updated_at 四处，不加表别名 Postgres 直接报 ambiguous column
	 * （错误会被注入侧 fail-open 吞掉 → 注入永远静默不生效）。输出列标签与 {@link #COLS} 一致， 故 {@link #map}
	 * 不变。
	 */
	private static final String JOINED_SKILL_COLS = "s.id, s.code, s.display_name, s.description, "
			+ "s.prompt_content, s.source_repo, s.source_license, s.enabled, s.version, "
			+ "s.updated_by, s.updated_at";

	private final DatabaseClient db;

	public HumanizeSkillRepository(DatabaseClient db) {
		this.db = db;
	}

	public Mono<Long> count() {
		return db.sql("SELECT count(*) AS n FROM humanize_skill").map((row, meta) -> row.get("n", Long.class)).one();
	}

	/** 治理台全量（含停用含 promptContent）。 */
	public Flux<HumanizeSkill> listAll() {
		return db.sql("SELECT " + COLS + " FROM humanize_skill ORDER BY code").map(HumanizeSkillRepository::map).all();
	}

	public Mono<HumanizeSkill> findById(UUID id) {
		return db.sql("SELECT " + COLS + " FROM humanize_skill WHERE id = :id").bind("id", id)
				.map(HumanizeSkillRepository::map).one();
	}

	public Mono<HumanizeSkill> findByCode(String code) {
		return db.sql("SELECT " + COLS + " FROM humanize_skill WHERE code = :code").bind("code", code)
				.map(HumanizeSkillRepository::map).one();
	}

	/**
	 * 生成时直读（无缓存）：当前激活且启用中的 skill——humanize_config 单行 JOIN humanize_skill， active 为
	 * NULL / 无行 / skill 已停用均返回空（= 不注入）。
	 */
	public Mono<HumanizeSkill> findActiveSkill() {
		return db.sql("SELECT " + JOINED_SKILL_COLS + " FROM humanize_config c "
				+ "JOIN humanize_skill s ON c.active_skill_code = s.code " + "WHERE c.id = 1 AND s.enabled = true")
				.map(HumanizeSkillRepository::map).one();
	}

	/** 启动种子逐条插入；UNIQUE(code) 冲突静默跳过。 */
	public Mono<Void> insertSeed(String code, String displayName, String description, String promptContent,
			String sourceRepo, String sourceLicense) {
		return db.sql("""
				INSERT INTO humanize_skill(code, display_name, description, prompt_content,
				    source_repo, source_license)
				VALUES (:code, :displayName, :description, :promptContent, :sourceRepo, :sourceLicense)
				ON CONFLICT (code) DO NOTHING
				""").bind("code", code).bind("displayName", displayName)
				.bind("description", description == null ? "" : description).bind("promptContent", promptContent)
				.bind("sourceRepo", sourceRepo).bind("sourceLicense", sourceLicense).then();
	}

	/**
	 * 乐观锁整行 UPDATE（治理台编辑）。{@code AND version = :expected} 命中 0 行 → 空 Mono，
	 * 由上层区分「不存在/版本冲突」→ 409。
	 */
	public Mono<HumanizeSkill> updateRow(UUID id, String displayName, String description, String promptContent,
			boolean enabled, int expectedVersion, UUID updatedBy) {
		return db.sql("""
				UPDATE humanize_skill
				SET display_name = :displayName,
				    description = :description,
				    prompt_content = :promptContent,
				    enabled = :enabled,
				    version = version + 1,
				    updated_by = :updatedBy,
				    updated_at = now()
				WHERE id = :id AND version = :expected
				RETURNING """ + " " + COLS).bind("id", id).bind("displayName", displayName)
				.bind("description", description == null ? "" : description).bind("promptContent", promptContent)
				.bind("enabled", enabled).bind("expected", expectedVersion)
				.bind("updatedBy", nullable(updatedBy, UUID.class)).map(HumanizeSkillRepository::map).one();
	}

	static HumanizeSkill map(Row row, RowMetadata meta) {
		return new HumanizeSkill(row.get("id", UUID.class), row.get("code", String.class),
				row.get("display_name", String.class), row.get("description", String.class),
				row.get("prompt_content", String.class), row.get("source_repo", String.class),
				row.get("source_license", String.class), Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
				row.get("version", Integer.class), row.get("updated_by", UUID.class),
				toInstant(row.get("updated_at", OffsetDateTime.class)));
	}

	private static Instant toInstant(OffsetDateTime value) {
		return value == null ? null : value.toInstant();
	}
}
