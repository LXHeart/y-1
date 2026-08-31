package com.grassland.intelligence.creationassistant;

import java.time.Instant;
import java.util.UUID;

/**
 * 创作草稿（草场 PRD §4.9.7 / Slice 15）。镜像 {@code creation_draft} 表（V19）。
 *
 * <p>
 * 承载推荐官/用户在 AI 创作中心的生产内容（主题/标题/大纲/正文），支持自动保存（乐观锁 PUT）+ 跨设备继续（后端存储天然解决）。source
 * 关联复用前端 {@code CreationSource} 联合类型。
 *
 * @param id
 *            草稿 id
 * @param ownerAccountId
 *            创作者账号
 * @param organizationId
 *            归属组织，可空（store 源时填商家 org）
 * @param title
 *            草稿标题（用户可读名，首期默认）
 * @param sourceType
 *            来源类型（{@link DraftSourceType}）
 * @param taskId
 *            task 源关联任务 id，可空
 * @param taskVersion
 *            task 源冻结的任务版本号，可空（§4.12 创作上下文快照入口）
 * @param storeId
 *            store 源关联门店 id，可空
 * @param platform
 *            目标发布平台，可空（未定时）
 * @param contentForm
 *            内容形式，可空
 * @param topic
 *            创作主题/选题
 * @param articleTitle
 *            文章标题（创作阶段产物）
 * @param outline
 *            大纲
 * @param content
 *            正文
 * @param contentMode
 *            内容模式（article/answer，任务书 #62；知乎回答模式的唯一持久化位）
 * @param questionText
 *            回答模式的目标问题原文，可空
 * @param questionRef
 *            目标问题引用（从粘贴链接本地正则提取的 questionId，仅溯源存档），可空
 * @param status
 *            状态（{@link DraftStatus}）
 * @param version
 *            版本号（乐观锁，每次保存 +1）
 * @param createdAt
 *            创建时间
 * @param updatedAt
 *            更新时间
 * @param deletedAt
 *            软删时间，可空
 */
public record CreationDraft(UUID id, String ownerAccountId, String organizationId, String title,
		DraftSourceType sourceType, String taskId, Integer taskVersion, String storeId, String platform,
		String contentForm, String topic, String articleTitle, String outline, String content,
		DraftContentMode contentMode, String questionText, String questionRef, DraftStatus status, int version,
		Instant createdAt, Instant updatedAt, Instant deletedAt) {
}
