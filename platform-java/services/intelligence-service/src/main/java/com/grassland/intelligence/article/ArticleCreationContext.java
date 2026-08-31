package com.grassland.intelligence.article;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.creationcontext.CreationContextSnapshot;
import com.grassland.intelligence.creationcontext.CreationContextSnapshotRepository;
import com.grassland.intelligence.creationcontext.StoreBrandingPromptText;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Validates and renders the frozen PRD 4.12 context used by article prompts.
 */
@Service
class ArticleCreationContext {
	private final CreationContextSnapshotRepository snapshots;
	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

	ArticleCreationContext(CreationContextSnapshotRepository snapshots) {
		this.snapshots = snapshots;
	}

	Mono<Binding> bind(UUID snapshotId, String accountId, String requestedPlatform) {
		if (snapshotId == null) {
			return Mono.error(new IntelligenceException(400, "任务创作必须绑定创作上下文快照"));
		}
		return snapshots.findById(snapshotId).filter(snapshot -> accountId.equals(snapshot.accountId()))
				.switchIfEmpty(Mono.error(new IntelligenceException(403, "无权使用该创作上下文快照")))
				.map(snapshot -> binding(snapshot, requestedPlatform));
	}

	private Binding binding(CreationContextSnapshot snapshot, String requestedPlatform) {
		if (!"graphic".equals(snapshot.contentFormId())) {
			throw new IntelligenceException(409, "创作上下文不是图文任务");
		}
		ArticlePrompts.Platform platform = switch (snapshot.platformId()) {
			case "wechat-official" -> ArticlePrompts.Platform.WECHAT;
			case "zhihu" -> ArticlePrompts.Platform.ZHIHU;
			case "xiaohongshu", "douyin" -> ArticlePrompts.Platform.XIAOHONGSHU;
			default -> throw new IntelligenceException(409, "当前文章工作流不支持快照中的目标平台");
		};
		ArticlePrompts.Platform requested = switch (requestedPlatform == null ? "" : requestedPlatform) {
			case "wechat" -> ArticlePrompts.Platform.WECHAT;
			case "zhihu" -> ArticlePrompts.Platform.ZHIHU;
			case "xiaohongshu" -> ArticlePrompts.Platform.XIAOHONGSHU;
			default -> throw new IntelligenceException(409, "请求平台与冻结的创作上下文不一致");
		};
		if (requested != platform) {
			throw new IntelligenceException(409, "请求平台与冻结的创作上下文不一致");
		}
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("contextSnapshotId", snapshot.id());
		context.put("task", snapshot.taskSnapshot());
		context.put("platformRules", snapshot.platformRulesSnapshot());
		context.put("materials", snapshot.materialSnapshot());
		// 任务书 #24：门店品牌块随冻结上下文下发（无门店任务省略）。
		if (!snapshot.storeBrandingSnapshot().isEmpty()) {
			context.put("storeBranding", snapshot.storeBrandingSnapshot());
		}
		try {
			return new Binding(snapshot, platform,
					ChatMessage.system("以下 JSON 是创作开始时冻结的权威任务上下文。必须遵守任务要求和平台规则，" + "只能使用其中授权的素材信息，不得用当前配置或推测覆盖。\n"
							+ mapper.writeValueAsString(context)
							+ StoreBrandingPromptText.render(snapshot.storeBrandingSnapshot())));
		} catch (Exception error) {
			throw new IntelligenceException(500, "创作上下文无法序列化");
		}
	}

	record Binding(CreationContextSnapshot snapshot, ArticlePrompts.Platform platform, ChatMessage promptContext) {

		/**
		 * 任务冻结的目标问题（任务书 #62）：商家发包时若填了「目标问题」，accept 时随 taskSnapshot 冻结。
		 *
		 * <p>
		 * 冻结上下文是权威：生成时以此为准，<b>忽略请求体 question</b>（照 §4.12 既有姿态——
		 * 前端可被篡改，快照不可）。仅知乎任务可能有值；其余平台/未填 → null。
		 */
		String frozenQuestion() {
			if (platform != ArticlePrompts.Platform.ZHIHU) {
				return null;
			}
			Object value = snapshot.taskSnapshot().get("questionText");
			if (value == null) {
				return null;
			}
			String text = String.valueOf(value).trim();
			return text.isEmpty() ? null : text;
		}
	}
}
