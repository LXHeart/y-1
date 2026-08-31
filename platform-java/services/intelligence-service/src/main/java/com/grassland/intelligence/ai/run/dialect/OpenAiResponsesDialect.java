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
 * OpenAI Responses 方言（{@code POST {base}/responses}，base 形如 {@code https://api.openai.com/v1}）。
 *
 * <p>与同厂 Chat Completions 是**两套形状**，别按名字想当然：
 * <ul>
 * <li>{@code input} 取代 {@code messages}、{@code max_output_tokens} 取代 {@code max_tokens}；</li>
 * <li>正文在 {@code output[].content[]} 里 {@code type=="output_text"} 的片断（{@code output_text}
 * 那个扁平字段是各家 SDK 的便利属性，<b>裸 HTTP 响应里没有</b>，照它取会恒得空串）；</li>
 * <li>usage 字段名是 {@code input_tokens}/{@code output_tokens}；</li>
 * <li>流是<b>带类型的事件</b>（{@code response.output_text.delta}），终止是
 * {@code response.completed}，没有 {@code [DONE]} 哨兵。</li>
 * </ul>
 */
@Component
public final class OpenAiResponsesDialect implements TextDialect {

	static final String NAME = "openai-responses";

	private static final String DELTA_EVENT = "response.output_text.delta";
	private static final String COMPLETED_EVENT = "response.completed";
	private static final String DONE = "[DONE]";

	private final ObjectMapper mapper = new ObjectMapper();

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String path(String model, boolean stream) {
		return "responses";
	}

	@Override
	public void applyAuth(HttpHeaders headers, String bearer) {
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
	}

	@Override
	public Map<String, Object> body(String model, List<ChatMessage> messages, int maxTokens, boolean stream) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", model);
		body.put("input", messages.stream().map(OpenAiResponsesDialect::inputItem).toList());
		body.put("max_output_tokens", maxTokens);
		body.put("stream", stream);
		return body;
	}

	/** 纯文本轮次用 {@code content} 明文（Responses 接受）；多模态用 input_text/input_image 片断数组。 */
	private static Map<String, Object> inputItem(ChatMessage message) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("role", message.role());
		item.put("content",
				message.multimodal()
						? message.parts().stream().map(OpenAiResponsesDialect::partBody).toList()
						: message.content());
		return item;
	}

	private static Map<String, Object> partBody(ContentPart part) {
		return switch (part) {
			case ContentPart.Text text -> Map.of("type", "input_text", "text", text.text());
			case ContentPart.Image image -> Map.of("type", "input_image", "image_url", image.url());
			// Responses 的 input 片断没有视频类型：显式 400，不静默丢片断（丢了模型会对着
			// 「请分析这个视频」凭空编）。视频理解请配 chat/completions 系的多模态模型。
			case ContentPart.Video video ->
				throw new IntelligenceException(400, "openai-responses 方言不支持视频输入，请改用支持视频理解的模型");
		};
	}

	@Override
	public TextCompletionResult parse(String json) {
		try {
			JsonNode root = mapper.readTree(json);
			StringBuilder content = new StringBuilder();
			for (JsonNode item : root.path("output")) {
				for (JsonNode block : item.path("content")) {
					if ("output_text".equals(block.path("type").asText()) && block.path("text").isTextual()) {
						content.append(block.path("text").asText());
					}
				}
			}
			JsonNode usage = root.get("usage");
			int inputTokens = DialectUsage.requireInt(usage, "input_tokens");
			int outputTokens = DialectUsage.requireInt(usage, "output_tokens");
			DialectUsage.assertSumFits(inputTokens, outputTokens);
			String providerRunId = root.path("id").isTextual() ? root.path("id").asText() : null;
			return new TextCompletionResult(
					ThinkingContentFilter.strip(content.toString()), inputTokens, outputTokens, providerRunId);
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
			if (!DELTA_EVENT.equals(event.path("type").asText())) {
				return null;   // response.created / output_item.added / reasoning 等无正文事件
			}
			JsonNode delta = event.path("delta");
			return delta.isTextual() && !delta.asText().isEmpty() ? delta.asText() : null;
		} catch (Exception e) {
			return null;
		}
	}

	@Override
	public boolean isStreamEnd(String data) {
		if (DONE.equals(data)) {
			return true;   // 部分兼容网关仍补发 OpenAI 经典哨兵
		}
		try {
			return COMPLETED_EVENT.equals(mapper.readTree(data).path("type").asText());
		} catch (Exception e) {
			return false;
		}
	}
}
