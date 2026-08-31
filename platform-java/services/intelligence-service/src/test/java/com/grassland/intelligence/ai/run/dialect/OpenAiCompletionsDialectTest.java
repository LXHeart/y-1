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
 * OpenAI Chat Completions 方言。
 *
 * <p>这一份是**历史默认形状的回归护栏**：改造前 {@code TextCompletionClient} 只会说这一种话，
 * {@code qwen} 与 {@code openai-compatible} 走的是逐字节相同的这条路径。所以本文件的断言写得比
 * 其余三家更死（字段名、字段存在性、thinking 双开关），改动即视为破坏兼容网关。
 */
@DisplayName("OpenAiCompletionsDialect")
class OpenAiCompletionsDialectTest {

	private final OpenAiCompletionsDialect dialect = new OpenAiCompletionsDialect();

	@Test
	@DisplayName("名字与路径固定，流式与否同路径")
	void nameAndPath() {
		assertThat(dialect.name()).isEqualTo("openai-completions");
		assertThat(dialect.path("any-model", false)).isEqualTo("chat/completions");
		assertThat(dialect.path("any-model", true)).isEqualTo("chat/completions");
	}

	@Test
	@DisplayName("鉴权是 Authorization: Bearer")
	void appliesBearerAuth() {
		HttpHeaders headers = new HttpHeaders();

		dialect.applyAuth(headers, "sk-secret");

		assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-secret");
	}

	@Test
	@DisplayName("纯文本请求体用 messages/max_tokens，并双开关关闭思考")
	void buildsTextBody() {
		Map<String, Object> body = dialect.body("qwen-plus",
				List.of(ChatMessage.system("你是助手"), ChatMessage.user("你好")), 512, false);

		assertThat(body).containsEntry("model", "qwen-plus")
				.containsEntry("stream", false)
				.containsEntry("max_tokens", 512)
				// enable_thinking 是 Qwen/DashScope 惯例，thinking.type 是 MiniMax-M3 开关，必须并列发出
				.containsEntry("enable_thinking", false)
				.containsEntry("thinking", Map.of("type", "disabled"));
		assertThat(messages(body)).containsExactly(Map.of("role", "system", "content", "你是助手"),
				Map.of("role", "user", "content", "你好"));
	}

	@Test
	@DisplayName("stream=true 只翻请求体里的 stream 开关")
	void buildsStreamingBody() {
		assertThat(dialect.body("m", List.of(ChatMessage.user("hi")), 16, true)).containsEntry("stream", true);
	}

	@Test
	@DisplayName("多模态片断序列化为 text/image_url/video_url")
	void buildsMultimodalBody() {
		Map<String, Object> body = dialect.body("m", List.of(ChatMessage.user(List.of(ContentPart.text("看图"),
				ContentPart.image("data:image/png;base64,AAA"), ContentPart.video("https://cdn.example.com/a.mp4")))),
				64, false);

		assertThat(messages(body)).singleElement()
				.extracting("content")
				.isEqualTo(List.of(Map.of("type", "text", "text", "看图"),
						Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AAA")),
						Map.of("type", "video_url", "video_url",
								Map.of("url", "https://cdn.example.com/a.mp4"))));
	}

	@Test
	@DisplayName("解析取 choices[0].message.content 与 prompt/completion_tokens，并透出顶层 id")
	void parsesResponse() {
		TextCompletionResult result = dialect.parse("""
				{"id":"chatcmpl-1","choices":[{"message":{"content":"结果"}}],
				 "usage":{"prompt_tokens":11,"completion_tokens":22}}
				""");

		assertThat(result.content()).isEqualTo("结果");
		assertThat(result.inputTokens()).isEqualTo(11);
		assertThat(result.outputTokens()).isEqualTo(22);
		assertThat(result.providerRunId()).isEqualTo("chatcmpl-1");
	}

	@Test
	@DisplayName("解析剥离 <think> 段")
	void stripsThinking() {
		TextCompletionResult result = dialect.parse("""
				{"choices":[{"message":{"content":"<think>推理过程</think>正文"}}],
				 "usage":{"prompt_tokens":1,"completion_tokens":1}}
				""");

		assertThat(result.content()).isEqualTo("正文");
	}

	@Test
	@DisplayName("choices 缺失时正文为空串而不抛（usage 齐全即可结算）")
	void toleratesMissingChoices() {
		assertThat(dialect.parse("""
				{"usage":{"prompt_tokens":3,"completion_tokens":0}}
				""").content()).isEmpty();
	}

	/** 计量是结算依据：缺 usage 静默填 0 等于让平台侧免费跑掉真实 token。 */
	@Test
	@DisplayName("缺 usage / usage 非法 → 502")
	void rejectsMissingUsage() {
		assertThatThrownBy(() -> dialect.parse("""
				{"choices":[{"message":{"content":"x"}}]}
				""")).isInstanceOf(IntelligenceException.class).hasMessageContaining("usage");

		assertThatThrownBy(() -> dialect.parse("""
				{"choices":[],"usage":{"prompt_tokens":-1,"completion_tokens":2}}
				""")).isInstanceOf(IntelligenceException.class);
	}

	@Test
	@DisplayName("响应体不是 JSON → 502")
	void rejectsGarbage() {
		assertThatThrownBy(() -> dialect.parse("<html>502 Bad Gateway</html>"))
				.isInstanceOf(IntelligenceException.class);
	}

	@Test
	@DisplayName("流增量取 choices[0].delta.content，空增量/坏帧跳过")
	void readsStreamDelta() {
		assertThat(dialect.streamDelta("""
				{"choices":[{"delta":{"content":"片"}}]}""")).isEqualTo("片");
		assertThat(dialect.streamDelta("""
				{"choices":[{"delta":{}}]}""")).isNull();
		assertThat(dialect.streamDelta("""
				{"choices":[{"delta":{"content":""}}]}""")).isNull();
		assertThat(dialect.streamDelta("not json")).isNull();
	}

	@Test
	@DisplayName("[DONE] 是流终止哨兵")
	void detectsStreamEnd() {
		assertThat(dialect.isStreamEnd("[DONE]")).isTrue();
		assertThat(dialect.isStreamEnd("""
				{"choices":[{"delta":{"content":"x"}}]}""")).isFalse();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> messages(Map<String, Object> body) {
		return (List<Map<String, Object>>) body.get("messages");
	}
}
