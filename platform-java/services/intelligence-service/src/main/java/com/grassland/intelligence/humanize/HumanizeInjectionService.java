package com.grassland.intelligence.humanize;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.credits.CreditFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 去AI味 skill 统一注入（任务书 #61；任务书 #108 C-01 消歧优先级并增加同次注入元数据）： 激活后往创作型文字生成的 system
 * prompt 注入平台级文风规则。
 *
 * <ul>
 * <li><b>fail-open</b>：任何读库异常 → 原样返回消息 + WARN + {@code degraded}
 * 元数据，绝不阻断生成。</li>
 * <li><b>直读无缓存</b>（照 #57 决策 F）：admin 改完/切换激活后下一次生成立即生效。</li>
 * <li><b>注入形态</b>（照 #57 决策 D 的保守路径）：有 system 消息 → 追加到最后一条 system 文本尾部；无 system
 * 消息 → 头部插入一条新 system（四种方言均可消化， Anthropic 方言会合并进顶层 system 字段）。</li>
 * <li><b>优先级语义</b>（任务书 #108，RULE-001）：文风规则只约束语言风格——事实、身份边界、否定/条件与既定输出
 * 结构优先于本段；不再声明「最高优先级」，避免与任务模板、用户明确要求互相顶牛。</li>
 * <li><b>同次元数据</b>（任务书 #108 §6.6）：detailed 入口在同一次查库内同时形成注入内容与
 * {@link Metadata}（activeCode/version/contentHash/status），供四平台调用者记录；不查第二次库拼版本。</li>
 * </ul>
 */
@Service
public class HumanizeInjectionService {

	private static final Logger log = LoggerFactory.getLogger(HumanizeInjectionService.class);

	/** 固定策略版本（任务书 #108 §6.6）：标识本段注入文案与元数据的组装策略。 */
	public static final String VOICE_POLICY_VERSION = "voice-policy-108-v1";

	/**
	 * 创作型白名单（计费流）。feature == null 视为创作型注入——当前 Frozen 入口唯一传 null 的 是文章
	 * outline/content 任务模式（创作型）。分析型（VIDEO_ANALYSIS/INTELLIGENCE_SMOKE 等） 不在集合内 →
	 * 不注入。
	 */
	private static final Set<CreditFeature> CREATIVE_FEATURES = Set.of(CreditFeature.ARTICLE_GENERATION,
			CreditFeature.CREATION_ASSISTANT, CreditFeature.MOMENTS_GENERATION, CreditFeature.COMEDY_GENERATION,
			CreditFeature.VIDEO_PRODUCTION_SCRIPT, CreditFeature.VIDEO_STUDIO_BGM, CreditFeature.CARD_SERIES_PLAN,
			CreditFeature.IMAGE_ANALYSIS, CreditFeature.AI_RUN_TEXT);

	static final String SEGMENT_APPENDED = "\n\n【平台文风建议】\n"
			+ "以下规则只约束语言风格：事实、数字、专有名词、代码与既定输出结构（如 JSON 字段、标题层级、列表条目）一律按原要求执行；"
			+ "不得新增未经确认的身份、资历、购买或到店经历，不得改变评价的正负方向，不得将参考作者的经历移植为当前作者自述；" + "与前文的事实、身份或明确任务要求冲突时，以后者为准；"
			+ "也不要在输出中提及、解释或引用这些规则：\n";

	static final String SEGMENT_STANDALONE = "【平台文风建议】\n"
			+ "以下规则只约束语言风格：事实、数字、专有名词、代码与既定输出结构（如 JSON 字段、标题层级、列表条目）一律按原要求执行；" + "不得新增未经确认的身份、资历、购买或到店经历，不得改变评价的正负方向；"
			+ "与事实、身份或明确任务要求冲突时，以后者为准；" + "也不要在输出中提及、解释或引用这些规则：\n";

	private final HumanizeSkillRepository repository;

	public HumanizeInjectionService(HumanizeSkillRepository repository) {
		this.repository = repository;
	}

	/**
	 * 注入结果（任务书 #108 §6.6）：messages 为注入后的消息列表；metadata 为同次读取得到的运行元数据， 白名单外不评估时为
	 * {@code null}。
	 */
	public record Injection(List<ChatMessage> messages, Metadata metadata) {
	}

	/**
	 * 同次注入元数据：status=applied（已注入）/ disabled（未激活或激活项停用）/ degraded（读库失败降级）。
	 * contentHash 为 promptContent 的 SHA-256；disabled/degraded 时
	 * activeCode/version/contentHash 为
	 * {@code null}。日志与调用方记录仅存本元数据，不写规则正文（RULE-015）。
	 */
	public record Metadata(String policyVersion, String status, String activeCode, Integer version,
			String contentHash) {

		static Metadata applied(String activeCode, int version, String contentHash) {
			return new Metadata(VOICE_POLICY_VERSION, "applied", activeCode, version, contentHash);
		}

		static Metadata disabled() {
			return new Metadata(VOICE_POLICY_VERSION, "disabled", null, null, null);
		}

		static Metadata degraded() {
			return new Metadata(VOICE_POLICY_VERSION, "degraded", null, null, null);
		}
	}

	/** 计费流入口（FrozenTextExecutionService 各入口调用）：白名单外原样返回（不查库、无元数据）。 */
	public Mono<List<ChatMessage>> injectForFeature(List<ChatMessage> messages, CreditFeature feature) {
		if (feature != null && !CREATIVE_FEATURES.contains(feature)) {
			return Mono.just(messages);
		}
		return injectCreative(messages);
	}

	/** 免费创作流入口（调用方显式接入）：无条件走注入判定。 */
	public Mono<List<ChatMessage>> injectCreative(List<ChatMessage> messages) {
		return injectCreativeDetailed(messages).map(Injection::messages);
	}

	/**
	 * 计费流 detailed 入口（任务书 #108）：白名单外原样返回且 metadata=null（不查库）；其余与
	 * {@link #injectCreativeDetailed} 同语义。
	 */
	public Mono<Injection> injectForFeatureDetailed(List<ChatMessage> messages, CreditFeature feature) {
		if (feature != null && !CREATIVE_FEATURES.contains(feature)) {
			return Mono.just(new Injection(messages, null));
		}
		return injectCreativeDetailed(messages);
	}

	/**
	 * 免费创作流 detailed 入口：一次查库同时形成注入内容与元数据（无第二次查库）。语义与旧 {@link #injectCreative}
	 * 完全一致，仅额外携带元数据。
	 */
	public Mono<Injection> injectCreativeDetailed(List<ChatMessage> messages) {
		return repository.findActiveSkill().map(skill -> {
			// 注入无 DB/lineage 留痕（消息不落库）——这行 INFO 是线上验证注入是否生效的唯一信号。
			// 只记 code/version/hash/长度（RULE-015：不写规则正文）。
			log.info("humanize injection active: skill={}, version={}, contentHash={}, ruleChars={}, messages={}",
					skill.code(), skill.version(), sha256(skill.promptContent()), skill.promptContent().length(),
					messages.size());
			return new Injection(append(messages, skill.promptContent()),
					Metadata.applied(skill.code(), skill.version(), sha256(skill.promptContent())));
		}).defaultIfEmpty(new Injection(List.copyOf(messages), Metadata.disabled())).onErrorResume(error -> {
			log.warn("humanize injection skipped (fail-open): {}", error.getMessage());
			return Mono.just(new Injection(List.copyOf(messages), Metadata.degraded()));
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

	private static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException error) {
			// JDK 必带 SHA-256；理论不可达——降级为哈希占位，元数据仍标 applied 状态
			return "unavailable";
		}
	}
}
