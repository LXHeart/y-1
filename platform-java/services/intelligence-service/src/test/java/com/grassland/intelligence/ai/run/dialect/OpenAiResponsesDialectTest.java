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
 * OpenAI Responses 方言。与同厂 Chat Completions 是两套形状，本文件逐条钉住那些「按名字想当然会写错」
 * 的差异：{@code input} / {@code max_output_tokens} / {@code output[].content[].output_text} /
 * {@code input_tokens} / 带类型事件流。
 */
@DisplayName("OpenAiResponsesDialect")
class OpenAiResponsesDialectTest {

	private final OpenAiResponsesDialect dialect = new OpenAiResponsesDialect();

	@Test
	@DisplayName("路径是 responses（不是 chat/completions）")
	void nameAndPath() {
		assertThat(dialect.name()).isEqualTo("openai-responses");
		assertThat(dialect.path("gpt-5", false)).isEqualTo("responses");
		assertThat(dialect.path("gpt-5", true)).isEqualTo("responses");
	}

	@Test
	@DisplayName("鉴权与 Chat Completions 同为 Bearer")
	void appliesBearerAuth() {
		HttpHeaders headers = new HttpHeaders();

		dialect.applyAuth(headers, "sk-secret");

		assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer sk-secret");
	}

	@Test
	@DisplayName("请求体用 input/max_output_tokens，且不含 messages/max_tokens")
	void buildsTextBody() {
		Map<String, Object> body = dialect.body("gpt-5",
				List.of(ChatMessage.system("你是助手"), ChatMessage.user("你好")), 512, false);

		assertThat(body).containsEntry("model", "gpt-5")
				.containsEntry("max_output_tokens", 512)
				.containsEntry("stream", false)
				.doesNotContainKeys("messages", "max_tokens");
		assertThat(input(body)).containsExactly(Map.of("role", "system", "content", "你是助手"),
				Map.of("role", "user", "content", "你好"));
	}

	@Test
	@DisplayName("多模态片断是 input_text/input_image（image_url 为扁平字符串）")
	void buildsMultimodalBody() {
		Map<String, Object> body = dialect.body("gpt-5", List.of(ChatMessage
				.user(List.of(ContentPart.text("看图"), ContentPart.image("https://cdn.example.com/a.png")))), 64,
				false);

		assertThat(input(body)).singleElement()
				.extracting("content")
				.isEqualTo(List.of(Map.of("type", "input_text", "text", "看图"),
						Map.of("type", "input_image", "image_url", "https://cdn.example.com/a.png")));
	}

	/**
	 * 视频显式 400 而非静默丢片断：丢了以后模型会对着「请分析这个视频」凭空编造一段。
	 * 这也是唯一会在 body() 阶段抛的分支——客户端必须把 body 构造放在 Mono.fromCallable/Flux.defer 里，
	 * 否则装配期抛出会绕过 onError 通道。
	 */
	@Test
	@DisplayName("视频片断 → 400（Responses 的 input 无视频类型）")
	void rejectsVideoParts() {
		assertThatThrownBy(() -> dialect.body("gpt-5",
				List.of(ChatMessage.user(List.of(ContentPart.video("https://cdn.example.com/a.mp4")))), 64, false))
				.isInstanceOf(IntelligenceException.class)
				.hasMessageContaining("openai-responses");
	}

	/**
	 * 正文只在 {@code output[].content[]} 里 {@code type=="output_text"} 的片断。各家 SDK 暴露的那个扁平
	 * {@code output_text} 便利属性在裸 HTTP 响应里不存在——照它取会恒得空串，故意在夹具里放一个同名
	 * 顶层字段确认解析没去读它。
	 */
	@Test
	@DisplayName("解析拼接 output_text 片断，忽略 reasoning 与顶层便利字段")
	void parsesResponse() {
		TextCompletionResult result = dialect.parse("""
				{"id":"resp_1","output_text":"绝不能读这个",
				 "output":[{"type":"reasoning","content":[{"type":"reasoning_text","text":"不要"}]},
				           {"type":"message","content":[{"type":"output_text","text":"前"},
				                                        {"type":"output_text","text":"后"}]}],
				 "usage":{"input_tokens":7,"output_tokens":9}}
				""");

		assertThat(result.content()).isEqualTo("前后");
		assertThat(result.inputTokens()).isEqualTo(7);
		assertThat(result.outputTokens()).isEqualTo(9);
		assertThat(result.providerRunId()).isEqualTo("resp_1");
	}

	@Test
	@DisplayName("usage 字段名是 input_tokens/output_tokens，缺失 → 502")
	void rejectsChatCompletionsUsageShape() {
		assertThatThrownBy(() -> dialect.parse("""
				{"output":[],"usage":{"prompt_tokens":1,"completion_tokens":2}}
				""")).isInstanceOf(IntelligenceException.class).hasMessageContaining("usage");
	}

	@Test
	@DisplayName("解析剥离 <think> 段")
	void stripsThinking() {
		assertThat(dialect.parse("""
				{"output":[{"content":[{"type":"output_text","text":"<think>推理</think>正文"}]}],
				 "usage":{"input_tokens":1,"output_tokens":1}}
				""").content()).isEqualTo("正文");
	}

	@Test
	@DisplayName("流增量只认 response.output_text.delta 事件")
	void readsStreamDelta() {
		assertThat(dialect.streamDelta("""
				{"type":"response.output_text.delta","delta":"片"}""")).isEqualTo("片");
		// reasoning 增量不能混进正文
		assertThat(dialect.streamDelta("""
				{"type":"response.reasoning_summary_text.delta","delta":"推理"}""")).isNull();
		assertThat(dialect.streamDelta("""
				{"type":"response.created"}""")).isNull();
		assertThat(dialect.streamDelta("""
				{"type":"response.output_text.delta","delta":""}""")).isNull();
		assertThat(dialect.streamDelta("not json")).isNull();
	}

	@Test
	@DisplayName("终止是 response.completed；兼容网关补发的 [DONE] 也认")
	void detectsStreamEnd() {
		assertThat(dialect.isStreamEnd("""
				{"type":"response.completed"}""")).isTrue();
		assertThat(dialect.isStreamEnd("[DONE]")).isTrue();
		assertThat(dialect.isStreamEnd("""
				{"type":"response.output_text.delta","delta":"x"}""")).isFalse();
		assertThat(dialect.isStreamEnd("not json")).isFalse();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> input(Map<String, Object> body) {
		return (List<Map<String, Object>>) body.get("input");
	}
}
