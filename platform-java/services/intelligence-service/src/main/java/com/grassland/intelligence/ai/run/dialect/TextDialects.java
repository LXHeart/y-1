package com.grassland.intelligence.ai.run.dialect;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * provider 名 → 方言 的唯一查表点。
 *
 * <p><b>为什么是宽容回落而非 fail-closed</b>：provider 这个字符串有两个来源——控制面平台凭据/模型行
 * （有正则约束，取值就是四个方言名 + {@code openai-compatible} + {@code sandbox}），以及 BYOK
 * 用户自填行（{@code CreateAiProviderKeyRequest.provider} 只有 {@code @NotBlank}，无正则，历史上什么都可能存进去）。
 * 迁移前 {@link com.grassland.intelligence.ai.run.TextCompletionClient} 对 provider <b>零分支</b>——
 * 所有取值都走 OpenAI Chat Completions 形状。所以未知名字回落到
 * {@link OpenAiCompletionsDialect} 恰好等于迁移前的既有行为，而 fail-closed 会把存量 BYOK 行一次性打死。
 *
 * <p>SSRF/受信 origin 的把关不在这里——平台分支由
 * {@link com.grassland.intelligence.ai.controlplane.PlatformProviderPolicy} 校验，BYOK 走
 * {@code PinnedByokClients}。本类只决定「说哪门方言」。
 */
@Component
public final class TextDialects {

	private final Map<String, TextDialect> byName;
	private final TextDialect fallback;

	public TextDialects(List<TextDialect> dialects) {
		this.byName = dialects.stream().collect(Collectors.toUnmodifiableMap(TextDialect::name, Function.identity()));
		TextDialect openAiCompletions = byName.get(OpenAiCompletionsDialect.NAME);
		if (openAiCompletions == null) {
			throw new IllegalStateException("缺少默认方言 " + OpenAiCompletionsDialect.NAME);
		}
		this.fallback = openAiCompletions;
	}

	/**
	 * 取 provider 对应方言；未知/空取值回落 {@code openai-completions}（含 legacy {@code qwen}、
	 * {@code openai-compatible} 与 {@code sandbox}——三者本就是同一条 OpenAI 形状路径）。
	 */
	public TextDialect resolve(String provider) {
		if (provider == null) {
			return fallback;
		}
		return byName.getOrDefault(provider.trim().toLowerCase(Locale.ROOT), fallback);
	}

	/** 已注册的方言名集合，供诊断与测试断言用。 */
	public Map<String, TextDialect> registered() {
		return new HashMap<>(byName);
	}
}
