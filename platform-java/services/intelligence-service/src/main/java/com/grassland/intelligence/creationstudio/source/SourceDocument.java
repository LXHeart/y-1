package com.grassland.intelligence.creationstudio.source;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 任务书 #101 C101-02（§6.3）：不可变来源文档行。创建后只读——重新导入产生新 document 与新块 ID；
 * 正文不因解析告警丢失（warnings 只描述解析限制）。
 */
public record SourceDocument(UUID id, String ownerAccountId, UUID draftId, String requestId, String requestHash,
		String kind, int schemaVersion, String title, String rawText, String normalizedMarkdown, String contentHash,
		List<Block> blocks, List<Map<String, Object>> sourceRefs, List<String> warnings, Instant createdAt) {

	/**
	 * 顶层块（§6.2 SourceBlock 同形；跨度为 normalizedMarkdown 的 code point 区间 [start,end)）。
	 */
	public record Block(String id, String kind, int position, int startCodePoint, int endCodePoint, String text,
			String textHash) {
	}
}
