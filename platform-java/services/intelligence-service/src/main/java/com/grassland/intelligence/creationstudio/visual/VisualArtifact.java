package com.grassland.intelligence.creationstudio.visual;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 任务书 #101 C101-09（§6.2/D10）：不可变视觉成品记录。 原图媒体与交付画幅衍生媒体分开保存；attempt 唯一——同 attempt
 * 重放读回同一行，不产生第二份引用。
 */
public record VisualArtifact(UUID id, String ownerAccountId, UUID draftId, UUID planId, int planRevision, String itemId,
		UUID attemptId, UUID runId, UUID originalMediaId, UUID deliveryMediaId, String targetAspect, int width,
		int height, String contentHash, UUID anchorArtifactId, OffsetDateTime createdAt) {
}
