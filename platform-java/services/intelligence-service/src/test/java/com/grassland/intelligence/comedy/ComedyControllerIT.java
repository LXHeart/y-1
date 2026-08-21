package com.grassland.intelligence.comedy;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 脱口秀端到端（草场 intelligence Slice 2；GL-P3-AI-001 尾巴已迁执行环）：401 无断言、400 参数非法、 402
 * 积分不足（环内拒绝透传，不流式）、200 SSE（先执行后发帧：content 帧 + 安全帧 + [DONE]， 环入口断言 prompt 与
 * feature，扣退在环内闭环）。执行环出口用 {@link MockitoBean} 隔离。
 */
class ComedyControllerIT extends IntelligenceItSupport {

	@MockitoBean
	private CreditsClient credits;

	@MockitoBean
	private FrozenTextExecutionService frozenText;

	@BeforeEach
	void stubDefaults() {
		reset(credits, frozenText);
		CreditsStubs.stubDefaults(credits);
		db.sql("DELETE FROM creation_generation").then().block();
	}

	private String signed() {
		return sign(UUID.randomUUID().toString(), "recommender");
	}

	@Test
	@DisplayName("无断言 → 401，不进执行环")
	void unauthenticatedRejected() {
		client().post().uri("/api/comedy-generation/generate-script").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "加班", "duration", 60)).exchange().expectStatus().isUnauthorized();
		verify(frozenText, never()).executeIndependent(any(), any(), anyInt(), any(), any());
	}

	@Test
	@DisplayName("题材为空 → 400，不进执行环（校验在环前）")
	void invalidTopicRejected() {
		client().post().uri("/api/comedy-generation/generate-script").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "   ", "duration", 60)).exchange()
				.expectStatus().isBadRequest();
		verify(frozenText, never()).executeIndependent(any(), any(), anyInt(), any(), any());
	}

	@Test
	@DisplayName("时长越界 → 400")
	void invalidDurationRejected() {
		client().post().uri("/api/comedy-generation/generate-script").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "加班", "duration", 10)).exchange()
				.expectStatus().isBadRequest();
	}

	@Test
	@DisplayName("积分不足 → 402（环内拒绝透传），不发 SSE")
	void insufficientCreditsRejected() {
		when(frozenText.executeIndependent(any(), any(), anyInt(), any(), any()))
				.thenReturn(Mono.error(new IntelligenceException(402, "积分不足")));
		client().post().uri("/api/comedy-generation/generate-script").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "加班", "duration", 60)).exchange()
				.expectStatus().isEqualTo(402);
	}

	@Test
	@DisplayName("成功 → 200 SSE：经执行环（COMEDY_GENERATION）+ 喜剧 prompt(duration/wordCount/主题) + 单帧 + 安全帧 + [DONE]")
	void streamsComedyScript() {
		when(frozenText.executeIndependent(any(), any(), anyInt(), any(), any())).thenReturn(
				Mono.just(new FrozenTextExecutionService.Traced<>("【铺垫】【爆点】", null, "qwen", "qwen-plus", 1, false)));

		byte[] body = client().post().uri("/api/comedy-generation/generate-script")
				.header("X-Grassland-Identity", signed()).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "职场加班", "duration", 60)).exchange().expectStatus().isOk().expectHeader()
				.contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).expectHeader()
				.valueEquals("X-Accel-Buffering", "no").expectBody().returnResult().getResponseBody();

		assertThat(new String(body, UTF_8)).isEqualTo(
				"data: {\"content\":\"【铺垫】【爆点】\"}\n\n" + "data: {\"type\":\"safety\",\"safety\":{\"findings\":[],"
						+ "\"lexiconVersion\":\"lexicon-v1\",\"deepCheck\":false," + "\"appliedOverlays\":[]}}\n\n"
						+ "data: [DONE]\n\n");

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor
				.forClass((Class<List<ChatMessage>>) (Class<?>) List.class);
		verify(frozenText).executeIndependent(any(), messagesCaptor.capture(), anyInt(),
				eq(CreditFeature.COMEDY_GENERATION), any());
		// prompt 组装：system 含 duration/wordCount（60×4.5=270），user 含主题
		List<ChatMessage> messages = messagesCaptor.getValue();
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0).role()).isEqualTo("system");
		assertThat(messages.get(0).content()).contains("总时长约 60 秒（约 270 字）");
		assertThat(messages.get(0).content()).doesNotContain("李继刚");
		assertThat(messages.get(1).role()).isEqualTo("user");
		assertThat(messages.get(1).content()).contains("职场加班");
		// 扣退在执行环内闭环，控制器不触达 credits
		verify(credits, never()).consume(any(), any());
	}

	@Test
	@DisplayName("上游失败 → 502 JSON 先于 SSE（退款在环内闭环，不再发 error 帧）")
	void upstreamFailureFailsBeforeSse() {
		when(frozenText.executeIndependent(any(), any(), anyInt(), any(), any()))
				.thenReturn(Mono.error(new IntelligenceException(502, "AI provider 调用失败")));

		client().post().uri("/api/comedy-generation/generate-script").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "加班", "duration", 60)).exchange()
				.expectStatus().isEqualTo(502);
	}
}
