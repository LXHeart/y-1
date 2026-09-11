package com.grassland.intelligence.creationassistant;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 草稿响应映射（任务书 #100 C100-04）：从 {@link CreationDraftController} 抽出的共享视图——
 * 既有草稿端点与画布工作区绑定（API-07）必须下发同一形状，避免两处序列化漂移。
 */
public record CreationDraftView(CreationDraft draft) {

	public static CreationDraftView of(CreationDraft draft) {
		return new CreationDraftView(draft);
	}

	public Map<String, Object> toMap() {
		CreationDraft d = draft;
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", d.id().toString());
		map.put("title", d.title());
		map.put("sourceType", d.sourceType().db());
		map.put("status", d.status().db());
		map.put("version", d.version());
		map.put("createdAt", d.createdAt());
		map.put("updatedAt", d.updatedAt());
		if (d.topic() != null)
			map.put("topic", d.topic());
		if (d.articleTitle() != null)
			map.put("articleTitle", d.articleTitle());
		if (d.outline() != null)
			map.put("outline", d.outline());
		if (d.content() != null)
			map.put("content", d.content());
		// 任务书 #62：contentMode 恒下发（前端恢复草稿要据此还原模式）；问题字段仅回答模式有值
		map.put("contentMode", (d.contentMode() == null ? DraftContentMode.ARTICLE : d.contentMode()).db());
		if (d.questionText() != null)
			map.put("questionText", d.questionText());
		if (d.questionRef() != null)
			map.put("questionRef", d.questionRef());
		if (d.platform() != null)
			map.put("platform", d.platform());
		if (d.contentForm() != null)
			map.put("contentForm", d.contentForm());
		if (d.taskId() != null)
			map.put("taskId", d.taskId());
		if (d.taskVersion() != null)
			map.put("taskVersion", d.taskVersion());
		if (d.storeId() != null)
			map.put("storeId", d.storeId());
		// 任务书 #92 C-02：工作区三字段恒下发（旧行空态）；capability 真相源在 workspace_json，
		// 缺省（旧草稿）按 article 口径回填（创作草稿模型本身就是文章工作流）。
		map.put("capability", d.workspace().get("capability") instanceof String capability ? capability : "article");
		map.put("workspace", CreationWorkspace.sanitizeForRead(d.workspace()));
		map.put("resultAssetIds", d.resultAssetIds() == null ? List.of() : d.resultAssetIds());
		map.put("runIds", d.runIds() == null ? List.of() : d.runIds());
		return map;
	}
}
