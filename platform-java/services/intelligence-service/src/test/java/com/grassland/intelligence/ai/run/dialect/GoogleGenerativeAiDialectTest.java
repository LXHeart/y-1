package com.grassland.intelligence.ai.run.dialect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * Google Generative AI（Gemini）方言——四家里差异最大的一个：模型名与流式开关编在 URL 里、
 * 鉴权走 x-goog-api-key、assistant 角色叫 model、计量在 usageMetadata、且**流无终止哨兵**。
 */
@DisplayName("GoogleGenerativeAiDialect")
class GoogleGenerativeAiDialectTest {

	private final GoogleGenerativeAiDialect dialect = new GoogleGenerativeAiDialect();

	/** 漏掉 alt=sse 会拿到一个 JSON 数组而不是 SSE 流，所以查询串是路径契约的一部分。 */
	@Test
	@DisplayName("模型名与流式开关编在路径里，流式必带 alt=sse")
	void nameAndPath() {
		assertThat(dialect.name()).isEqualTo("google-generative-ai");
		assertThat(dialect.path("gemini-2.5-pro", false)).isEqualTo("models/gemini-2.5-pro:generateContent");
		assertThat(dialect.path("gemini-2.5-pro", true))
				.isEqualTo("models/gemini-2.5-pro:streamGenerateContent?alt=sse");
	}

	@Test
	@DisplayName("鉴权是 x-goog-api-key，且不发 Bearer")
	void appliesGoogleApiKeyAuth() {
		HttpHeaders headers = new HttpHeaders();

		dialect.applyAuth(headers, "AIza-secret");

		assertThat(headers.getFirst("x-goog-api-key")).isEqualTo("AIza-secret");
		assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isNull();
	}

	/** model 已在 URL 里，请求体重复带会被上游拒；上限字段在 generationConfig 下而非顶层。 */
	@Test
	@DisplayName("请求体用 contents/generationConfig.maxOutputTokens，且不带 model")
	void buildsTextBody() {
		Map<String, Object> body = dialect.body("gemini-2.5-pro", List.of(ChatMessage.user("你好")), 512, false);

		assertThat(body).containsEntry("generationConfig", Map.of("maxOutputTokens", 512))
				.doesNotContainKeys("model", "max_tokens", "maxOutputTokens", "stream");
		assertThat(contents(body))
				.containsExactly(Map.of("role", "user", "parts", List.of(Map.of("text", "你好"))));
	}

	@Test
	@DisplayName("system 走顶层 systemInstruction，assistant 角色改名 model")
	void liftsSystemAndRenamesAssistant() {
		Map<String, Object> body = dialect.body("gemini-2.5-pro", List.of(ChatMessage.system("规则"),
				ChatMessage.user("问"), new ChatMessage("assistant", "答", null)), 256, false);

		assertThat(body).containsEntry("systemInstruction", Map.of("parts", List.of(Map.of("text", "规则"))));
		assertThat(contents(body)).extracting("role").containsExactly("user", "model");
	}

	@Test
	@DisplayName("无 system 消息时不下发 systemInstruction")
	void omitsSystemInstructionWhenAbsent() {
		assertThat(dialect.body("gemini-2.5-pro", List.of(ChatMessage.user("x")), 16, false))
				.doesNotContainKey("systemInstruction");
	}

	@Test
	@DisplayName("data URI 走 inline_data，http(s) 走 file_data")
	void buildsMediaParts() {
		Map<String, Object> body = dialect.body("gemini-2.5-pro",
				List.of(ChatMessage.user(List.of(ContentPart.text("看图"),
						ContentPart.image("data:image/webp;base64,QUJD"),
						ContentPart.image("https://cdn.example.com/a.png")))),
				64, false);

		assertThat(contents(body)).singleElement()
				.extracting("parts")
				.isEqualTo(List.of(Map.of("text", "看图"),
						Map.of("inline_data", Map.of("mime_type", "image/webp", "data", "QUJD")),
						Map.of("file_data",
								Map.of("mime_type", "image/png", "file_uri", "https://cdn.example.com/a.png"))));
	}

	/**
	 * 与另两家不同，Gemini 的视频**原样下传**而非本地 400：上游的拒绝理由（Files API URI / YouTube 链接
	 * 之外都不收）比我们猜的更准。
	 */
	@Test
	@DisplayName("视频片断原样下传为 file_data（不本地拦）")
	void passesVideoThrough() {
		Map<String, Object> body = dialect.body("gemini-2.5-pro",
				List.of(ChatMessage.user(List.of(ContentPart.video("https://cdn.example.com/a.mp4")))), 64, false);

		assertThat(contents(body)).singleElement()
				.extracting("parts")
				.isEqualTo(List.of(Map.of("file_data",
						Map.of("mime_type", "video/mp4", "file_uri", "https://cdn.example.com/a.mp4"))));
	}

	@Test
	@DisplayName("解析拼接 candidates[0].content.parts 的 text，计量取 usageMetadata")
	void parsesResponse() {
		TextCompletionResult result = dialect.parse("""
				{"responseId":"resp-1",
				 "candidates":[{"content":{"parts":[{"text":"前"},{"text":"后"}]}}],
				 "usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":6}}
				""");

		assertThat(result.content()).isEqualTo("前后");
		assertThat(result.inputTokens()).isEqualTo(4);
		assertThat(result.outputTokens()).isEqualTo(6);
		assertThat(result.providerRunId()).isEqualTo("resp-1");
	}

	/** 被安全策略掐断、无候选输出时 Gemini 不回 candidatesTokenCount——这是 optionalInt 的唯一用途。 */
	@Test
	@DisplayName("缺 candidatesTokenCount 记 0（安全策略掐断场景）")
	void toleratesMissingCandidatesTokenCount() {
		TextCompletionResult result = dialect.parse("""
				{"candidates":[{"finishReason":"SAFETY"}],"usageMetadata":{"promptTokenCount":4}}
				""");

		assertThat(result.content()).isEmpty();
		assertThat(result.inputTokens()).isEqualTo(4);
		assertThat(result.outputTokens()).isZero();
	}

	@Test
	@DisplayName("缺 usageMetadata / promptTokenCount → 502")
	void rejectsMissingUsage() {
		assertThatThrownBy(() -> dialect.parse("""
				{"candidates":[{"content":{"parts":[{"text":"x"}]}}]}
				""")).isInstanceOf(IntelligenceException.class).hasMessageContaining("usage");

		assertThatThrownBy(() -> dialect.parse("""
				{"candidates":[],"usageMetadata":{"candidatesTokenCount":2}}
				""")).isInstanceOf(IntelligenceException.class);
	}

	@Test
	@DisplayName("流增量复用候选解析，无正文帧跳过")
	void readsStreamDelta() {
		assertThat(dialect.streamDelta("""
				{"candidates":[{"content":{"parts":[{"text":"片"}]}}]}""")).isEqualTo("片");
		assertThat(dialect.streamDelta("""
				{"candidates":[{"finishReason":"STOP"}]}""")).isNull();
		assertThat(dialect.streamDelta("""
				{"usageMetadata":{"promptTokenCount":1}}""")).isNull();
		assertThat(dialect.streamDelta("not json")).isNull();
	}

	/** 没有 [DONE]、也没有终止事件：流随连接自然结束，客户端不能依赖哨兵收尾。 */
	@Test
	@DisplayName("流无终止哨兵，isStreamEnd 恒 false")
	void hasNoStreamSentinel() {
		assertThat(dialect.isStreamEnd("[DONE]")).isFalse();
		assertThat(dialect.isStreamEnd("""
				{"candidates":[{"finishReason":"STOP"}]}""")).isFalse();
		assertThat(dialect.isStreamEnd("")).isFalse();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> contents(Map<String, Object> body) {
		return (List<Map<String, Object>>) body.get("contents");
	}
}
