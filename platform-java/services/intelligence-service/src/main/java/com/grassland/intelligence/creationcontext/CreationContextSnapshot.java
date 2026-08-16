package com.grassland.intelligence.creationcontext;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable task creation context captured at the AI-center handoff.
 *
 * <p>任务书 #24：{@code storeBrandingSnapshot} 冻结门店品牌上下文（品牌语气/必须强调/
 * 禁止表达/可使用标签等，来自 marketplace 权威快照端点）；无门店任务为空 map。
 */
public record CreationContextSnapshot(
        UUID id,
        String accountId,
        String organizationId,
        String taskId,
        String applicationId,
        int taskVersion,
        String platformId,
        String contentFormId,
        Map<String, Object> taskSnapshot,
        Map<String, Object> platformRulesSnapshot,
        Map<String, Object> materialSnapshot,
        Map<String, Object> aiConfigSnapshot,
        Map<String, Object> storeBrandingSnapshot,
        Instant createdAt) {
}
