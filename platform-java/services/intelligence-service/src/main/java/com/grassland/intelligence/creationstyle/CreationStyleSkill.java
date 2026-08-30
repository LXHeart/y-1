package com.grassland.intelligence.creationstyle;

import java.time.Instant;
import java.util.UUID;

/**
 * 创作 style skill 行（任务书 #57，V55 表 {@code creation_style_skill}）。
 *
 * <p>admin 改库行即生效（生成时直读无缓存）；种子不回写契约文件。
 */
public record CreationStyleSkill(
        UUID id,
        CreationStyleSkillCategory category,
        String code,
        String name,
        String description,
        String promptContent,
        boolean enabled,
        int sortOrder,
        int version,
        UUID updatedBy,
        Instant updatedAt) {

    /** 注入 prompt 段所需的极简视图（ArticlePrompts 组装用，不携带 DB 元数据）。 */
    public record SkillPrompt(String name, String promptContent) {
        public static SkillPrompt from(CreationStyleSkill skill) {
            return new SkillPrompt(skill.name(), skill.promptContent());
        }
    }
}
