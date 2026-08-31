package com.grassland.intelligence.humanize;

import java.time.Instant;
import java.util.UUID;

/**
 * 去AI味 skill 行（任务书 #61，V58 表 {@code humanize_skill}）。
 *
 * <p>
 * admin 改库行即生效（生成时直读无缓存，照 #57 决策 F）；激活项存 {@code humanize_config}
 * 单行表（NULL=不注入）；种子不回写契约文件。
 */
public record HumanizeSkill(UUID id, String code, String displayName, String description, String promptContent,
		String sourceRepo, String sourceLicense, boolean enabled, int version, UUID updatedBy, Instant updatedAt) {
}
