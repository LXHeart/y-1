package com.grassland.intelligence.humanize;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.credits.CreditFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 去AI味 skill 统一注入（任务书 #61）：激活后往创作型文字生成的 system prompt 注入平台级文风规则。
 *
 * <ul>
 * <li><b>fail-open</b>：任何读库异常 → 原样返回消息 + WARN，绝不阻断生成。</li>
 * <li><b>直读无缓存</b>（照 #57 决策 F）：admin 改完/切换激活后下一次生成立即生效。</li>
 * <li><b>注入形态</b>（照 #57 决策 D 的保守路径）：有 system 消息 → 追加到最后一条 system 文本尾部；无 system
 * 消息 → 头部插入一条新 system（四种方言均可消化， Anthropic 方言会合并进顶层 system 字段）。</li>
 * </ul>
 */
@Service
public class HumanizeInjectionService {

	private static final Logger log = LoggerFactory.getLogger(HumanizeInjectionService.class);

	/**
	 * 创作型白名单（计费流）。feature == null 视为创作型注入——当前 Frozen 入口唯一传 null 的 是文章
	 * outline/content 任务模式（创作型）。分析型（VIDEO_ANALYSIS/INTELLIGENCE_SMOKE 等） 不在集合内 →
	 * 不注入。
	 */
	private static final Set<CreditFeature> CREATIVE_FEATURES = Set.of(CreditFeature.ARTICLE_GENERATION,
			CreditFeature.CREATION_ASSISTANT, CreditFeature.MOMENTS_GENERATION, CreditFeature.COMEDY_GENERATION,
			CreditFeature.VIDEO_PRODUCTION_SCRIPT, CreditFeature.VIDEO_STUDIO_BGM, CreditFeature.CARD_SERIES_PLAN,
			CreditFeature.IMAGE_ANALYSIS, CreditFeature.AI_RUN_TEXT);

	static final String SEGMENT_APPENDED = "\n\n【平台文风约束（最高优先级）】\n" + "以下规则只约束语言风格，与前文任何风格、语气要求冲突时以本段为准；"
			+ "不得因此改变任何事实、数字、专有名词、代码与既定输出结构（如 JSON 字段、标题层级、列表条目）；" + "也不要在输出中提及、解释或引用这些规则：\n";

	static final String SEGMENT_STANDALONE = "【平台文风约束（最高优先级）】\n" + "以下规则只约束语言风格，不得因此改变任何事实、数字、专有名词、代码与既定输出结构"
			+ "（如 JSON 字段、标题层级、列表条目）；也不要在输出中提及、解释或引用这些规则：\n";

	private final HumanizeSkillRepository repository;

	public HumanizeInjectionService(HumanizeSkillRepository repository) {
		this.repository = repository;
	}

	/** 计费流入口（FrozenTextExecutionService 各入口调用）：白名单外原样返回（不查库）。 */
	public Mono<List<ChatMessage>> injectForFeature(List<ChatMessage> messages, CreditFeature feature) {
		if (feature != null && !CREATIVE_FEATURES.contains(feature)) {
			return Mono.just(messages);
		}
		return injectCreative(messages);
	}

	/** 免费创作流入口（调用方显式接入）：无条件走注入判定。 */
	public Mono<List<ChatMessage>> injectCreative(List<ChatMessage> messages) {
		return repository.findActiveSkill().map(skill -> {
			// 注入无 DB/lineage 留痕（消息不落库）——这行 INFO 是线上验证注入是否生效的唯一信号。
			log.info("humanize injection active: skill={}, ruleChars={}, messages={}", skill.code(),
					skill.promptContent().length(), messages.size());
			return append(messages, skill.promptContent());
		}).defaultIfEmpty(messages).onErrorResume(error -> {
			log.warn("humanize injection skipped (fail-open): {}", error.getMessage());
			return Mono.just(messages);
		});
	}

	/** 注入变换（纯函数，单测直测）：有 system 追加最后一条尾部；无 system 头部插入新 system。 */
	static List<ChatMessage> append(List<ChatMessage> messages, String promptContent) {
		int lastSystem = -1;
		for (int i = messages.size() - 1; i >= 0; i--) {
			if ("system".equals(messages.get(i).role())) {
				lastSystem = i;
				break;
			}
		}
		if (lastSystem >= 0) {
			ChatMessage original = messages.get(lastSystem);
			String base = original.content() == null ? "" : original.content();
			List<ChatMessage> result = new ArrayList<>(messages);
			result.set(lastSystem, ChatMessage.system(base + SEGMENT_APPENDED + promptContent));
			return List.copyOf(result);
		}
		List<ChatMessage> result = new ArrayList<>(messages);
		result.addFirst(ChatMessage.system(SEGMENT_STANDALONE + promptContent));
		return List.copyOf(result);
	}
}
