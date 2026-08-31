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
 * Anthropic Messages 方言。四处硬差异各有断言：x-api-key + anthropic-version 鉴权、system 上提为顶层字段、
 * max_tokens 必填、图片用 source 块。
 */
@DisplayName("AnthropicMessagesDialect")
class AnthropicMessagesDialectTest {

	private final AnthropicMessagesDialect dialect = new AnthropicMessagesDialect();

	@Test
	@DisplayName("路径是 messages")
	void nameAndPath() {
		assertThat(dialect.name()).isEqualTo("anthropic-messages");
		assertThat(dialect.path("claude-x", false)).isEqualTo("messages");
		assertThat(dialect.path("claude-x", true)).isEqualTo("messages");
	}

	/** anthropic-version 缺失即 400，所以它和 x-api-key 一样是硬要求；Bearer 头必须不出现。 */
	@Test
	@DisplayName("鉴权是 x-api-key + anthropic-version，且不发 Bearer")
	void appliesApiKeyAuth() {
		HttpHeaders headers = new HttpHeaders();

		dialect.applyAuth(headers, "sk-ant-secret");

		assertThat(headers.getFirst("x-api-key")).isEqualTo("sk-ant-secret");
		assertThat(headers.getFirst("anthropic-version")).isEqualTo("2023-06-01");
		assertThat(headers.getFirst(HttpHeaders.AUTHORIZATION)).isNull();
	}

	/**
	 * system 留在 messages 里会被上游 400。多条 system 用空行拼接，顺序保持原样。
	 */
	@Test
	@DisplayName("system 上提为顶层字段并拼接，messages 里只剩对话轮次")
	void liftsSystemToTopLevel() {
		Map<String, Object> body = dialect.body("claude-x", List.of(ChatMessage.system("规则一"),
				ChatMessage.system("规则二"), ChatMessage.user("你好")), 512, false);

		assertThat(body).containsEntry("system", "规则一\n\n规则二").containsEntry("max_tokens", 512);
		assertThat(turns(body)).containsExactly(
				Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", "你好"))));
	}

	@Test
	@DisplayName("无 system 消息时不下发 system 字段")
	void omitsSystemWhenAbsent() {
		assertThat(dialect.body("claude-x", List.of(ChatMessage.user("你好")), 16, false))
				.doesNotContainKey("system");
	}

	/** 纯文本轮次也要包成 content 块数组——Anthropic 的 content 接受字符串，但块数组是两种输入的统一形状。 */
	@Test
	@DisplayName("assistant 角色保留，其余非 user 角色归一到 user")
	void normalisesRoles() {
		Map<String, Object> body = dialect.body("claude-x",
				List.of(ChatMessage.user("问"), new ChatMessage("assistant", "答", null),
						new ChatMessage("tool", "意外角色", null)),
				16, false);

		assertThat(turns(body)).extracting("role").containsExactly("user", "assistant", "user");
	}

	@Test
	@DisplayName("stream=false 时不下发 stream 字段，true 时下发")
	void streamFlagIsOptional() {
		assertThat(dialect.body("claude-x", List.of(ChatMessage.user("x")), 16, false)).doesNotContainKey("stream");
		assertThat(dialect.body("claude-x", List.of(ChatMessage.user("x")), 16, true)).containsEntry("stream", true);
	}

	@Test
	@DisplayName("data URI 图片拆成 source{type=base64,media_type,data}")
	void buildsBase64ImageBody() {
		Map<String, Object> body = dialect.body("claude-x", List.of(ChatMessage
				.user(List.of(ContentPart.text("看图"), ContentPart.image("data:image/jpeg;base64,QUJD")))), 64, false);

		assertThat(turns(body)).singleElement()
				.extracting("content")
				.isEqualTo(List.of(Map.of("type", "text", "text", "看图"),
						Map.of("type", "image", "source",
								Map.of("type", "base64", "media_type", "image/jpeg", "data", "QUJD"))));
	}

	@Test
	@DisplayName("http(s) 图片走 source{type=url}")
	void buildsUrlImageBody() {
		Map<String, Object> body = dialect.body("claude-x",
				List.of(ChatMessage.user(List.of(ContentPart.image("https://cdn.example.com/a.png")))), 64, false);

		assertThat(turns(body)).singleElement()
				.extracting("content")
				.isEqualTo(List.of(Map.of("type", "image", "source",
						Map.of("type", "url", "url", "https://cdn.example.com/a.png"))));
	}

	/** Anthropic 无视频理解端点能力：显式 400，不静默丢片断。 */
	@Test
	@DisplayName("视频片断 → 400")
	void rejectsVideoParts() {
		assertThatThrownBy(() -> dialect.body("claude-x",
				List.of(ChatMessage.user(List.of(ContentPart.video("https://cdn.example.com/a.mp4")))), 64, false))
				.isInstanceOf(IntelligenceException.class)
				.hasMessageContaining("anthropic-messages");
	}

	@Test
	@DisplayName("解析拼接 content 里的 text 块，忽略 thinking 块")
	void parsesResponse() {
		TextCompletionResult result = dialect.parse("""
				{"id":"msg_1",
				 "content":[{"type":"thinking","thinking":"不要"},
				            {"type":"text","text":"前"},{"type":"text","text":"后"}],
				 "usage":{"input_tokens":5,"output_tokens":8}}
				""");

		assertThat(result.content()).isEqualTo("前后");
		assertThat(result.inputTokens()).isEqualTo(5);
		assertThat(result.outputTokens()).isEqualTo(8);
		assertThat(result.providerRunId()).isEqualTo("msg_1");
	}

	@Test
	@DisplayName("缺 usage → 502")
	void rejectsMissingUsage() {
		assertThatThrownBy(() -> dialect.parse("""
				{"content":[{"type":"text","text":"x"}]}
				""")).isInstanceOf(IntelligenceException.class).hasMessageContaining("usage");
	}

	@Test
	@DisplayName("流增量只认 content_block_delta，其余事件跳过")
	void readsStreamDelta() {
		assertThat(dialect.streamDelta("""
				{"type":"content_block_delta","delta":{"type":"text_delta","text":"片"}}""")).isEqualTo("片");
		assertThat(dialect.streamDelta("""
				{"type":"message_start","message":{"id":"msg_1"}}""")).isNull();
		assertThat(dialect.streamDelta("""
				{"type":"ping"}""")).isNull();
		assertThat(dialect.streamDelta("""
				{"type":"content_block_delta","delta":{"type":"thinking_delta","thinking":"推理"}}""")).isNull();
		assertThat(dialect.streamDelta("not json")).isNull();
	}

	@Test
	@DisplayName("终止是 message_stop 事件，不是 [DONE]")
	void detectsStreamEnd() {
		assertThat(dialect.isStreamEnd("""
				{"type":"message_stop"}""")).isTrue();
		assertThat(dialect.isStreamEnd("[DONE]")).isFalse();
		assertThat(dialect.isStreamEnd("""
				{"type":"content_block_stop","index":0}""")).isFalse();
		assertThat(dialect.isStreamEnd("not json")).isFalse();
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> turns(Map<String, Object> body) {
		return (List<Map<String, Object>>) body.get("messages");
	}
}
