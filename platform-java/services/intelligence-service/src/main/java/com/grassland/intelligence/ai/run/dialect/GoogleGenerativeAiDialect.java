package com.grassland.intelligence.ai.run.dialect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.ThinkingContentFilter;
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
 * Google Generative AI（Gemini）方言，base 形如 {@code https://generativelanguage.googleapis.com/v1beta}。
 *
 * <p>四家里差异最大的一个：
 * <ul>
 * <li><b>模型名与流式开关编在 URL 里</b>：{@code models/{model}:generateContent} /
 * {@code models/{model}:streamGenerateContent?alt=sse}，不在请求体（漏掉 {@code alt=sse} 会
 * 得到一个 JSON 数组而非 SSE 流）；</li>
 * <li>鉴权是 {@code x-goog-api-key}，不是 Bearer；</li>
 * <li>消息是 {@code contents[{role,parts[]}]}，assistant 角色叫 <b>model</b>；system 走顶层
 * {@code systemInstruction}；上限字段是 {@code generationConfig.maxOutputTokens}；</li>
 * <li>计量在 {@code usageMetadata.promptTokenCount/candidatesTokenCount}；</li>
 * <li><b>流无终止哨兵</b>——没有 {@code [DONE]} 也没有终止事件，流随连接自然结束。</li>
 * </ul>
 */
@Component
public final class GoogleGenerativeAiDialect implements TextDialect {

	static final String NAME = "google-generative-ai";

	private static final String ROLE_SYSTEM = "system";
	private static final String ROLE_ASSISTANT = "assistant";
	private static final String ROLE_MODEL = "model";
	private static final String ROLE_USER = "user";

	/** {@code data:image/png;base64,xxxx} → (mime_type, data)。 */
	private static final Pattern DATA_URI = Pattern.compile("^data:([^;,]+);base64,(.+)$", Pattern.DOTALL);

	private final ObjectMapper mapper = new ObjectMapper();

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public String path(String model, boolean stream) {
		return stream
				? "models/" + model + ":streamGenerateContent?alt=sse"
				: "models/" + model + ":generateContent";
	}

	@Override
	public void applyAuth(HttpHeaders headers, String bearer) {
		headers.set("x-goog-api-key", bearer);
	}

	@Override
	public Map<String, Object> body(String model, List<ChatMessage> messages, int maxTokens, boolean stream) {
		List<Map<String, Object>> contents = new ArrayList<>();
		List<Map<String, Object>> systemParts = new ArrayList<>();
		for (ChatMessage message : messages) {
			if (ROLE_SYSTEM.equals(message.role())) {
				systemParts.addAll(partsOf(message));
				continue;
			}
			Map<String, Object> turn = new LinkedHashMap<>();
			turn.put("role", ROLE_ASSISTANT.equals(message.role()) ? ROLE_MODEL : ROLE_USER);
			turn.put("parts", partsOf(message));
			contents.add(turn);
		}
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("contents", contents);
		if (!systemParts.isEmpty()) {
			body.put("systemInstruction", Map.of("parts", systemParts));
		}
		// model 已在 URL 路径里，请求体不再重复带。
		body.put("generationConfig", Map.of("maxOutputTokens", maxTokens));
		return body;
	}

	private static List<Map<String, Object>> partsOf(ChatMessage message) {
		if (!message.multimodal()) {
			return List.of(Map.of("text", message.content()));
		}
		return message.parts().stream().map(GoogleGenerativeAiDialect::partBody).toList();
	}

	private static Map<String, Object> partBody(ContentPart part) {
		return switch (part) {
			case ContentPart.Text text -> Map.of("text", text.text());
			case ContentPart.Image image -> mediaBody(image.url(), "image/png");
			// Gemini 的 file_data 只接受 Files API URI 与 YouTube 链接，任意公网视频地址会被上游拒。
			// 仍原样下传而不在此处拦：上游的拒绝理由比我们猜的更准，且静默丢片断会让模型对着
			// 「请分析这个视频」凭空编造。
			case ContentPart.Video video -> mediaBody(video.url(), "video/mp4");
		};
	}

	/** data URI → inline_data（内联 base64）；http(s) → file_data（上游按 URI 拉取）。 */
	private static Map<String, Object> mediaBody(String url, String fallbackMimeType) {
		Matcher dataUri = DATA_URI.matcher(url);
		if (dataUri.matches()) {
			return Map.of("inline_data", Map.of("mime_type", dataUri.group(1), "data", dataUri.group(2)));
		}
		return Map.of("file_data", Map.of("mime_type", fallbackMimeType, "file_uri", url));
	}

	@Override
	public TextCompletionResult parse(String json) {
		try {
			JsonNode root = mapper.readTree(json);
			String content = textOfCandidates(root);
			JsonNode usage = root.get("usageMetadata");
			int inputTokens = DialectUsage.requireInt(usage, "promptTokenCount");
			// 被安全策略掐断、无候选输出时 Gemini 不回 candidatesTokenCount。
			int outputTokens = DialectUsage.optionalInt(usage, "candidatesTokenCount");
			DialectUsage.assertSumFits(inputTokens, outputTokens);
			String providerRunId = root.path("responseId").isTextual() ? root.path("responseId").asText() : null;
			return new TextCompletionResult(
					ThinkingContentFilter.strip(content), inputTokens, outputTokens, providerRunId);
		} catch (IntelligenceException e) {
			throw e;
		} catch (Exception e) {
			throw new IntelligenceException(502, "AI provider 返回了无法解析的内容");
		}
	}

	private static String textOfCandidates(JsonNode root) {
		StringBuilder content = new StringBuilder();
		for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
			if (part.path("text").isTextual()) {
				content.append(part.path("text").asText());
			}
		}
		return content.toString();
	}

	@Override
	public String streamDelta(String data) {
		try {
			String text = textOfCandidates(mapper.readTree(data));
			return text.isEmpty() ? null : text;
		} catch (Exception e) {
			return null;
		}
	}

	/** Gemini 流无终止哨兵：连接结束即流结束，此处恒 false。 */
	@Override
	public boolean isStreamEnd(String data) {
		return false;
	}
}
