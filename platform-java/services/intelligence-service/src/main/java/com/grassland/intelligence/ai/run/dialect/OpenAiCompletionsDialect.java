package com.grassland.intelligence.ai.run.dialect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.ThinkingContentFilter;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/**
 * OpenAI Chat Completions 方言（{@code POST {base}/chat/completions}）。
 *
 * <p>这是**历史默认形状**：改造前 {@code TextCompletionClient} 只会说这一种话，{@code qwen} 与
 * {@code openai-compatible} 两个 provider 名走的是逐字节相同的这条路径（qwen 从来只是标签，不是方言）。
 * 本类是那段行为的原样搬迁，请求体与解析口径不得改动——DashScope、MiniMax、vLLM、各类兼容网关
 * 都吃这一形状。
 */
@Component
public final class OpenAiCompletionsDialect implements TextDialect {

	static final String NAME = "openai-completions";

	private static final String DONE = "[DONE]";

	private final ObjectMapper mapper = new ObjectMapper();

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String path(String model, boolean stream) {
		return "chat/completions";
	}

	@Override
	public void applyAuth(HttpHeaders headers, String bearer) {
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
	}

	@Override
	public Map<String, Object> body(String model, List<ChatMessage> messages, int maxTokens, boolean stream) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("messages", messages.stream().map(OpenAiCompletionsDialect::messageBody).toList());
		body.put("stream", stream);
		body.put("max_tokens", maxTokens);
		putThinkingDisabled(body);
		return body;
	}

	/**
	 * 关闭思考的两家方言并列发出：{@code thinking.type=disabled} 是 MiniMax-M3 的开关（缺省
	 * adaptive，思考会内联进 content）；{@code enable_thinking=false} 是 Qwen/DashScope 惯例。
	 * OpenAI 官方及多数兼容网关忽略未知字段；解析侧另有 {@link ThinkingContentFilter} 兜底，
	 * 上游不认参数时仍能拿到干净正文。
	 */
	private static void putThinkingDisabled(Map<String, Object> body) {
		body.put("enable_thinking", false);
		body.put("thinking", Map.of("type", "disabled"));
	}

	private static Map<String, Object> messageBody(ChatMessage message) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("role", message.role());
		body.put("content",
				message.multimodal()
						? message.parts().stream().map(OpenAiCompletionsDialect::partBody).toList()
						: message.content());
		return body;
	}

	private static Map<String, Object> partBody(ContentPart part) {
		return switch (part) {
			case ContentPart.Text text -> Map.of("type", "text", "text", text.text());
			case ContentPart.Image image -> Map.of("type", "image_url", "image_url", Map.of("url", image.url()));
			case ContentPart.Video video -> Map.of("type", "video_url", "video_url", Map.of("url", video.url()));
		};
	}

	@Override
	public TextCompletionResult parse(String json) {
		try {
			JsonNode root = mapper.readTree(json);
			JsonNode choices = root.path("choices");
			String content = choices.isArray() && choices.size() > 0
					? ThinkingContentFilter.strip(choices.get(0).path("message").path("content").asText(""))
					: "";
			int inputTokens = DialectUsage.requireInt(root.get("usage"), "prompt_tokens");
			int outputTokens = DialectUsage.requireInt(root.get("usage"), "completion_tokens");
			DialectUsage.assertSumFits(inputTokens, outputTokens);
			String providerRunId = root.path("id").isTextual() ? root.path("id").asText() : null;
			return new TextCompletionResult(content, inputTokens, outputTokens, providerRunId);
		} catch (IntelligenceException e) {
			throw e;
		} catch (Exception e) {
			throw new IntelligenceException(502, "AI provider 返回了无法解析的内容");
		}
	}

	@Override
	public String streamDelta(String data) {
		try {
			JsonNode content = mapper.readTree(data).path("choices").path(0).path("delta").path("content");
			return content.isTextual() && !content.asText().isEmpty() ? content.asText() : null;
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public boolean isStreamEnd(String data) {
		return DONE.equals(data);
	}
}
