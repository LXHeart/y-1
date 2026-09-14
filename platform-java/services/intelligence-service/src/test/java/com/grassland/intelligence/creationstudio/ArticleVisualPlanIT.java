package com.grassland.intelligence.creationstudio;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * 任务书 #101 C101-14（TC101-065~068）：文章封面与段落配图策划。 article-visuals：封面恰好一张、每张插图
 * purpose+afterBlockId 定位真实段落；知乎 answer 保留问题语义；cover-only 恰好一张； 正文变化 →
 * stale/confirm 409，不隐式重生成。
 */
@TestPropertySource(properties = {"creation.studio.writes-enabled=true"})
class ArticleVisualPlanIT extends IntelligenceItSupport {

	private static final String ACCOUNT = "00000000-0000-4000-8000-00000000060e";

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

	private String draftId;
	private int draftVersion;

	@BeforeEach
	void seed() {
		FINANCE.resetAll();
		FINANCE.stubFor(com.github.tomakehurst.wiremock.client.WireMock
				.get(urlEqualTo("/internal/marketplace/reputation/" + ACCOUNT + "/ai-entitlement"))
				.willReturn(okJson("{\"success\":true,\"data\":{\"accountId\":\"" + ACCOUNT
						+ "\",\"aiQuotaMultiplierBps\":10000,\"policyVersion\":1}}")));
		FINANCE.stubFor(post(urlEqualTo("/internal/credits/consume")).willReturn(okJson(
				"{\"success\":true,\"data\":{\"source\":\"quota\",\"policyVersion\":1,\"transactionId\":\"11111111-1111-4111-8111-111111111111\"}}")));
		FINANCE.stubFor(
				post(urlEqualTo("/internal/credits/consume-compensations")).willReturn(aResponse().withStatus(200)));
		db.sql("DELETE FROM creation_visual_artifact").then().then(db.sql("DELETE FROM creation_visual_item").then())
				.then(db.sql("DELETE FROM card_series_operation WHERE api_version = 2").then())
				.then(db.sql("DELETE FROM creation_visual_quote").then())
				.then(db.sql("DELETE FROM creation_visual_plan_revision").then())
				.then(db.sql("DELETE FROM creation_visual_plan").then())
				.then(db.sql("DELETE FROM creation_source_document").then())
				.then(db.sql("DELETE FROM creation_draft WHERE owner_account_id = :a").bind("a", ACCOUNT).then())
				.block(java.time.Duration.ofSeconds(10));
		attachPlatformTextCredential();
		QWEN.resetAll();
	}

	private String createDraft(String platform, String contentMode) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceType", "independent");
		body.put("title", "文章配图 IT 草稿");
		body.put("platform", platform);
		body.put("contentForm", "graphic");
		body.put("capability", "article");
		body.put("contentMode", contentMode);
		body.put("questionText", "如何把一家街边面馆做成排队店？");
		body.put("articleTitle", "街边面馆的三年排队生意");
		body.put("content", ARTICLE_TEXT);
		Map<?, ?> response = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return ((Map<?, ?>) response.get("data")).get("id").toString();
	}

	private static final String ARTICLE_TEXT = """
			# 街边面馆的三年排队生意

			门店三年，人均 68 元，翻台四次。

			## 招牌与定价

			招牌面 32 元一碗，日销两百碗。小菜 12 元一份。

			## 出品流程

			明档现擀，三分钟出一碗。收银台只收扫码。

			## 会员与复购

			会员再省 8 元，复购率四成。""";

	// ---- helpers ----

	@SuppressWarnings("unchecked")
	private Map<String, Object> importSource() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", draftVersion);
		body.put("kind", "markdown");
		body.put("text", ARTICLE_TEXT);
		Map<?, ?> response = client().post().uri("/api/creation-studio/sources")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (Map<String, Object>) response.get("data");
	}

	/** 文章配图模型桩：封面 + 每张插图带 afterBlockId（定位真实块；关键文字取自所引块本身）。 */
	private void stubArticleModel(Map<String, Object> source, int illustrations, boolean withAfterBlockId) {
		List<Map<String, String>> blocks = (List<Map<String, String>>) (List<?>) (List<?>) source.get("blocks");
		StringBuilder items = new StringBuilder("[");
		items.append("{\"role\":\"cover\",\"title\":\"三年排队生意\",\"bullets\":[],")
				.append("\"criticalText\":[\"人均 68 元\"],")
				.append("\"illustration\":\"暖光面馆门头，蒸汽与木质招牌，排队人群虚化背景，平视构图，生活质感，光线柔和，画面完整具体，一百字左右的画面描述。\",")
				.append("\"caption\":\"\",\"purpose\":\"封面主题\",\"sourceBlockIds\":[\"").append(blocks.get(1).get("id"))
				.append("\"]}");
		for (int index = 0; index < illustrations; index++) {
			Map<String, String> block = blocks.get(Math.min(index + 2, blocks.size() - 1));
			// 关键文字取所引块的前几个字（逐字保真由服务端校验）
			String blockText = String.valueOf(block.get("text"));
			String critical = blockText.substring(0, Math.min(3, blockText.length()));
			items.append(",{\"role\":\"illustration\",\"title\":\"插图").append(index + 1)
					.append("\",\"bullets\":[],\"criticalText\":[\"").append(escape(critical)).append("\"],")
					.append("\"illustration\":\"明档厨房特写，面条与灶火，俯视构图，热气与质感细节，光线明亮，一百字左右的完整画面描述，主体明确场景具体。\",")
					.append("\"caption\":\"\",\"purpose\":\"解释第").append(index + 1)
					.append("个步骤\",\"sourceBlockIds\":[\"").append(block.get("id")).append("\"]");
			if (withAfterBlockId) {
				items.append(",\"afterBlockId\":\"").append(block.get("id")).append("\"");
			}
			items.append("}");
		}
		items.append("]");
		stubRawModel(items.toString());
	}

	private static String escape(String text) {
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private void stubCoverModel(Map<String, Object> source) {
		List<Map<String, String>> blocks = (List<Map<String, String>>) (List<?>) (List<?>) source.get("blocks");
		// 引用「招牌面 32 元一碗…」正文块（关键文字逐字可查）
		Map<String, String> noodleBlock = blocks.get(3);
		stubRawModel("[{\"role\":\"cover\",\"title\":\"招牌面 32 元\",\"bullets\":[]," + "\"criticalText\":[\"招牌面 32 元\"],"
				+ "\"illustration\":\"一碗招牌牛肉面俯拍特写，热气腾腾，深色木桌衬托，光线聚焦主体，质感细腻，一百字左右的完整画面描述，主体明确场景具体光线柔和。\","
				+ "\"caption\":\"\",\"purpose\":\"封面\",\"sourceBlockIds\":[\"" + noodleBlock.get("id") + "\"]}]");
	}

	private void stubRawModel(String itemsJson) {
		String raw = "{\"items\":" + itemsJson + ",\"explanation\":\"按段落定位\"}";
		String escaped;
		try {
			escaped = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(raw);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse()
				.withHeader("Content-Type", "application/json").withBody("{\"choices\":[{\"message\":{\"content\":"
						+ escaped + "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}")));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> preparePlan(Map<String, Object> source, String recipe, Integer itemCount) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", UUID.randomUUID().toString());
		body.put("draftId", draftId);
		body.put("expectedDraftVersion", draftVersion);
		body.put("recipe", Map.of("id", recipe, "version", "1.0.0"));
		body.put("source",
				Map.of("id", source.get("id").toString(), "contentHash", source.get("contentHash").toString()));
		body.put("selectedBlockIds", ((List<?>) source.get("blocks")).stream()
				.map(block -> ((Map<?, ?>) block).get("id").toString()).toList());
		if (itemCount != null) {
			body.put("itemCount", itemCount);
		}
		return (Map<String, Object>) ((Map<?, ?>) client().post().uri("/api/creation-studio/visual-plans")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody()).get("data");
	}

	// ---- TC101-065：文章配图——封面恰好一张，插图定位真实段落 ----

	@Test
	@SuppressWarnings("unchecked")
	void articleVisualsPlanBindsPlacementsToRealBlocks() {
		draftId = createDraft("wechat-official", "article");
		draftVersion = 1;
		Map<String, Object> source = importSource();
		// 3 个标题块 → 缺省 4 项（1 封面 + 3 插图）
		stubArticleModel(source, 3, true);
		Map<String, Object> plan = preparePlan(source, "article-visuals", 4);
		assertThat(plan.get("status")).as("plan error: %s", plan.get("error")).isEqualTo("ready");
		List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<?, ?>) plan.get("document")).get("items");
		assertThat(items).hasSize(4);
		assertThat(items.get(0).get("role")).isEqualTo("cover");
		assertThat(((Map<?, ?>) items.get(0).get("placement"))).isNull();
		List<String> blockIds = new ArrayList<>();
		for (Object block : (List<?>) source.get("blocks")) {
			blockIds.add(((Map<?, ?>) block).get("id").toString());
		}
		for (int index = 1; index < items.size(); index++) {
			Map<?, ?> item = items.get(index);
			assertThat(item.get("role")).isEqualTo("illustration");
			assertThat(item.get("purpose")).asString().isNotEmpty();
			Map<?, ?> placement = (Map<?, ?>) item.get("placement");
			assertThat(placement).isNotNull();
			assertThat(blockIds).contains(placement.get("afterBlockId").toString());
		}
		// 封面恰好一张
		assertThat(items.stream().filter(item -> "cover".equals(item.get("role"))).count()).isEqualTo(1);
	}

	// ---- TC101-065b：插图缺 afterBlockId → 模型违约（计划 failed，不做第二次修复调用） ----

	@Test
	void articleVisualsRejectsMissingPlacement() {
		draftId = createDraft("wechat-official", "article");
		draftVersion = 1;
		Map<String, Object> source = importSource();
		stubArticleModel(source, 3, false);
		Map<String, Object> plan = preparePlan(source, "article-visuals", 4);
		assertThat(plan.get("status")).isEqualTo("failed");
		assertThat(((Map<?, ?>) plan.get("error")).get("code")).isEqualTo("STUDIO_INVALID_PLAN");
		// 只调一次模型，无隐形修复
		assertThat(QWEN.getAllServeEvents()).hasSize(1);
	}

	// ---- TC101-066：知乎回答——问题与开头语义不被改写成文章标题 ----

	@Test
	void zhihuAnswerPlanKeepsQuestionSemantics() {
		draftId = createDraft("zhihu", "answer");
		draftVersion = 1;
		Map<String, Object> source = importSource();
		stubArticleModel(source, 2, true);
		Map<String, Object> plan = preparePlan(source, "article-visuals", 3);
		assertThat(plan.get("status")).as("plan error: %s", plan.get("error")).isEqualTo("ready");
		// system prompt 携带回答语义约束（不得复述/改写问题标题）
		assertThat(QWEN.findAll(com.github.tomakehurst.wiremock.client.WireMock
				.postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(containing("知乎回答配图")))).hasSize(1);
	}

	// ---- TC101-067：cover-only 恰好一张封面，无段落定位 ----

	@Test
	@SuppressWarnings("unchecked")
	void coverOnlyPlanIsSingleCover() {
		draftId = createDraft("wechat-official", "article");
		draftVersion = 1;
		Map<String, Object> source = importSource();
		stubCoverModel(source);
		Map<String, Object> plan = preparePlan(source, "cover-only", null);
		assertThat(plan.get("status")).as("plan error: %s", plan.get("error")).isEqualTo("ready");
		List<Map<String, Object>> items = (List<Map<String, Object>>) ((Map<?, ?>) plan.get("document")).get("items");
		assertThat(items).hasSize(1);
		assertThat(items.get(0).get("role")).isEqualTo("cover");
		assertThat(items.get(0).get("targetAspect")).isEqualTo("2.35:1");
	}

	// ---- TC101-068：正文变化 → stale；确认被拒，不隐式重生成 ----

	@Test
	void contentDriftMarksStaleAndBlocksConfirm() {
		draftId = createDraft("wechat-official", "article");
		draftVersion = 1;
		Map<String, Object> source = importSource();
		stubArticleModel(source, 3, true);
		Map<String, Object> plan = preparePlan(source, "article-visuals", 4);
		assertThat(plan.get("status")).as("plan error: %s", plan.get("error")).isEqualTo("ready");

		// 正文编辑（版本+1、baseContentHash 变化）
		client().put().uri("/api/creation-drafts/" + draftId).header("X-Grassland-Identity", sign(ACCOUNT, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", draftVersion, "title",
						"文章配图 IT 草稿", "content", ARTICLE_TEXT + "\n\n旺季加开二楼，等位减半。"))
				.exchange().expectStatus().isOk();
		draftVersion += 1;

		Map<?, ?> reloaded = (Map<?, ?>) ((Map<?, ?>) client().get()
				.uri("/api/creation-studio/visual-plans/" + plan.get("id"))
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody()).get("data");
		assertThat(reloaded.get("stale")).isEqualTo(true);

		// 确认被拒：必须重建或重新确认新计划（不隐式重生成）
		client().post().uri("/api/creation-studio/visual-plans/" + plan.get("id") + "/confirm")
				.header("X-Grassland-Identity", sign(ACCOUNT, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId, "expectedDraftVersion",
						draftVersion, "expectedRevision", 1, "sourceContentHash",
						((Map<?, ?>) plan.get("source")).get("contentHash")))
				.exchange().expectStatus().isEqualTo(409);
		// 没有新的模型调用（正文变化不触发重生成）
		assertThat(QWEN.getAllServeEvents()).hasSize(1);
	}
}
