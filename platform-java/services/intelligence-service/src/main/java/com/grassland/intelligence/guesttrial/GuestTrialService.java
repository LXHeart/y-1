package com.grassland.intelligence.guesttrial;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 游客试用三能力的 provider 调用（R5）。匿名端点 → 经
 * {@link RoutedTextCompletionService#completePlatformOnly} 固定平台内置模型（管理后台控制面；
 * 凭据无密钥回落 env bootstrap），非流式聚合 + 固定 JSON 解析（试用价值密度优先，流式逐 token 属登录后体验）。prompt
 * 为试用专用裁剪版（{@link GuestTrialPrompts}），不改既有文件。
 */
@Component
public class GuestTrialService {

	/** 多模态/长文完成的超时（试用面非流式，超时即 provider_error，不烧额度）。 */
	private static final Duration TIMEOUT = Duration.ofSeconds(60);
	private static final String FAILURE = "试用生成失败，请稍后再试";

	private final RoutedTextCompletionService routed;

	// 任务书 #61：去AI味 skill 注入（免费 Routed 通道显式接入；计费流在执行环内统一注入）
	private final com.grassland.intelligence.humanize.HumanizeInjectionService humanize;

	public GuestTrialService(RoutedTextCompletionService routed,
			com.grassland.intelligence.humanize.HumanizeInjectionService humanize) {
		this.routed = routed;
		this.humanize = humanize;
	}

	/** article-titles：主题 → 5 个候选标题 JSON。 */
	public Mono<String> titles(String topic) {
		return complete(GuestTrialPrompts.titles(topic));
	}

	/** content-score：文案 → 5 维评分 JSON。 */
	public Mono<String> score(String content) {
		return complete(GuestTrialPrompts.score(content));
	}

	/** image-review：探店照片 base64 → 点评草稿 JSON。base64 进 buffer 前已限长（controller）。 */
	public Mono<String> imageReview(String imageBase64, String mimeType) {
		List<ContentPart> parts = new ArrayList<>();
		parts.add(ContentPart.image("data:" + mimeType + ";base64," + imageBase64));
		parts.add(ContentPart.text(GuestTrialPrompts.imageReviewInstruction()));
		return humanize.injectCreative(List.of(ChatMessage.user(parts)))
				.flatMap(msgs -> routed.completePlatformOnly(msgs, 1024, TIMEOUT, FAILURE))
				.map(result -> requireJson(result.content())).onErrorMap(GuestTrialService::asProviderError);
	}

	private Mono<String> complete(List<ChatMessage> messages) {
		return humanize.injectCreative(messages)
				.flatMap(msgs -> routed.completePlatformOnly(msgs, 1024, TIMEOUT, FAILURE))
				.map(result -> requireJson(result.content())).onErrorMap(GuestTrialService::asProviderError);
	}

	/** 试用帧契约：result 载荷必须是 JSON 对象（剥 markdown code fence；非对象 → provider_error）。 */
	private static String requireJson(String raw) {
		String stripped = raw == null ? "" : raw.trim();
		if (stripped.startsWith("```")) {
			int start = stripped.indexOf('\n');
			int end = stripped.lastIndexOf("```");
			if (start >= 0 && end > start) {
				stripped = stripped.substring(start + 1, end).trim();
			}
		}
		if (!stripped.startsWith("{") || !stripped.endsWith("}")) {
			throw new IntelligenceException(502, "试用生成返回了无法解析的内容");
		}
		return stripped;
	}

	private static Throwable asProviderError(Throwable error) {
		return error instanceof IntelligenceException ? error : new IntelligenceException(502, FAILURE);
	}
}
