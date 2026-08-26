package com.grassland.intelligence.videostudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 视频工坊 BGM 建议端点 IT（任务书 #43 Stage 1；GL-P3-AI-001 尾巴已迁执行环）： 经执行环闭环扣
 * video_studio_bgm（控制器不手动 consume/refund）、余额不足 402、 完成聚合 + 围栏剥离、JSON
 * 结构断言、上游/解析失败 502 固定文案。
 */
class VideoStudioControllerIT extends IntelligenceItSupport {

	private static final String OWNER = "bgm-owner";

	private static final String VALID_JSON = """
			{"moodDirection":{"label":"温暖叙事","reason":"适配探店氛围","referenceStyle":"轻爵士"},
			 "rhythm":[{"timeRange":"0-15s","intensity":2,"suggestion":"安静铺陈"},
			           {"timeRange":"15-60s","intensity":4,"suggestion":"情绪抬升"}],
			 "syncPoints":[{"atSeconds":15,"suggestion":"菜品出场卡点"},
			               {"atSeconds":42,"suggestion":"金句定格"}],
			 "cautions":["注意音乐版权"]}""";

	@MockitoBean
	private CreditsClient credits;

	@MockitoBean
	private RoutedTextCompletionService ai;

	@MockitoBean
	private FrozenTextExecutionService frozenText;

	@BeforeEach
	void setUp() {
		reset(credits, ai, frozenText);
	}

	/** 桩执行环出口：把环入口捕获的 transform 应用到给定模型输出。 */
	@SuppressWarnings("unchecked")
	private void stubIndependentAppliesTransform(String modelOutput) {
		when(frozenText.executeIndependent(any(), any(), anyInt(), eq(CreditFeature.VIDEO_STUDIO_BGM), any()))
				.thenAnswer(invocation -> {
					Function<TextCompletionResult, Object> transform = (Function<TextCompletionResult, Object>) invocation
							.getArgument(4);
					try {
						return Mono.just(traced(transform.apply(new TextCompletionResult(modelOutput, 10, 5))));
					} catch (RuntimeException error) {
						return Mono.error(error);
					}
				});
	}

	private static <T> FrozenTextExecutionService.Traced<T> traced(T value) {
		return new FrozenTextExecutionService.Traced<>(value, null, "qwen", "qwen-plus", 1, false);
	}

	@Test
	void adviceChargesOneCreditAndParsesStructuredJson() {
		stubIndependentAppliesTransform(VALID_JSON);

		post(OWNER, Map.of("platform", "douyin", "contentForm", "口播", "topic", "秋日探店", "durationSeconds", 60))
				.expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true)
				.jsonPath("$.data.moodDirection.label").isEqualTo("温暖叙事")
				.jsonPath("$.data.moodDirection.referenceStyle").isEqualTo("轻爵士").jsonPath("$.data.rhythm.length()")
				.isEqualTo(2).jsonPath("$.data.rhythm[1].intensity").isEqualTo(4)
				.jsonPath("$.data.syncPoints[0].atSeconds").isEqualTo(15.0).jsonPath("$.data.cautions[0]")
				.isEqualTo("注意音乐版权");

		verify(frozenText).executeIndependent(any(), any(), anyInt(), eq(CreditFeature.VIDEO_STUDIO_BGM), any());
		// 计费/退款在执行环内闭环，控制器不再触达 credits
		verifyNoInteractions(credits);
	}

	@Test
	void codeFenceIsStrippedBeforeParsing() {
		stubIndependentAppliesTransform("```json\n" + VALID_JSON + "\n```");

		post(OWNER, Map.of("platform", "dianping", "contentForm", "探店", "topic", "秋日探店", "durationSeconds", 90))
				.expectStatus().isOk().expectBody().jsonPath("$.data.moodDirection.label").isEqualTo("温暖叙事");
	}

	@Test
	void authenticationIsRequired() {
		client().post().uri("/api/video-studio/bgm-advice").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("platform", "douyin", "contentForm", "口播", "topic", "x", "durationSeconds", 60))
				.exchange().expectStatus().isUnauthorized();
		verifyNoInteractions(credits, ai, frozenText);
	}

	@Test
	void payloadOutsideWhitelistIsRejectedBeforeCharging() {
		// 白名单是中文内容形式（口播/剧情/…）：英文 id 一律 400，且不进执行环
		post(OWNER, Map.of("platform", "douyin", "contentForm", "talking-head", "topic", "秋日探店", "durationSeconds", 60))
				.expectStatus().isBadRequest();
		post(OWNER, Map.of("platform", "bilibili", "contentForm", "口播", "topic", "秋日探店", "durationSeconds", 60))
				.expectStatus().isBadRequest();
		post(OWNER, Map.of("platform", "douyin", "contentForm", "口播", "topic", "秋日探店", "durationSeconds", 601))
				.expectStatus().isBadRequest();
		verifyNoInteractions(credits, ai, frozenText);
	}

	@Test
	void insufficientCreditsReturnsPaymentRequiredEnvelope() {
		when(frozenText.executeIndependent(any(), any(), anyInt(), eq(CreditFeature.VIDEO_STUDIO_BGM), any()))
				.thenReturn(Mono.error(new IntelligenceException(402, "积分不足")));

		post(OWNER, Map.of("platform", "douyin", "contentForm", "口播", "topic", "秋日探店", "durationSeconds", 60))
				.expectStatus().isEqualTo(402).expectBody().jsonPath("$.success").isEqualTo(false).jsonPath("$.error")
				.isEqualTo("积分不足");
		verifyNoInteractions(ai);
	}

	@Test
	void unparseableOutputReturns502() {
		stubIndependentAppliesTransform("今天天气不错");

		String response = post(OWNER,
				Map.of("platform", "douyin", "contentForm", "口播", "topic", "秋日探店", "durationSeconds", 60))
				.expectStatus().isEqualTo(502).expectBody(String.class).returnResult().getResponseBody();

		assertThat(response).contains("建议生成失败");
	}

	@Test
	void upstreamFailureReturnsSanitized502() {
		when(frozenText.executeIndependent(any(), any(), anyInt(), eq(CreditFeature.VIDEO_STUDIO_BGM), any()))
				.thenReturn(Mono.error(new IllegalStateException("provider-secret-detail")));

		String response = post(OWNER,
				Map.of("platform", "douyin", "contentForm", "口播", "topic", "秋日探店", "durationSeconds", 60))
				.expectStatus().isEqualTo(502).expectBody(String.class).returnResult().getResponseBody();

		assertThat(response).doesNotContain("provider-secret-detail");
		// 退款在执行环内闭环，控制器不手动 refund
		verify(credits, never()).refund(any(CreditCharge.class), anyString());
	}

	private WebTestClient.ResponseSpec post(String accountId, Map<String, Object> body) {
		return client().post().uri("/api/video-studio/bgm-advice").header("X-Grassland-Identity", sign(accountId, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange();
	}
}
