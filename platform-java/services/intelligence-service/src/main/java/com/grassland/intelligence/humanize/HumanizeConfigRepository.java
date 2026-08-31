package com.grassland.intelligence.humanize;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 去AI味激活配置仓储（任务书 #61）：单行表 {@code humanize_config}（固定 id=1）， upsert 乐观锁照
 * {@code HomepageHotConfigRepository} 模式。
 */
@Component
public class HumanizeConfigRepository {

	private final DatabaseClient db;

	public HumanizeConfigRepository(DatabaseClient db) {
		this.db = db;
	}

	/** activeSkillCode 当前激活 code；NULL = 不注入。version 行版本（写后 +1）。 */
	public record HumanizeConfig(String activeSkillCode, long version) {
	}

	/** 读配置；无行 → version=0（语义=未配置，首次写入走 INSERT 分支）。 */
	public Mono<HumanizeConfig> findOrDefault() {
		return db.sql("SELECT active_skill_code, version FROM humanize_config WHERE id = 1").map((row,
				meta) -> new HumanizeConfig(row.get("active_skill_code", String.class), row.get("version", Long.class)))
				.one().defaultIfEmpty(new HumanizeConfig(null, 0L));
	}

	/**
	 * 乐观锁写激活项：{@code expectedVersion==0} 表示预期无行走 INSERT（冲突 DO NOTHING → 空 Mono， 上层转
	 * 409）；否则 UPDATE 带 {@code AND version = :expected}，命中 0 行 → 空 Mono。
	 */
	public Mono<HumanizeConfig> upsertActive(String activeSkillCode, long expectedVersion, String adminId) {
		if (expectedVersion == 0L) {
			return db.sql("""
					INSERT INTO humanize_config(id, active_skill_code, version, updated_by)
					VALUES (1, :code, 1, :adminId)
					ON CONFLICT (id) DO NOTHING
					RETURNING active_skill_code, version
					""").bind("code", nullable(activeSkillCode, String.class)).bind("adminId", adminId)
					.map(HumanizeConfigRepository::map).one();
		}
		return db.sql("""
				UPDATE humanize_config
				SET active_skill_code = :code,
				    version = version + 1,
				    updated_by = :adminId,
				    updated_at = now()
				WHERE id = 1 AND version = :expected
				RETURNING active_skill_code, version
				""").bind("code", nullable(activeSkillCode, String.class)).bind("adminId", adminId)
				.bind("expected", expectedVersion).map(HumanizeConfigRepository::map).one();
	}

	private static HumanizeConfig map(io.r2dbc.spi.Row row, io.r2dbc.spi.RowMetadata meta) {
		return new HumanizeConfig(row.get("active_skill_code", String.class), row.get("version", Long.class));
	}
}
