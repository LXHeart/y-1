package com.grassland.marketplace.taskcatalog;

import java.util.List;
import java.util.UUID;

/**
 * 草稿送审请求体（任务书 #96 C96-04 §6 /submissions/draft）：附件形态（mediaIds 可空=纯说明文案），
 * <b>不要求公开链接</b>（TC96-015）。{@code note} 创作说明/自审要点，可空。
 */
public record DraftSubmissionRequest(String note, List<UUID> mediaIds) {
}
