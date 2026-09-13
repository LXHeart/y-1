package com.grassland.intelligence.creationstudio;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.articleimage.ImageGenerationConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 任务书 #101 C101-05（API101-08～12、V79）：服务端视觉计划、修订与费用估算。 TC101-020～025。模型经
 * WireMock（/chat/completions 合成计划 JSON）；估算走治理台 image_generation
 * 行（seedPlatformImageGenerationModel），不触发出站图片调用。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true"})
class VisualPlanIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "00000000-0000-4000-8000-00000000020a";
	private static final String ACCOUNT_B = "00000000-0000-4000-8000-00000000020b";
	private static final com.github.tomakehurst.wiremock.WireMockServer FINANCE = new com.github.tomakehurst.wiremock.WireMockServer(
			0);
	static {
		FINANCE.start();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void finance(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("credits.finance.base-url", FINANCE::baseUrl);
		registry.add("marketplace.service.base-url", FINANCE::baseUrl);
	}

	@Autowired
	private ImageGenerationConfig imageConfig;

	private String draftId;
	private int draftVersion;

	@BeforeEach
	void seedDraftAndClean() {
		FINANCE.resetAll();
		FINANCE.stubFor(get(urlEqualTo("/internal/marketplace/reputation/" + ACCOUNT + "/ai-entitlement")).willReturn(
				okJson("{\"success\":true,\"data\":{\"accountId\":\"00000000-0000-4000-8000-00000000020a\",\"aiQuotaMultiplierBps\":10000,\"policyVersion\":1}}")));
		FINANCE.stubFor(post(urlEqualTo("/internal/credits/consume")).willReturn(okJson(
				"{\"success\":true,\"data\":{\"source\":\"quota\",\"policyVersion\":1,\"transactionId\":\"11111111-1111-4111-8111-111111111111\"}}")));
		FINANCE.stubFor(
				post(urlEqualTo("/internal/credits/consume-compensations")).willReturn(aResponse().withStatus(200)));
		db.sql("DELETE FROM creation_visual_quote").then()
				.then(db.sql("DELETE FROM creation_visual_plan_revision").then())
				.then(db.sql("DELETE FROM creation_visual_plan").then())
				.then(db.sql("DELETE FROM creation_studio_apply").then())
				.then(db.sql("DELETE FROM creation_source_document").then())
				.then(db.sql("DELETE FROM creation_draft WHERE owner_account_id = :account").bind("account", ACCOUNT)
						.then())
				.block(java.time.Duration.ofSeconds(10));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceType", "independent");
		body.put("title", "计划 IT 草稿");
		body.put("platform", "xiaohongshu");
		body.put("contentForm", "graphic");
		body.put("capability", "article");
		body.put("content", "门店三年，人均 68 元。\n\n招牌面 32 元，日销两百碗。");
		Map<String, Object> response = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		draftId = ((Map<?, ?>) response.get("data")).get("id").toString();
		draftVersion = (Integer) ((Map<?, ?>) response.get("data")).get("version");
		attachPlatformTextCredential();
		QWEN.resetAll();
	}

	// ---- helpers ----

	@SuppressWarnings("unchecked")
	private Map<String, Object> importSource(String markdown) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", draftVersion);
		body.put("kind", "markdown");
		body.put("text", markdown);
		Map<String, Object> response = client().post().uri("/api/creation-studio/sources")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	private static List<String> blockIds(Map<String, Object> source) {
		List<String> ids = new ArrayList<>();
		for (Object block : (List<?>) source.get("blocks")) {
			ids.add(((Map<?, ?>) block).get("id").toString());
		}
		return ids;
	}

	/** 合成模型计划输出（content 为转义 JSON 字符串）；criticalText/blocks 由调用方控制。 */
	private void stubModelPlan(String rawJson) {
		String escaped;
		try {
			escaped = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(rawJson);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
		String response = "{\"choices\":[{\"message\":{\"content\":" + escaped
				+ "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
		QWEN.stubFor(post(urlEqualTo("/chat/completions"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(response)));
	}

	/** 构造合法模型输出：cover + N-1 个 content 项，来源块轮转引用，关键文字取自块文本。 */
	private static String modelItems(List<String> ids, int count, java.util.function.UnaryOperator<String> json) {
		StringBuilder items = new StringBuilder("[");
		for (int index = 0; index < count; index++) {
			String blockId = ids.get(index % ids.size());
			if (index > 0) {
				items.append(',');
			}
			items.append("{\"role\":\"").append(index == 0 ? "cover" : "content").append("\",").append("\"title\":\"第")
					.append(index + 1).append("卡\",").append("\"bullets\":[\"要点一\",\"要点二\"],")
					.append("\"criticalText\":[\"68\",\"32\"],")
					.append("\"illustration\":\"暖光小店门头特写，木质招牌与蒸汽，平视构图，生活质感，100字左右的完整画面描述，主体明确场景具体光线柔和质感细腻。\",")
					.append("\"caption\":\"发布配文").append(index + 1).append("\",").append("\"purpose\":\"承载第")
					.append(index + 1).append("个要点\",").append("\"sourceBlockIds\":[\"").append(blockId).append("\"]}");
		}
		items.append("]");
		String raw = "{\"items\":" + items + ",\"explanation\":\"按信息密度拆分\"}";
		return json.apply(raw);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> preparePlan(String requestId, Map<String, Object> source, Integer itemCount,
			String strategy, String extraKey, Object extraValue) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", requestId);
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", draftVersion);
		body.put("recipe", Map.of("id", "social-card-series", "version", "1.0.0"));
		body.put("source",
				Map.of("id", source.get("id").toString(), "contentHash", source.get("contentHash").toString()));
		body.put("selectedBlockIds", List.of());
		if (itemCount != null) {
			body.put("itemCount", itemCount);
		}
		if (strategy != null) {
			body.put("strategy", strategy);
		}
		if (extraKey != null) {
			body.put(extraKey, extraValue);
		}
		Map<String, Object> response = client().post().uri("/api/creation-studio/visual-plans")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	private Map<String, Object> readyPlan(Map<String, Object> source, Integer itemCount) {
		return preparePlan(UUID.randomUUID().toString(), source, itemCount, "information", null, null);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> getPlan(String id, Integer revision) {
		String uri = "/api/creation-studio/visual-plans/" + id + (revision == null ? "" : "?revision=" + revision);
		Map<String, Object> response = client().get().uri(uri).header("X-Grassland-Identity", sign(ACCOUNT, null))
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> confirmPlan(String id, int expectedRevision) {
		Map<String, Object> body = Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId,
				"expectedDraftVersion", draftVersion, "expectedRevision", expectedRevision, "sourceContentHash",
				sourceHashOf(id));
		Map<String, Object> response = client().post().uri("/api/creation-studio/visual-plans/" + id + "/confirm")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> patchPlan(String id, int expectedRevision, Map<String, Object> document) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("expectedRevision", expectedRevision);
		body.put("document", document);
		Map<String, Object> response = client().patch().uri("/api/creation-studio/visual-plans/" + id)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	private String sourceHashOf(String planId) {
		return db.sql("SELECT source_content_hash FROM creation_visual_plan WHERE id = CAST(:id AS uuid)")
				.bind("id", planId).map(row -> row.get(0, String.class)).one().block(java.time.Duration.ofSeconds(10));
	}

	/** 五块来源（标题×2＋段落×3）；每块都逐字含 "68" 与 "32"，供 criticalText 校验。 */
	private String importDefaultSource() {
		return "# 三年老店：人均 68 元与招牌 32 元\n\n门店三年，人均 68 元，招牌 32 元。\n\n"
				+ "日销两百碗，人均 68 元，招牌 32 元。\n\n## 小结：人均 68 元，招牌 32 元\n\n" + "回头客占六成，人均 68 元，招牌 32 元。";
	}

	// ---- TC101-020：指定 5 图 + 信息策略 ----

	/** 恰好 5 个合法稳定项、来源可定位；一次模型调用；同键重放不再调用。 */
	@Test
	@SuppressWarnings("unchecked")
	void explicitFiveItemsInformationStrategyProducesTraceablePlan() {
		Map<String, Object> source = importSource(importDefaultSource());
		List<String> ids = blockIds(source);
		stubModelPlan(modelItems(ids, 5, raw -> raw));

		String requestId = UUID.randomUUID().toString();
		Map<String, Object> plan = preparePlan(requestId, source, 5, "information", null, null);

		assertThat(plan.get("status")).isEqualTo("ready");
		assertThat(plan.get("revision")).isEqualTo(1);
		assertThat(plan.get("runId")).asString().isNotBlank();
		Map<String, Object> document = (Map<String, Object>) plan.get("document");
		assertThat(document.get("strategy")).isEqualTo("information");
		List<Map<String, Object>> items = (List<Map<String, Object>>) document.get("items");
		assertThat(items).hasSize(5);
		assertThat(items.get(0).get("role")).isEqualTo("cover");
		// 身份服务端铸造且稳定；position 连续；来源可定位（全部引用真实块）
		var distinctItemIds = items.stream().map(item -> item.get("itemId").toString()).distinct().count();
		var distinctCardIds = items.stream().map(item -> item.get("cardId").toString()).distinct().count();
		assertThat(distinctItemIds).isEqualTo(5);
		assertThat(distinctCardIds).isEqualTo(5);
		for (int index = 0; index < items.size(); index++) {
			assertThat(items.get(index).get("position")).isEqualTo(index + 1);
			assertThat((List<String>) items.get(index).get("sourceBlockIds")).isSubsetOf(ids);
		}
		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));

		// 同键重放：同一计划、不再次调用模型
		Map<String, Object> replay = preparePlan(requestId, source, 5, "information", null, null);
		assertThat(replay.get("id")).isEqualTo(plan.get("id"));
		assertThat(replay.get("status")).isEqualTo("ready");
		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));

		// 同键异参 409；跨账号 404
		preparePlanConflict(requestId, source);
		client().get().uri("/api/creation-studio/visual-plans/" + plan.get("id"))
				.header("X-Grassland-Identity", sign(ACCOUNT_B, null)).exchange().expectStatus().isNotFound();
	}

	private void preparePlanConflict(String requestId, Map<String, Object> source) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", requestId);
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", draftVersion);
		body.put("recipe", Map.of("id", "social-card-series", "version", "1.0.0"));
		body.put("source",
				Map.of("id", source.get("id").toString(), "contentHash", source.get("contentHash").toString()));
		body.put("itemCount", 4);
		body.put("strategy", "information");
		client().post().uri("/api/creation-studio/visual-plans").header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus()
				.isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
	}

	// ---- TC101-021：缺省数量算法 ----

	/** 4 非空块 → min(6,max(2,1+2))=3；12 非空块 → min(6,7)=6（不超模板上限）。 */
	@Test
	@SuppressWarnings("unchecked")
	void defaultItemCountFollowsBlockAlgorithmAndTemplateCap() {
		Map<String, Object> fourBlockSource = importSource("第一段：门店三年，人均 68 元，招牌 32 元。\n\n第二段：日销两百碗，人均 68 元，招牌 32 元。\n\n"
				+ "第三段：回头客占六成，人均 68 元，招牌 32 元。\n\n第四段：加面免费，人均 68 元，招牌 32 元。");
		assertThat(blockIds(fourBlockSource)).hasSize(4);
		stubModelPlan(modelItems(blockIds(fourBlockSource), 3, raw -> raw));
		Map<String, Object> plan = readyPlan(fourBlockSource, null);
		Map<String, Object> document = (Map<String, Object>) plan.get("document");
		assertThat((List<?>) document.get("items")).hasSize(3);
		// 无确认经历 + 素材不足 → 缺省策略 information
		assertThat(document.get("strategy")).isEqualTo("information");

		StringBuilder twelve = new StringBuilder();
		for (int index = 1; index <= 12; index++) {
			if (index > 1) {
				twelve.append("\n\n");
			}
			twelve.append("第").append(index).append("段：要点 ").append(index).append("，人均 68 元，招牌 32 元。");
		}
		Map<String, Object> twelveBlockSource = importSource(twelve.toString());
		assertThat(blockIds(twelveBlockSource)).hasSize(12);
		stubModelPlan(modelItems(blockIds(twelveBlockSource), 6, raw -> raw));
		Map<String, Object> capped = readyPlan(twelveBlockSource, null);
		assertThat((List<?>) ((Map<String, Object>) capped.get("document")).get("items")).hasSize(6);

		// 显式超模板上限 → 400，不静默截断
		Map<String, Object> over = importSource(importDefaultSource());
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", draftVersion);
		body.put("recipe", Map.of("id", "social-card-series", "version", "1.0.0"));
		body.put("source", Map.of("id", over.get("id").toString(), "contentHash", over.get("contentHash").toString()));
		body.put("itemCount", 10);
		client().post().uri("/api/creation-studio/visual-plans").header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isBadRequest();
	}

	// ---- TC101-022：模型输出违约 ----

	/** 假 blockId／注入身份字段／编造关键数字 → STUDIO_INVALID_PLAN（failed），无图片调用。 */
	@Test
	@SuppressWarnings("unchecked")
	void fabricatedModelOutputFailsClosed() {
		Map<String, Object> source = importSource(importDefaultSource());
		List<String> ids = blockIds(source);

		// 假 blockId
		stubModelPlan(modelItems(ids, 3, raw -> raw.replace(ids.get(0), "00000000-0000-4000-8000-00000000dead")));
		Map<String, Object> fakeBlock = readyPlan(source, 3);
		assertThat(fakeBlock.get("status")).isEqualTo("failed");
		assertThat(((Map<String, Object>) fakeBlock.get("error")).get("code")).isEqualTo("STUDIO_INVALID_PLAN");
		assertThat(fakeBlock.get("document")).isNull();

		// 注入 itemId（模型不应提供身份字段）
		stubModelPlan(modelItems(ids, 3,
				raw -> raw.replace("{\"role\":\"cover\"", "{\"itemId\":\"evil-id\",\"role\":\"cover\"")));
		Map<String, Object> injected = readyPlan(source, 3);
		assertThat(injected.get("status")).isEqualTo("failed");

		// 编造关键数字（criticalText 不在来源块中逐字出现）
		stubModelPlan(modelItems(ids, 3, raw -> raw
				.replace("\"criticalText\":[\"68\",\"32\"]", "\"criticalText\":[\"99 元\"]").replace("第1卡", "第1卡99")));
		Map<String, Object> wrongNumber = readyPlan(source, 3);
		assertThat(wrongNumber.get("status")).isEqualTo("failed");

		// 三次准备=三次模型调用（一次一计划）；没有任何图片出站调用（QWEN 仅 chat/completions）
		QWEN.verify(3, postRequestedFor(urlEqualTo("/chat/completions")));
		// failed 计划不能确认／估算
		String failedId = fakeBlock.get("id").toString();
		client().post().uri("/api/creation-studio/visual-plans/" + failedId + "/confirm")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId, "expectedDraftVersion",
						draftVersion, "expectedRevision", 1, "sourceContentHash", sourceHashOf(failedId)))
				.exchange().expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
		client().post().uri("/api/creation-studio/visual-plans/" + failedId + "/estimate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedRevision", 1, "selectedItemIds",
						List.of("x"), "consistencyMode", "prompt-only"))
				.exchange().expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
	}

	// ---- TC101-023：PATCH 清确认 + 旧 revision 不可变 ----

	/** 改第 3 页 → 新 revision 清确认；旧 revision 只读且数据库层拒绝改写。 */
	@Test
	@SuppressWarnings("unchecked")
	void patchClearsConfirmationAndOldRevisionsAreImmutable() {
		Map<String, Object> source = importSource(importDefaultSource());
		stubModelPlan(modelItems(blockIds(source), 3, raw -> raw));
		Map<String, Object> plan = readyPlan(source, 3);
		String planId = plan.get("id").toString();

		Map<String, Object> confirmed = confirmPlan(planId, 1);
		assertThat(confirmed.get("confirmedRevision")).isEqualTo(1);

		// 改第 3 项标题，整份 document 提交（身份不变）
		Map<String, Object> document = (Map<String, Object>) plan.get("document");
		List<Map<String, Object>> items = (List<Map<String, Object>>) document.get("items");
		String thirdItemId = items.get(2).get("itemId").toString();
		items.get(2).put("title", "改后的第三卡标题");
		Map<String, Object> patched = patchPlan(planId, 1, document);

		assertThat(patched.get("revision")).isEqualTo(2);
		assertThat(patched.get("confirmedRevision")).isNull();
		List<Map<String, Object>> patchedItems = (List<Map<String, Object>>) ((Map<String, Object>) patched
				.get("document")).get("items");
		assertThat(patchedItems.get(2).get("title")).isEqualTo("改后的第三卡标题");
		assertThat(patchedItems.get(2).get("itemId")).isEqualTo(thirdItemId);

		// 旧 revision 只读：内容保持原样
		Map<String, Object> revision1 = getPlan(planId, 1);
		List<Map<String, Object>> oldItems = (List<Map<String, Object>>) ((Map<String, Object>) revision1
				.get("document")).get("items");
		assertThat(oldItems.get(2).get("title").toString()).doesNotContain("改后");

		// 数据库层不可变（触发器拒绝 UPDATE）
		assertThatThrownBy(() -> db
				.sql("UPDATE creation_visual_plan_revision SET document_json = '{}'::jsonb "
						+ "WHERE plan_id = CAST(:id AS uuid) AND revision = 1")
				.bind("id", planId).then().block(java.time.Duration.ofSeconds(10))).hasMessageContaining("immutable");

		// 确认旧 revision → 409（须按当前 revision 重新确认）
		client().post().uri("/api/creation-studio/visual-plans/" + planId + "/confirm")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId, "expectedDraftVersion",
						draftVersion, "expectedRevision", 1, "sourceContentHash", sourceHashOf(planId)))
				.exchange().expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
	}

	/** PATCH 不能切换策略／模板（须新建计划）。 */
	@Test
	@SuppressWarnings("unchecked")
	void patchRejectsStrategyAndRecipeSwitch() {
		Map<String, Object> source = importSource(importDefaultSource());
		stubModelPlan(modelItems(blockIds(source), 3, raw -> raw));
		Map<String, Object> plan = readyPlan(source, 3);
		String planId = plan.get("id").toString();
		Map<String, Object> document = (Map<String, Object>) plan.get("document");

		Map<String, Object> switched = new LinkedHashMap<>(document);
		switched.put("strategy", "story");
		client().patch().uri("/api/creation-studio/visual-plans/" + planId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(
						Map.of("requestId", UUID.randomUUID().toString(), "expectedRevision", 1, "document", switched))
				.exchange().expectStatus().isBadRequest();

		Map<String, Object> swapped = new LinkedHashMap<>(document);
		swapped.put("recipe", Map.of("id", "social-card-series", "version", "9.9.9"));
		client().patch().uri("/api/creation-studio/visual-plans/" + planId)
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(
						Map.of("requestId", UUID.randomUUID().toString(), "expectedRevision", 1, "document", swapped))
				.exchange().expectStatus().isBadRequest();
	}

	// ---- TC101-024：confirm 与 PATCH 并发 ----

	/** 并发 confirm(rev1)+PATCH(rev1)：行锁串行，终态一致（rev2、确认清空、无孤儿 revision）。 */
	@Test
	void confirmAndPatchConcurrentlySerializeOnPlanRowLock() {
		Map<String, Object> source = importSource(importDefaultSource());
		stubModelPlan(modelItems(blockIds(source), 3, raw -> raw));
		Map<String, Object> plan = readyPlan(source, 3);
		String planId = plan.get("id").toString();
		String sourceHash = sourceHashOf(planId);
		@SuppressWarnings("unchecked")
		Map<String, Object> document = (Map<String, Object>) plan.get("document");

		AtomicInteger confirmStatus = new AtomicInteger();
		AtomicInteger patchStatus = new AtomicInteger();
		Mono<Void> confirmCall = Mono.defer(() -> {
			var result = client().post().uri("/api/creation-studio/visual-plans/" + planId + "/confirm")
					.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
					.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId,
							"expectedDraftVersion", draftVersion, "expectedRevision", 1, "sourceContentHash",
							sourceHash))
					.exchange().expectBody(Map.class).returnResult();
			confirmStatus.set(result.getStatus().value());
			return Mono.empty();
		}).subscribeOn(Schedulers.boundedElastic()).then();
		Mono<Void> patchCall = Mono.defer(() -> {
			var result = client().patch().uri("/api/creation-studio/visual-plans/" + planId)
					.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
					.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedRevision", 1, "document",
							document))
					.exchange().expectBody(Map.class).returnResult();
			patchStatus.set(result.getStatus().value());
			return Mono.empty();
		}).subscribeOn(Schedulers.boundedElastic()).then();
		Mono.when(confirmCall, patchCall).block(java.time.Duration.ofSeconds(30));

		// PATCH 的 CAS 只会被另一 PATCH 消耗 → 必须成功；confirm 与 PATCH 串行化后要么成功要么 409。
		assertThat(patchStatus.get()).isEqualTo(200);
		assertThat(confirmStatus.get()).isIn(200, 409);

		Long[] state = db.sql(
				"SELECT current_revision, confirmed_revision FROM creation_visual_plan WHERE id = CAST(:id AS uuid)")
				.bind("id", planId)
				.map(row -> new Long[]{row.get(0, Long.class),
						row.get(1, Long.class) == null ? -1L : row.get(1, Long.class)})
				.one().block(java.time.Duration.ofSeconds(10));
		// 无半更新确认：指针推进到 2，确认必被清空（PATCH 清确认在推进同一事务）。
		assertThat(state[0]).isEqualTo(2L);
		assertThat(state[1]).isEqualTo(-1L);
		Long revisions = db.sql("SELECT count(*) FROM creation_visual_plan_revision WHERE plan_id = CAST(:id AS uuid)")
				.bind("id", planId).map(row -> row.get(0, Long.class)).one().block(java.time.Duration.ofSeconds(10));
		assertThat(revisions).isEqualTo(2L);
	}

	// ---- TC101-025：估算 ----

	/** 估算无模型调用／扣费；reference-image 拒绝；路由缺失 503 不伪造 0；重复估算幂等。 */
	@Test
	@SuppressWarnings("unchecked")
	void estimateQuotesWithoutModelCallsOrCharges() {
		Map<String, Object> source = importSource(importDefaultSource());
		stubModelPlan(modelItems(blockIds(source), 3, raw -> raw));
		Map<String, Object> plan = readyPlan(source, 3);
		String planId = plan.get("id").toString();
		List<String> itemIds = new ArrayList<>();
		for (Object item : (List<?>) ((Map<String, Object>) plan.get("document")).get("items")) {
			itemIds.add(((Map<?, ?>) item).get("itemId").toString());
		}

		// reference-image：当前协议不支持 → 409，明确能力（不假报原生参考）
		client().post().uri("/api/creation-studio/visual-plans/" + planId + "/estimate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedRevision", 1, "selectedItemIds",
						itemIds, "consistencyMode", "reference-image"))
				.exchange().expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT);

		// anchor 不存在（artifact 属 C101-09）
		client().post().uri("/api/creation-studio/visual-plans/" + planId + "/estimate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedRevision", 1, "selectedItemIds",
						itemIds, "consistencyMode", "prompt-only", "anchorArtifactId", UUID.randomUUID().toString()))
				.exchange().expectStatus().isNotFound();

		// 控制面无 image_generation 行 → 503 明确缺项，不伪造 0 元
		client().post().uri("/api/creation-studio/visual-plans/" + planId + "/estimate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedRevision", 1, "selectedItemIds",
						itemIds, "consistencyMode", "prompt-only"))
				.exchange().expectStatus().isEqualTo(503);

		seedPlatformImageGenerationModel();
		int consumesBefore = FINANCE.findAll(postRequestedFor(urlEqualTo("/internal/credits/consume"))).size();

		Map<String, Object> quote = estimate(planId, itemIds);
		assertThat(quote.get("imageCalls")).isEqualTo(3);
		assertThat(quote.get("platformBudgetCents")).isEqualTo(imageConfig.unitPriceCents() * 3);
		assertThat(quote.get("userCredits")).isEqualTo(0);
		assertThat(quote.get("billingSource")).isEqualTo("platform");
		assertThat(quote.get("consistencyMode")).isEqualTo("prompt-only");
		assertThat((String) quote.get("expiresAt")).isNotBlank();
		assertThat((List<String>) quote.get("selectedItemIds")).containsExactlyElementsOf(itemIds);

		// 重复估算（新键）：第二份 quote，仍无模型调用／扣费
		Map<String, Object> again = estimate(planId, itemIds);
		assertThat(again.get("imageCalls")).isEqualTo(3);
		assertThat(FINANCE.findAll(postRequestedFor(urlEqualTo("/internal/credits/consume")))).hasSize(consumesBefore);
		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")));
		Long quoteRows = db.sql("SELECT count(*) FROM creation_visual_quote WHERE plan_id = CAST(:id AS uuid)")
				.bind("id", planId).map(row -> row.get(0, Long.class)).one().block(java.time.Duration.ofSeconds(10));
		assertThat(quoteRows).isEqualTo(2L);

		// 同键重放返回同一 quote；同键异参 409
		String requestId = UUID.randomUUID().toString();
		Map<String, Object> first = estimateWithRequestId(requestId, planId, itemIds);
		Map<String, Object> replay = estimateWithRequestId(requestId, planId, itemIds);
		assertThat(replay.get("id")).isEqualTo(first.get("id"));
		client().post().uri("/api/creation-studio/visual-plans/" + planId + "/estimate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", requestId, "expectedRevision", 1, "selectedItemIds",
						itemIds.subList(0, 1), "consistencyMode", "prompt-only"))
				.exchange().expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT);

		// 未知 itemId → 400
		client().post().uri("/api/creation-studio/visual-plans/" + planId + "/estimate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedRevision", 1, "selectedItemIds",
						List.of("00000000-0000-4000-8000-000000000099"), "consistencyMode", "prompt-only"))
				.exchange().expectStatus().isBadRequest();

		// 旧 revision 估算 → 409
		Map<String, Object> document = (Map<String, Object>) plan.get("document");
		patchPlan(planId, 1, document);
		client().post().uri("/api/creation-studio/visual-plans/" + planId + "/estimate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "expectedRevision", 1, "selectedItemIds",
						itemIds, "consistencyMode", "prompt-only"))
				.exchange().expectStatus().isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> estimate(String planId, List<String> itemIds) {
		return estimateWithRequestId(UUID.randomUUID().toString(), planId, itemIds);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> estimateWithRequestId(String requestId, String planId, List<String> itemIds) {
		Map<String, Object> response = client().post().uri("/api/creation-studio/visual-plans/" + planId + "/estimate")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", requestId, "expectedRevision", 1, "selectedItemIds", itemIds,
						"consistencyMode", "prompt-only"))
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) response.get("data");
	}
}
