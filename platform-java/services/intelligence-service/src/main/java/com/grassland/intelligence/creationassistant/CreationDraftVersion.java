package com.grassland.intelligence.creationassistant;

import java.time.Instant;
import java.util.UUID;

/** 创作草稿的只读版本视图；历史行来自不可变快照，最新版本来自当前草稿行。 */
public record CreationDraftVersion(UUID draftId, int version, String title, DraftSourceType sourceType, String taskId,
		Integer taskVersion, String storeId, String platform, String contentForm, String topic, String articleTitle,
		String outline, String content, DraftContentMode contentMode, String questionText, String questionRef,
		DraftStatus status, Instant createdAt) {
}
