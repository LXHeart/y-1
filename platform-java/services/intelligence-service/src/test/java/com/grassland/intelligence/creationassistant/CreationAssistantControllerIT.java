package com.grassland.intelligence.creationassistant;

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
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.credits.InsufficientCreditsException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 智能创作助手集成测试（草场 PRD §4.9.4/§4.9.6 / Slice 15 Stage 2）。复用
 * {@link IntelligenceItSupport} （testcontainers postgres +
 * 真实断言签名）。聚合型流（score/guide/task-coverage/topic-from-hot）已迁 执行环（GL-P3-AI-001
 * 尾巴）：桩 {@link FrozenTextExecutionService} 环出口、在环入口断言 feature 与
 * prompt、扣退在环内闭环（控制器零 credits 触达）；suggest 仍为 legacy 流式（暂保留手写扣退断言）。
 *
 * <p>
 * 锁定：评分（聚合 JSON → 逐维度 SSE 帧）、建议（纯流式）、guide lineage 落环内 runId、 积分不足
 * 402、上游失败、参数校验。
 */
class CreationAssistantControllerIT extends IntelligenceItSupport {

	@MockitoBean
	private AiCapabilityAdapter ai;
	@MockitoBean
	private CreditsClient credits;
	@MockitoBean
	private FrozenTextExecutionService frozenText;

	private String header() {
		return "X-Grassland-Identity";
	}

	@BeforeEach
	void resetMocks() {
		reset(ai, credits, frozenText);
		CreditsStubs.stubDefaults(credits);
		db.sql("DELETE FROM creation_generation").then().block();
	}

	/** 桩执行环出口：把环入口捕获的 transform 应用到给定模型输出（覆盖剥 fence + 解析路径）。 */
	@SuppressWarnings("unchecked")
	private void stubIndependentAppliesTransform(String modelOutput) {
		when(frozenText.executeIndependent(any(), any(), anyInt(), any(), any())).thenAnswer(invocation -> {
			Function<TextCompletionResult, Object> transform = (Function<TextCompletionResult, Object>) invocation
					.getArgument(4);
			return Mono.just(traced(transform.apply(new TextCompletionResult(modelOutput, 10, 5))));
		});
	}

	private static <T> FrozenTextExecutionService.Traced<T> traced(T value) {
		return new FrozenTextExecutionService.Traced<>(value, null, "qwen", "qwen-plus", 1, false);
	}

	// ---------------- score ----------------

	@Test
	void scoreParsesDimensionsAndStreamsFrames() {
		// LLM 返回评分 JSON
		stubIndependentAppliesTransform(
				"{\"dimensions\":[" + "{\"dimension\":\"title_appeal\",\"score\":8,\"advice\":\"标题有吸引力\"},"
						+ "{\"dimension\":\"structure\",\"score\":6,\"advice\":\"开头可更抓人\"}" + "],\"overall\":7}");

		byte[] body = client().post().uri("/api/creation-assistant/score").header(header(), sign("user-score", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("content", "这是一段需要评分的测试内容，至少十个字", "platform", "xiaohongshu")).exchange()
				.expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
				.expectBody().returnResult().getResponseBody();

		String sse = new String(body, UTF_8);
		// 逐维度帧 + overall 帧 + [DONE]
		assertThat(sse).contains("\"type\":\"score\"");
		assertThat(sse).contains("\"dimension\":\"title_appeal\"");
		assertThat(sse).contains("\"dimension\":\"structure\"");
		assertThat(sse).contains("\"type\":\"overall\"");
		assertThat(sse).endsWith("data: [DONE]\n\n");
		// 经执行环且 feature 正确；扣退在环内闭环（控制器不触达 credits）
		verify(frozenText).executeIndependent(any(), any(), anyInt(), eq(CreditFeature.CREATION_ASSISTANT), any());
		verify(credits, never()).consume(any(), any());
		verify(credits, never()).refund(any(), any());
	}

	@Test
	void scoreFailsClosedOnUpstreamFailure() {
		when(frozenText.executeIndependent(any(), any(), anyInt(), any(), any()))
				.thenReturn(Mono.error(new RuntimeException("LLM 不可用")));

		client().post().uri("/api/creation-assistant/score").header(header(), sign("user-fail", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("content", "这是一段需要评分的测试内容，至少十个字")).exchange()
				.expectStatus().is5xxServerError();
	}

	@Test
	void scoreRejectsShortContent() {
		client().post().uri("/api/creation-assistant/score").header(header(), sign("user-short", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("content", "太短")).exchange().expectStatus()
				.is4xxClientError();
		verify(frozenText, never()).executeIndependent(any(), any(), anyInt(), any(), any());
	}

	// ---------------- suggest（legacy 纯流式，暂保留手写扣退）----------------

	@Test
	void suggestStreamsChunksAndChargesCreationAssistant() {
		ArgumentCaptor<CreditFeature> featureCaptor = ArgumentCaptor.forClass(CreditFeature.class);
		when(credits.consume(any(), featureCaptor.capture()))
				.thenAnswer(inv -> CreditsStubs.charge(inv.getArgument(0), inv.getArgument(1)));
		when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk("亮点："), new ChatChunk("开头生动。")));

		byte[] body = client().post().uri("/api/creation-assistant/suggest")
				.header(header(), sign("user-suggest", null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("content", "这是一段需要优化建议的测试内容，至少十个字", "platform", "zhihu")).exchange().expectStatus()
				.isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).expectBody()
				.returnResult().getResponseBody();

		String sse = new String(body, UTF_8);
		assertThat(sse).isEqualTo(
				"data: {\"content\":\"亮点：\"}\n\n" + "data: {\"content\":\"开头生动。\"}\n\n" + "data: [DONE]\n\n");
		assertThat(featureCaptor.getValue()).isEqualTo(CreditFeature.CREATION_ASSISTANT);
	}

	/**
	 * 流中途失败：头已随 200 发出，只能靠错误帧告知客户端（不能 Mono.error 让流裸截断）， 同时仍要退款。镜像
	 * ArticleController 的 outline/content 流。
	 */
	@Test
	void suggestEmitsErrorFrameAndRefundsWhenStreamFailsMidway() {
		when(credits.consume(any(), any()))
				.thenReturn(CreditsStubs.charge("user-midfail", CreditFeature.CREATION_ASSISTANT));
		when(ai.startTextRun(any()))
				.thenReturn(Flux.just(new ChatChunk("亮点：")).concatWith(Flux.error(new RuntimeException("LLM 断流"))));

		byte[] body = client().post().uri("/api/creation-assistant/suggest")
				.header(header(), sign("user-midfail", null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("content", "这是一段需要优化建议的测试内容，至少十个字")).exchange().expectStatus().isOk().expectBody()
				.returnResult().getResponseBody();

		String sse = new String(body, UTF_8);
		assertThat(sse).contains("data: {\"content\":\"亮点：\"}");
		assertThat(sse).contains("data: {\"error\":\"优化建议生成失败\"}");
		verify(credits).refund(any(), any());
	}

	@Test
	void insufficientCreditsReturnsErrorWithoutCallingAi() {
		when(credits.consume(any(), any())).thenReturn(Mono.error(new InsufficientCreditsException()));

		client().post().uri("/api/creation-assistant/suggest").header(header(), sign("user-broke", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("content", "这是一段需要优化建议的测试内容，至少十个字"))
				.exchange().expectStatus().is4xxClientError();

		verify(ai, never()).startTextRun(any());
	}

	@Test
	void requiresAuthentication() {
		client().post().uri("/api/creation-assistant/score").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("content", "这是一段需要评分的测试内容，至少十个字")).exchange().expectStatus().isUnauthorized();
	}

	// ---------------- guide（§4.9.1/§4.9.2；经执行环 + lineage 落环内
	// runId）----------------

	@Test
	void guideAskStreamsQuestionAndRecordsLineageWithRunId() {
		UUID runId = UUID.randomUUID();
		when(frozenText.executeIndependent(any(), any(), anyInt(), any(), any())).thenAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			Function<TextCompletionResult, Object> transform = (Function<TextCompletionResult, Object>) invocation
					.getArgument(4);
			return Mono.just(new FrozenTextExecutionService.Traced<>(
					transform
							.apply(new TextCompletionResult("{\"action\":\"ask\",\"question\":\"你想发布到哪个平台？\"}", 10, 5)),
					runId, "qwen", "qwen-plus", 1, false));
		});

		byte[] body = client().post().uri("/api/creation-assistant/guide").header(header(), sign("user-guide", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("userInput", "想写一篇探店笔记")).exchange()
				.expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
				.expectBody().returnResult().getResponseBody();

		String sse = new String(body, UTF_8);
		assertThat(sse).contains("\"type\":\"ask\"");
		assertThat(sse).contains("你想发布到哪个平台");
		assertThat(sse).endsWith("data: [DONE]\n\n");
		// 任务书 #44 升级：guide lineage 的 runId 不再恒 null，落执行环真值
		String recorded = db
				.sql("SELECT ai_run_id::text AS run_id FROM creation_generation "
						+ "WHERE owner_account_id = :owner AND kind = 'assistant_guide'")
				.bind("owner", "user-guide").map((row, meta) -> row.get("run_id", String.class)).one().block();
		assertThat(recorded).isEqualTo(runId.toString());
		verify(credits, never()).consume(any(), any());
	}

	@Test
	void guideBriefMarksInferredFields() {
		stubIndependentAppliesTransform("{\"action\":\"brief\",\"brief\":{\"angle\":\"探店种草\",\"audience\":\"年轻白领\","
				+ "\"structure\":\"开头钩子+菜品+环境+地址\",\"inferredFields\":[\"audience\",\"style\"]}}");

		byte[] body = client().post().uri("/api/creation-assistant/guide").header(header(), sign("user-brief", null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("userInput", "想写一篇小红书探店笔记", "platform", "xiaohongshu", "history", "已选小红书，主题探店"))
				.exchange().expectStatus().isOk().expectBody().returnResult().getResponseBody();

		String sse = new String(body, UTF_8);
		assertThat(sse).contains("\"type\":\"brief\"");
		assertThat(sse).contains("探店种草");
		// §4.9.2 推测标记：inferredFields 列出 AI 推测的字段
		assertThat(sse).contains("inferredFields");
		assertThat(sse).contains("audience,style");
	}

	@Test
	void guideRejectsEmptyInput() {
		client().post().uri("/api/creation-assistant/guide").header(header(), sign("user-empty", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("userInput", "")).exchange().expectStatus()
				.is4xxClientError();
		verify(frozenText, never()).executeIndependent(any(), any(), anyInt(), any(), any());
	}

	// ---------------- task-coverage（§4.9.3）----------------

	@Test
	void taskCoverageStreamsGapsWhenRequirementsUnmet() {
		stubIndependentAppliesTransform("{\"covered\":false,\"gaps\":["
				+ "{\"requirement\":\"必须提到门店地址\",\"status\":\"missing\",\"hint\":\"结尾加地址\"},"
				+ "{\"requirement\":\"带3张以上配图\",\"status\":\"weak\",\"hint\":\"补图\"}" + "]}");

		byte[] body = client().post().uri("/api/creation-assistant/task-coverage")
				.header(header(), sign("user-cov", null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("content", "这家店不错，菜品新鲜，推荐大家来试试。", "taskRequirements", "必须提到门店地址；带3张以上配图；200字以上"))
				.exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
				.expectBody().returnResult().getResponseBody();

		String sse = new String(body, UTF_8);
		assertThat(sse).contains("\"type\":\"gap\"");
		assertThat(sse).contains("必须提到门店地址");
		assertThat(sse).contains("带3张以上配图");
		assertThat(sse).contains("\"type\":\"covered\"");
		assertThat(sse).contains("\"covered\":false");
		verify(frozenText).executeIndependent(any(), any(), anyInt(), eq(CreditFeature.CREATION_ASSISTANT), any());
	}

	@Test
	void taskCoverageReportsAllCovered() {
		stubIndependentAppliesTransform("{\"covered\":true,\"gaps\":[]}");

		byte[] body = client().post().uri("/api/creation-assistant/task-coverage")
				.header(header(), sign("user-covered", null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("content", "门店在南京路1号，菜品新鲜环境好，推荐大家来试试。", "taskRequirements", "提到门店地址")).exchange()
				.expectStatus().isOk().expectBody().returnResult().getResponseBody();

		String sse = new String(body, UTF_8);
		assertThat(sse).contains("\"covered\":true");
		// 无 gap 帧
		assertThat(sse).doesNotContain("\"type\":\"gap\"");
	}

	@Test
	void taskCoverageRejectsMissingRequirements() {
		client().post().uri("/api/creation-assistant/task-coverage").header(header(), sign("user-noreq", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("content", "这是一段足够长的内容用于测试，至少十个字")).exchange()
				.expectStatus().is4xxClientError();
		verify(frozenText, never()).executeIndependent(any(), any(), anyInt(), any(), any());
	}

	// ---------------- topic-from-hot（§4.9.5）----------------

	@Test
	void topicFromHotStructuresTitleIntoAngleThesisAudience() {
		stubIndependentAppliesTransform("{\"topic\":\"打工人早餐新选择\",\"angle\":\"平价高效\","
				+ "\"thesis\":\"5分钟搞定营养早餐\",\"audience\":\"通勤白领\"," + "\"entryPoints\":[\"时间对比\",\"营养搭配\",\"价格真相\"]}");

		byte[] body = client().post().uri("/api/creation-assistant/topic-from-hot")
				.header(header(), sign("user-hot", null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("hotTitle", "打工人早餐调查", "platform", "xiaohongshu")).exchange().expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).expectBody().returnResult()
				.getResponseBody();

		String sse = new String(body, UTF_8);
		assertThat(sse).contains("\"type\":\"topic\"");
		assertThat(sse).contains("打工人早餐新选择");
		assertThat(sse).contains("通勤白领");
		// entryPoints 是结构化切入点，而非纯字符串 topic
		assertThat(sse).contains("时间对比");
		assertThat(sse).contains("价格真相");
		verify(frozenText).executeIndependent(any(), any(), anyInt(), eq(CreditFeature.CREATION_ASSISTANT), any());
	}

	@Test
	void topicFromHotRejectsEmptyTitle() {
		client().post().uri("/api/creation-assistant/topic-from-hot").header(header(), sign("user-notitle", null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("hotTitle", "")).exchange().expectStatus()
				.is4xxClientError();
		verify(frozenText, never()).executeIndependent(any(), any(), anyInt(), any(), any());
	}
}
