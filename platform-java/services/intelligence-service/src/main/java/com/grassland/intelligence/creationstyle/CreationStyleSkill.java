package com.grassland.intelligence.creationstyle;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 创作 style skill 行（任务书 #57，V55 表 {@code creation_style_skill}）。
 *
 * <p>
 * admin 改库行即生效（生成时直读无缓存）；种子不回写契约文件。
 */
public record CreationStyleSkill(UUID id, CreationStyleSkillCategory category, String code, String name,
		String description, String promptContent, boolean enabled, int sortOrder, List<String> applicablePlatforms,
		int version, UUID updatedBy, Instant updatedAt) {

	/**
	 * 平台归属（任务书 #62）：<b>空列表 = 全平台通用</b>（存量 22 条即此态）。DB 存逗号分隔串
	 * （{@code applicable_platforms text NOT NULL DEFAULT ''}，V59），出入库经
	 * {@link #parsePlatforms}/{@link #joinPlatforms} 往返。
	 */
	public CreationStyleSkill {
		applicablePlatforms = applicablePlatforms == null ? List.of() : List.copyOf(applicablePlatforms);
	}

	/** 逗号串 → 有序去重列表；null/空串/纯分隔符 → 空列表（= 通用）。 */
	public static List<String> parsePlatforms(String raw) {
		if (raw == null || raw.isBlank()) {
			return List.of();
		}
		List<String> out = new ArrayList<>();
		for (String part : raw.split(",")) {
			String trimmed = part.trim();
			if (!trimmed.isEmpty() && !out.contains(trimmed)) {
				out.add(trimmed);
			}
		}
		return List.copyOf(out);
	}

	/** 列表 → 逗号串（入库）；空/null → 空串（= 通用，与列 DEFAULT 一致）。 */
	public static String joinPlatforms(List<String> platforms) {
		return platforms == null ? "" : String.join(",", parsePlatforms(String.join(",", platforms)));
	}

	/** 该 skill 是否对目标平台可见：空归属=通用对全平台可见；否则须显式含该平台。 */
	public boolean appliesTo(String platformId) {
		return applicablePlatforms.isEmpty() || (platformId != null && applicablePlatforms.contains(platformId.trim()));
	}

	/** 注入 prompt 段所需的极简视图（ArticlePrompts 组装用，不携带 DB 元数据）。 */
	public record SkillPrompt(String name, String promptContent) {
		public static SkillPrompt from(CreationStyleSkill skill) {
			return new SkillPrompt(skill.name(), skill.promptContent());
		}
	}
}
