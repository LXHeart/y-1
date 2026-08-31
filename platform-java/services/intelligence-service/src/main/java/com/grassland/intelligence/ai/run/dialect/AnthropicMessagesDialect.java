package com.grassland.intelligence.ai.run.dialect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * Anthropic Messages 方言（{@code POST {base}/messages}，base 形如 {@code https://api.anthropic.com/v1}）。
 *
 * <p>与 OpenAI 形状的四处硬差异：
 * <ol>
 * <li>鉴权是 {@code x-api-key} + 必填 {@code anthropic-version}，<b>不是</b> Bearer；</li>
 * <li>{@code system} 不是一条消息，而是顶层字段——system 角色必须上提，留在 messages 里会 400；</li>
 * <li>{@code max_tokens} 是<b>必填</b>（OpenAI 侧可省）；</li>
 * <li>图片是 {@code source{type,media_type,data}}，不是 {@code image_url}；视频无对应能力。</li>
 * </ol>
 */
@Component
public final class AnthropicMessagesDialect implements TextDialect {

	static final String NAME = "anthropic-messages";

	/** Anthropic 要求每次请求显式声明 API 版本；缺失即 400。 */
	private static final String ANTHROPIC_VERSION = "2023-06-01";

	private static final String ROLE_SYSTEM = "system";
	private static final String ROLE_ASSISTANT = "assistant";
	private static final String ROLE_USER = "user";

	/** {@code data:image/png;base64,xxxx} → (media_type, data)。 */
	private static final Pattern DATA_URI = Pattern.compile("^data:([^;,]+);base64,(.+)$", Pattern.DOTALL);

	private final ObjectMapper mapper = new ObjectMapper();

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String path(String model, boolean stream) {
		return "messages";
	}

	@Override
	public void applyAuth(HttpHeaders headers, String bearer) {
		headers.set("x-api-key", bearer);
		headers.set("anthropic-version", ANTHROPIC_VERSION);
	}

	@Override
	public Map<String, Object> body(String model, List<ChatMessage> messages, int maxTokens, boolean stream) {
		List<Map<String, Object>> turns = new ArrayList<>();
		List<String> systemTexts = new ArrayList<>();
		for (ChatMessage message : messages) {
			if (ROLE_SYSTEM.equals(message.role())) {
				// 多模态 system 不是有效形状，取文本片断即可（实践中 system 恒为明文）。
				systemTexts.add(message.multimodal() ? textOf(message) : message.content());
				continue;
			}
			turns.add(turnBody(message));
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("max_tokens", maxTokens);
		if (!systemTexts.isEmpty()) {
			body.put(ROLE_SYSTEM, String.join("\n\n", systemTexts));
		}
		body.put("messages", turns);
		if (stream) {
			body.put("stream", true);
		}
		return body;
	}

	private static String textOf(ChatMessage message) {
		return message.parts().stream()
				.filter(part -> part instanceof ContentPart.Text)
				.map(part -> ((ContentPart.Text) part).text())
				.reduce((left, right) -> left + "\n" + right)
				.orElse("");
	}

	private static Map<String, Object> turnBody(ChatMessage message) {
		Map<String, Object> body = new LinkedHashMap<>();
		// Anthropic 只认 user/assistant；其余角色（含意外值）归一到 user，避免上游 400。
		body.put("role", ROLE_ASSISTANT.equals(message.role()) ? ROLE_ASSISTANT : ROLE_USER);
		body.put("content",
				message.multimodal()
						? message.parts().stream().map(AnthropicMessagesDialect::partBody).toList()
						: List.of(Map.of("type", "text", "text", message.content())));
		return body;
	}

	private static Map<String, Object> partBody(ContentPart part) {
		return switch (part) {
			case ContentPart.Text text -> Map.of("type", "text", "text", text.text());
			case ContentPart.Image image -> imageBody(image.url());
			// 视频理解 Anthropic 无对应端点能力：显式 400 而不是静默丢片断——丢了会让模型
			// 面对「请分析这个视频」却没有视频，返回一段一本正经的空想。
			case ContentPart.Video video ->
				throw new IntelligenceException(400, "anthropic-messages 方言不支持视频输入，请改用支持视频理解的模型");
		};
	}

	private static Map<String, Object> imageBody(String url) {
		Matcher dataUri = DATA_URI.matcher(url);
		if (dataUri.matches()) {
			return Map.of("type", "image", "source",
					Map.of("type", "base64", "media_type", dataUri.group(1), "data", dataUri.group(2)));
		}
		return Map.of("type", "image", "source", Map.of("type", "url", "url", url));
	}

	@Override
	public TextCompletionResult parse(String json) {
		try {
			JsonNode root = mapper.readTree(json);
			StringBuilder content = new StringBuilder();
			for (JsonNode block : root.path("content")) {
				if ("text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
					content.append(block.path("text").asText());
				}
			}
			JsonNode usage = root.get("usage");
			int inputTokens = DialectUsage.requireInt(usage, "input_tokens");
			int outputTokens = DialectUsage.requireInt(usage, "output_tokens");
			DialectUsage.assertSumFits(inputTokens, outputTokens);
			String providerRunId = root.path("id").isTextual() ? root.path("id").asText() : null;
			return new TextCompletionResult(content.toString(), inputTokens, outputTokens, providerRunId);
		} catch (IntelligenceException e) {
			throw e;
		} catch (Exception e) {
			throw new IntelligenceException(502, "AI provider 返回了无法解析的内容");
		}
	}

	@Override
	public String streamDelta(String data) {
		try {
			JsonNode event = mapper.readTree(data);
			if (!"content_block_delta".equals(event.path("type").asText())) {
				return null;   // message_start/ping/content_block_start 等无正文事件
			}
			JsonNode text = event.path("delta").path("text");
			return text.isTextual() && !text.asText().isEmpty() ? text.asText() : null;
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public boolean isStreamEnd(String data) {
		try {
			return "message_stop".equals(mapper.readTree(data).path("type").asText());
		} catch (Exception e) {
			return false;
		}
	}
}
