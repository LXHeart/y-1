package com.grassland.intelligence.cardseries;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.articleimage.GeneratedImage;
import com.grassland.intelligence.articleimage.GeneratedImageStore;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.storage.ObjectStorageAdapter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 系列图卡端到端（任务书 #54）：计划 SSE（桩执行环出口、断言 CARD_SERIES_PLAN 与模板描述词入 prompt）；
 * 逐卡生成走真执行环（预算闸/ai_run/结算）+ 桩图像客户端与受管 store（media 登记/审核送审链真实）； 持久化全链（永久 key +
 * 幂等 + 归属校验）。
 */
@DisplayName("系列 AI 图卡（任务书 #54）")
class CardSeriesIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "54545454-5454-5454-5454-545454545454";
	private static final String OTHER = "55555555-5555-5555-5555-555555555555";
	private static final String PNG_B64 = Base64.getEncoder()
			.encodeToString(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3});

	@MockitoBean
	private CreditsClient credits;
	@MockitoBean
	private FrozenTextExecutionService frozenText;
	@MockitoBean
	private com.grassland.intelligence.articleimage.ImageGenerationClient generation;
	@MockitoBean
	private com.grassland.intelligence.articleimage.TaskImageGenerationService taskGeneration;
	@MockitoBean
	private GeneratedImageStore generatedStore;
	@MockitoBean
	private ObjectStorageAdapter storage;

	@Autowired
	private CardSeriesService service;

	@BeforeEach
	void resetMocks() {
		reset(credits, frozenText, generation, taskGeneration, generatedStore, storage);
		CreditsStubs.stubDefaults(credits);
		// 受管 store：media 登记链真实触发（TTL 行 + 异步送审由审核服务自身 gate）
		when(generatedStore.store(anyString())).thenAnswer(invocation -> {
			String id = UUID.randomUUID().toString();
			return Mono.just(new GeneratedImageStore.StoredRef(id, "article-generated/" + id + ".png", true));
		});
		when(generatedStore.find(anyString()))
				.thenReturn(Mono.just(new GeneratedImageStore.StoredImage(PNG_B64.getBytes())));
		for (String table : new String[]{"card_series_operation", "creation_generation", "media_reference",
				"ai_credit_compensation", "ai_run", "ai_model_budget", "intelligence_outbox"}) {
			db.sql("DELETE FROM " + table).then().block();
		}
		// 任务书 #58：图卡出图走控制面 image_generation 行（静态 env 回落已删）
		seedPlatformImageGenerationModel();
	}

	@Test
	@DisplayName("无断言 → 401（plan 与 generate）")
	void unauthenticatedRejected() {
		client().post().uri("/api/card-series/plan").contentType(MediaType.APPLICATION_JSON).bodyValue(planBody(3))
				.exchange().expectStatus().isUnauthorized();
		client().post().uri("/api/card-series/generate").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(generateBody(1)).exchange().expectStatus().isUnauthorized();
	}

	@Test
	@DisplayName("计划校验：内容缺失/卡片数超限/风格缺失 → 400，不进执行环")
	void planValidationRejected() {
		client().post().uri("/api/card-series/plan").header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(planBody(10)).exchange().expectStatus()
				.isBadRequest();
		client().post().uri("/api/card-series/plan").header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("platform", "xiaohongshu", "content", "",
						"cardCount", 3, "styleText", "", "layoutText", "清单"))
				.exchange().expectStatus().isBadRequest();
		client().post().uri("/api/card-series/plan").header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("platform", "xiaohongshu", "cardCount", 3, "styleText", "极简", "layoutText", "清单"))
				.exchange().expectStatus().isBadRequest();
		verify(frozenText, never()).executeIndependent(any(), any(), anyInt(), any(), any(), any());
	}

	@Test
	@DisplayName("计划成功 → SSE progress/result + 模板描述词与 CARD_SERIES_PLAN 进执行环")
	void streamsPlanResult() {
		ArgumentCaptor<List<com.grassland.intelligence.ai.ChatMessage>> msgCaptor = ArgumentCaptor
				.forClass((Class) List.class);
		when(frozenText.executeIndependent(any(), msgCaptor.capture(), anyInt(),
				org.mockito.ArgumentMatchers.eq(CreditFeature.CARD_SERIES_PLAN), any(), any()))
				.thenReturn(Mono.just(traced(new CardSeriesService.CardSeriesPlan(
						List.of(new CardSeriesService.CardPlan("封面：开业福利", List.of("全场 8 折"), "门头插画", "开业啦"),
								new CardSeriesService.CardPlan("招牌菜", List.of("镇店烤鱼"), "菜品特写", "必点"),
								new CardSeriesService.CardPlan("地址", List.of("地铁 2 号线"), "地图示意", "来店"))))));

		client().post().uri("/api/card-series/plan").header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(planBody(3)).exchange().expectStatus().isOk()
				.expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).expectBody(String.class)
				.value(body -> {
					assertThat(body).contains("\"type\":\"progress\"");
					assertThat(body).contains("\"type\":\"result\"");
					assertThat(body).contains("封面：开业福利");
					assertThat(body).contains("镇店烤鱼");
					assertThat(body).contains("data: [DONE]");
				});

		assertThat(msgCaptor.getValue()).hasSize(2);
		assertThat(msgCaptor.getValue().get(0).content()).contains("可爱清新风格").contains("清单布局").contains("\"cards\"");
		assertThat(msgCaptor.getValue().get(1).content()).contains("新店开业，全场八折").contains("拆解为卡片计划");
	}

	@Test
	@DisplayName("计划积分不足 → 402 JSON 先于 SSE")
	void planInsufficientCredits() {
		when(frozenText.executeIndependent(any(), any(), anyInt(), any(), any(), any()))
				.thenReturn(Mono.error(new com.grassland.intelligence.security.IntelligenceException(402, "积分不足")));
		client().post().uri("/api/card-series/plan").header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(planBody(3)).exchange().expectStatus()
				.isEqualTo(402);
	}

	@Test
	@DisplayName("逐卡生成：3 卡成功，每卡独立 ai_run，lineage kind=card_series，首卡风格锚注入后续 prompt")
	void generatesCardsWithAnchorAndLineage() {
		AtomicInteger seq = new AtomicInteger();
		when(generation.generate(anyString(), anyString(), any(), any())).thenAnswer(invocation -> Mono
				.just(new GeneratedImage(null, PNG_B64, seq.incrementAndGet() == 1 ? "首图风格锚" : "后续")));

		Map<String, Object> body = client().post().uri("/api/card-series/generate")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(generateBody(3)).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> cards = (List<Map<String, Object>>) ((Map<?, ?>) body.get("data")).get("cards");
		assertThat(cards).hasSize(3).allSatisfy(card -> assertThat(card.get("ok")).isEqualTo(true));
		assertThat((String) cards.get(0).get("url")).startsWith("/api/article-generation/generated-images/");

		ArgumentCaptor<String> prompts = ArgumentCaptor.forClass(String.class);
		verify(generation, times(3)).generate(prompts.capture(), anyString(), any(), any());
		assertThat(prompts.getAllValues().get(0)).doesNotContain("首图风格锚");
		assertThat(prompts.getAllValues().get(1)).contains("首图风格锚");
		assertThat(prompts.getAllValues().get(2)).contains("首图风格锚");
		// 2026-09-02 文字策略改版：标题/要点绘制进画面（引号精确引用 + 排版约束），不再是禁字底图
		assertThat(prompts.getAllValues().get(0)).contains("绘制以下文字").contains("\"卡片 1\"");
		assertThat(prompts.getAllValues().get(0)).contains("只出现一次").doesNotContain("不得出现任何文字");

		Long runCount = db.sql("SELECT count(*) AS c FROM ai_run WHERE status='completed'")
				.map(row -> row.get("c", Long.class)).one().block();
		assertThat(runCount).isEqualTo(3L);
		Long mediaCount = db
				.sql("SELECT count(*) AS c FROM media_reference WHERE purpose='card_series' AND expires_at IS NOT NULL")
				.map(row -> row.get("c", Long.class)).one().block();
		assertThat(mediaCount).isEqualTo(3L);
		Map<String, Object> lineage = db.sql("""
				SELECT kind, mode, resolution, provider,
				       coalesce(array_length(result_media_ids, 1), 0) AS media_count,
				       result->>'okCount' AS ok_count
				FROM creation_generation WHERE kind='card_series'
				""").<Map<String, Object>>map((row, metadata) -> {
			Map<String, Object> values = new LinkedHashMap<>();
			values.put("kind", row.get("kind", String.class));
			values.put("mode", row.get("mode", String.class));
			values.put("resolution", row.get("resolution", String.class));
			values.put("provider", row.get("provider", String.class));
			values.put("mediaCount", row.get("media_count", Integer.class));
			values.put("okCount", row.get("ok_count", String.class));
			return values;
		}).one().block();
		assertThat(lineage).isNotNull().containsEntry("mode", "independent").containsEntry("resolution", "platform")
				.containsEntry("mediaCount", 3).containsEntry("okCount", "3");
	}

	@Test
	@DisplayName("部分成功：第 2 卡失败不拖垮整批，lineage okCount=2")
	void partialFailureSemantics() {
		when(generation.generate(anyString(), anyString(), any(), any()))
				.thenReturn(Mono.just(new GeneratedImage(null, PNG_B64, "a")))
				.thenReturn(Mono.error(new RuntimeException("provider down")))
				.thenReturn(Mono.just(new GeneratedImage(null, PNG_B64, "c")));

		Map<String, Object> body = client().post().uri("/api/card-series/generate")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(generateBody(3)).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> cards = (List<Map<String, Object>>) ((Map<?, ?>) body.get("data")).get("cards");
		assertThat(cards.get(0).get("ok")).isEqualTo(true);
		assertThat(cards.get(1).get("ok")).isEqualTo(false);
		assertThat((String) cards.get(1).get("errorReason")).isNotBlank();
		assertThat(cards.get(2).get("ok")).isEqualTo(true);

		String okCount = db.sql("SELECT result->>'okCount' AS ok FROM creation_generation WHERE kind='card_series'")
				.map(row -> row.get("ok", String.class)).one().block();
		assertThat(okCount).isEqualTo("2");
	}

	@Test
	@DisplayName("预算耗尽：逐卡被闸，卡片全失败带原因，不 500")
	void budgetDeniedPerCard() {
		// 个人作用域行（organization_id NOT NULL；个人预算以 u: 前缀字符串为作用域键）
		db.sql("INSERT INTO ai_model_budget(organization_id, capability, provider, max_cents_per_run, enabled) "
				+ "VALUES (:scope, 'image_generation', 'platform', 1, true)").bind("scope", "u:" + ACCOUNT).then()
				.block();

		Map<String, Object> body = client().post().uri("/api/card-series/generate")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(generateBody(2)).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> cards = (List<Map<String, Object>>) ((Map<?, ?>) body.get("data")).get("cards");
		assertThat(cards).allSatisfy(card -> {
			assertThat(card.get("ok")).isEqualTo(false);
			assertThat((String) card.get("errorReason")).contains("exceeds_run_budget");
		});
		verify(generation, never()).generate(anyString(), anyString(), any(), any());
	}

	@Test
	@DisplayName("持久化：转永久 key + 无过期行 + 幂等 + 归属校验 404")
	void persistsCardPermanently() {
		when(generation.generate(anyString(), anyString(), any(), any()))
				.thenReturn(Mono.just(new GeneratedImage(null, PNG_B64, "锚")));

		Map<String, Object> body = client().post().uri("/api/card-series/generate")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(generateBody(1)).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> cards = (List<Map<String, Object>>) ((Map<?, ?>) body.get("data")).get("cards");
		String cardId = ((String) cards.get(0).get("url"))
				.substring(((String) cards.get(0).get("url")).lastIndexOf('/') + 1);

		Map<String, Object> persisted = client().post().uri("/api/card-series/cards/" + cardId + "/persist")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		String mediaId = (String) ((Map<?, ?>) persisted.get("data")).get("mediaId");
		assertThat(mediaId).isNotBlank();

		ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
		verify(storage, times(1)).putObject(keys.capture(), any(), anyString());
		assertThat(keys.getValue()).isEqualTo("media/card_series/" + cardId);
		Long permanent = db
				.sql("SELECT count(*) AS c FROM media_reference WHERE object_key=:key AND expires_at IS NULL")
				.bind("key", "media/card_series/" + cardId).map(row -> row.get("c", Long.class)).one().block();
		assertThat(permanent).isEqualTo(1L);

		// 幂等：二次持久化返回同一 mediaId，不再写对象
		Map<String, Object> again = client().post().uri("/api/card-series/cards/" + cardId + "/persist")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		assertThat((String) ((Map<?, ?>) again.get("data")).get("mediaId")).isEqualTo(mediaId);
		verify(storage, times(1)).putObject(anyString(), any(), anyString());

		// 他人访问 → 404 不泄漏存在性
		client().post().uri("/api/card-series/cards/" + cardId + "/persist")
				.header("X-Grassland-Identity", sign(OTHER, "recommender")).exchange().expectStatus().isNotFound();
		client().post().uri("/api/card-series/cards/not-a-uuid/persist")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isNotFound();
	}

	// ---------------- AI内容中心改造-02：任务冻结执行 + 操作记录（T08/T21） ----------------

	@Test
	@DisplayName("任务图卡：快照平台错配在模型调用前 409；计划走 executeTraced 且注入冻结上下文与 Brief")
	void taskModePlanBindsFrozenSnapshot() {
		String snapshotId = seedGraphicSnapshot(ACCOUNT, "xiaohongshu");
		ArgumentCaptor<List<com.grassland.intelligence.ai.ChatMessage>> msgCaptor = ArgumentCaptor
				.forClass((Class) List.class);
		when(frozenText.executeTraced(any(), any(), msgCaptor.capture(), anyInt(),
				org.mockito.ArgumentMatchers.eq(CreditFeature.CARD_SERIES_PLAN), any()))
				.thenReturn(Mono.just(traced(new CardSeriesService.CardSeriesPlan(
						List.of(new CardSeriesService.CardPlan("封面：任务图卡", List.of("要点"), "插画", "配文"))))));

		Map<String, Object> body = planBody(1);
		body.put("contextSnapshotId", snapshotId);
		body.put("brief", Map.of("processingMode", "create", "extraInstructions", "保留价格 68 元"));
		client().post().uri("/api/card-series/plan").header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk()
				.expectBody(String.class).value(content -> assertThat(content).contains("封面：任务图卡"));

		assertThat(msgCaptor.getValue()).hasSize(3);
		assertThat(msgCaptor.getValue().get(1).content()).contains("冻结的权威图文任务上下文");
		assertThat(msgCaptor.getValue().get(2).content()).contains("保留价格 68 元").contains("创作简报");
		verify(frozenText, never()).executeIndependent(any(), any(), anyInt(), any(), any(), any());

		// 平台与快照不一致 → 409，不进执行环（T08）
		Map<String, Object> mismatched = planBody(1);
		mismatched.put("platform", "douyin");
		mismatched.put("contextSnapshotId", snapshotId);
		client().post().uri("/api/card-series/plan").header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(mismatched).exchange().expectStatus().isEqualTo(409);
		// 他人快照 → 403
		Map<String, Object> foreign = planBody(1);
		foreign.put("contextSnapshotId", snapshotId);
		client().post().uri("/api/card-series/plan").header("X-Grassland-Identity", sign(OTHER, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(foreign).exchange().expectStatus().isForbidden();
	}

	@Test
	@DisplayName("任务生图：generateForBoundContextTraced 执行且 lineage mode=task")
	void taskModeGenerateUsesBoundContext() {
		String snapshotId = seedGraphicSnapshot(ACCOUNT, "xiaohongshu");
		when(taskGeneration.generateForBoundContextTraced(any(), any(), any(),
				org.mockito.ArgumentMatchers.eq(com.grassland.intelligence.media.MediaPurpose.CARD_SERIES)))
				.thenAnswer(invocation -> Mono.just(
						new com.grassland.intelligence.articleimage.TaskImageGenerationService.GeneratedImageWithTrace(
								new com.grassland.intelligence.articleimage.GeneratedImageResponse(
										"/api/article-generation/generated-images/" + UUID.randomUUID(), "任务锚"),
								UUID.randomUUID(), "platform", "img-model", 7)));

		Map<String, Object> body = generateBody(1);
		body.put("contextSnapshotId", snapshotId);
		Map<String, Object> response = client().post().uri("/api/card-series/generate")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		assertThat((List<?>) ((Map<?, ?>) response.get("data")).get("cards")).hasSize(1);
		verify(taskGeneration, times(1)).generateForBoundContextTraced(any(), any(), any(), any());
		verify(generation, never()).generate(anyString(), anyString(), any(), any());
		String mode = db.sql("SELECT mode FROM creation_generation WHERE kind='card_series'")
				.map(row -> row.get("mode", String.class)).one().block();
		assertThat(mode).isEqualTo("task");
	}

	@Test
	@DisplayName("T21 操作记录：同请求回读原结果不再调模型；异请求同键 409；查询端点回状态")
	void operationRecordReplayAndConflict() {
		when(generation.generate(anyString(), anyString(), any(), any()))
				.thenReturn(Mono.just(new GeneratedImage(null, PNG_B64, "锚")));

		Map<String, Object> body = generateBody(2);
		body.put("requestId", "op-reuse-1");
		Map<String, Object> first = client().post().uri("/api/card-series/generate")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();

		// 相同 requestId + 相同请求 → 回读已记录结果，零新增模型调用
		Map<String, Object> replay = client().post().uri("/api/card-series/generate")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		assertThat(replay).isEqualTo(first);
		verify(generation, times(2)).generate(anyString(), anyString(), any(), any());

		// 相同 requestId + 变更请求 → 409
		Map<String, Object> changed = generateBody(2);
		changed.put("requestId", "op-reuse-1");
		((List<Map<String, Object>>) (Object) changed.get("cards")).get(0).put("title", "改过的标题");
		client().post().uri("/api/card-series/generate").header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(changed).exchange().expectStatus().isEqualTo(409);

		// 查询端点：owner 视角回 succeeded 与记录结果；他人 404
		client().get().uri("/api/card-series/operations/op-reuse-1")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.status").isEqualTo("succeeded").jsonPath("$.data.cards").isArray();
		client().get().uri("/api/card-series/operations/op-reuse-1")
				.header("X-Grassland-Identity", sign(OTHER, "recommender")).exchange().expectStatus().isNotFound();
	}

	private String seedGraphicSnapshot(String accountId, String platform) {
		return db.sql("""
				INSERT INTO creation_context_snapshot(
				    account_id, task_id, application_id, task_version, platform_id, content_form_id,
				    task_snapshot, platform_rules_snapshot, material_snapshot, ai_config_snapshot)
				VALUES (:account,:task,:application,3,:platform,'graphic',
				    '{"title":"图文任务","requirements":"必须包含门店名称"}'::jsonb,
				    '{"version":"2026-09-08","maxChars":1000}'::jsonb,
				    '{"items":[]}'::jsonb,
				    '{"resolutionType":"PLATFORM","configId":"cfg","provider":"qwen","model":"qwen-plus",
				      "platformModelVersion":7,"modelRole":"primary"}'::jsonb)
				RETURNING id::text
				""").bind("account", accountId).bind("task", UUID.randomUUID().toString())
				.bind("application", UUID.randomUUID().toString()).bind("platform", platform)
				.map(row -> row.get("id", String.class)).one().block();
	}

	// ---------------- helpers ----------------

	private static <T> FrozenTextExecutionService.Traced<T> traced(T value) {
		return new FrozenTextExecutionService.Traced<>(value, UUID.randomUUID(), "qwen", "qwen-plus", 1, false);
	}

	private static Map<String, Object> planBody(int cardCount) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("platform", "xiaohongshu");
		body.put("content", "新店开业，全场八折。招牌烤鱼 68 元一份，手工酸辣粉 22 元。" + "营业时间周一至周日 10:00-22:00，地铁 2 号线 A 口步行 300 米。"
				+ "开业前三天到店打卡送甜品，分享朋友圈再减 10 元。");
		body.put("cardCount", cardCount);
		body.put("styleText", "可爱清新风格：圆润造型、暖色调、活泼手绘插画");
		body.put("layoutText", "清单布局：上方主视觉、下方留白要点区");
		body.put("paletteText", "马卡龙配色");
		return body;
	}

	private static Map<String, Object> generateBody(int cardCount) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("platform", "xiaohongshu");
		body.put("styleText", "可爱清新风格：圆润造型、暖色调、活泼手绘插画");
		body.put("layoutText", "清单布局：上方主视觉、下方留白要点区");
		body.put("size", "1024x1792");
		java.util.List<Map<String, Object>> cards = new java.util.ArrayList<>();
		for (int i = 0; i < cardCount; i++) {
			Map<String, Object> card = new LinkedHashMap<>();
			card.put("title", "卡片 " + (i + 1));
			card.put("bullets", List.of("要点一", "要点二"));
			card.put("illustration", "门店门头插画，暖色氛围");
			card.put("caption", "配文 " + (i + 1));
			cards.add(card);
		}
		body.put("cards", cards);
		return body;
	}
}
