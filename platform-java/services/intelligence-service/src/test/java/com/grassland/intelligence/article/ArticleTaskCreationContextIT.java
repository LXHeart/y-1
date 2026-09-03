package com.grassland.intelligence.article;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

@DisplayName("Article task-mode creation context")
class ArticleTaskCreationContextIT extends IntelligenceItSupport {
	private static final String ACCOUNT = "19191919-1919-1919-1919-191919191919";
	private static final String OTHER = "20202020-2020-2020-2020-202020202020";
	private static final String TEST_KEK_BASE64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

	@org.springframework.test.context.DynamicPropertySource
	static void kekProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("crypto.kek.encoded", () -> TEST_KEK_BASE64);
	}

	@org.springframework.beans.factory.annotation.Autowired
	org.springframework.beans.factory.ObjectProvider<com.grassland.crypto.EnvelopeEncryption> encryptionProvider;

	@MockitoBean
	CreditsClient credits;

	private String platformConfigId;

	@BeforeEach
	void cleanAndSeed() {
		reset(credits);
		when(credits.consume(anyString(), eq(CreditFeature.ARTICLE_GENERATION), anyString()))
				.thenAnswer(invocation -> Mono.just(new CreditCharge(invocation.getArgument(0),
						CreditFeature.ARTICLE_GENERATION, invocation.getArgument(2))));
		when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
		when(credits.compensate(any(), anyString())).thenReturn(Mono.empty());

		db.sql("DELETE FROM intelligence_outbox").then().block();
		db.sql("DELETE FROM ai_credit_compensation").then().block();
		db.sql("DELETE FROM ai_run").then().block();
		db.sql("DELETE FROM creation_context_snapshot").then().block();
		db.sql("DELETE FROM ai_model_budget").then().block();
		db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
		db.sql("DELETE FROM platform_model_config").then().block();
		db.sql("DELETE FROM platform_provider_credential WHERE base_url LIKE 'http://localhost:%' OR name = 'article-text'")
				.then().block();
		// 任务书 #58：平台 text 行必须带凭据密钥（无 env 兜底，密钥经 KEK 信封加密落库）
		String encryptedKey = encryptionProvider.getIfAvailable().encrypt("sk-article-text-key-1234");
		platformConfigId = db.sql("""
				WITH cred AS (
				    INSERT INTO platform_provider_credential(name, provider, base_url,
				        encrypted_key, key_version, masked_hint, enabled)
				    VALUES ('article-text', 'qwen', :baseUrl, :encryptedKey, 'v1', 'sk-***art', true)
				    RETURNING id
				)
				INSERT INTO platform_model_config(
				    capability, model_role, provider, model, base_url,
				    health_status, enabled, version, credential_id)
				SELECT 'text','primary','qwen','qwen-plus',:baseUrl,'healthy',true,7,cred.id
				FROM cred
				RETURNING id::text
				""").bind("baseUrl", QWEN.baseUrl()).bind("encryptedKey", encryptedKey)
				.map(row -> row.get("id", String.class)).one().block();
		QWEN.resetAll();
	}

	@Test
	@DisplayName("task titles use frozen rules and persist the snapshot on a completed AI run")
	void titlesUseFrozenContextAndPersistAuditLink() {
		String snapshotId = seedSnapshot(ACCOUNT, "xiaohongshu", "graphic");
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
				{"choices":[{"message":{"content":"{\\"titles\\":[{\\"title\\":\\"冻结标题\\",\\"hook\\":\\"任务规则\\"}]}"}}],
				 "usage":{"prompt_tokens":21,"completion_tokens":8}}
				""")));

		client().post().uri("/api/article-generation/titles")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"topic":"探店任务","platform":"xiaohongshu","taskMode":true,
						 "contextSnapshotId":"%s"}
						""".formatted(snapshotId)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.titles[0].title").isEqualTo("冻结标题");

		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(containing("必须包含门店名称"))
				.withRequestBody(containing("contextSnapshotId")));
		String persisted = db.sql("""
				SELECT context_snapshot_id::text || ':' || status AS audit
				FROM ai_run ORDER BY started_at DESC LIMIT 1
				""").map(row -> row.get("audit", String.class)).one().block();
		assertThat(persisted).isEqualTo(snapshotId + ":completed");
		Long completedEvents = db
				.sql("SELECT COUNT(*) AS n FROM intelligence_outbox " + "WHERE event_type='AiRunCompleted'")
				.map(row -> row.get("n", Long.class)).one().block();
		assertThat(completedEvents).isEqualTo(1);
	}

	@Test
	@DisplayName("task titles inject the selected title formula into the upstream system message")
	void taskTitlesInjectTitleFormula() {
		String snapshotId = seedSnapshot(ACCOUNT, "xiaohongshu", "graphic");
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
				{"choices":[{"message":{"content":"{\\"titles\\":[{\\"title\\":\\"3个探店要点\\",\\"hook\\":\\"数字\\"}]}"}}],
				 "usage":{"prompt_tokens":21,"completion_tokens":8}}
				""")));

		client().post().uri("/api/article-generation/titles")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"topic":"探店任务","platform":"xiaohongshu","taskMode":true,
						 "contextSnapshotId":"%s","titleFormula":"number"}
						""".formatted(snapshotId)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.titles[0].title").isEqualTo("3个探店要点");

		// 上游请求体（WireMock 实收）含注入段——任务模式同样注入（任务书 #57 决策 A）
		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(containing("【标题套路：数字型】"))
				.withRequestBody(containing("数字尽量放在标题前半段")));
	}

	@Test
	@DisplayName("task mode fails closed for missing, foreign, and platform-mismatched snapshots")
	void taskModeFailsClosed() {
		client().post().uri("/api/article-generation/titles")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"topic":"任务","platform":"xiaohongshu","taskMode":true}
						""").exchange().expectStatus().isBadRequest();

		String foreign = seedSnapshot(OTHER, "xiaohongshu", "graphic");
		taskTitles(ACCOUNT, foreign, "xiaohongshu").expectStatus().isForbidden();

		String mismatched = seedSnapshot(ACCOUNT, "zhihu", "graphic");
		taskTitles(ACCOUNT, mismatched, "wechat").expectStatus().isEqualTo(409);

		Long runs = db.sql("SELECT COUNT(*) AS n FROM ai_run").map(row -> row.get("n", Long.class)).one().block();
		assertThat(runs).isZero();
		QWEN.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
	}

	@Test
	@DisplayName("outline and content also bind the same snapshot")
	void outlineAndContentBindSnapshot() {
		String snapshotId = seedSnapshot(ACCOUNT, "wechat-official", "graphic");
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
				{"choices":[{"message":{"content":"生成内容"}}],
				 "usage":{"prompt_tokens":11,"completion_tokens":4}}
				""")));

		client().post().uri("/api/article-generation/outline")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"topic":"任务","title":"任务标题","platform":"wechat","taskMode":true,
						 "contextSnapshotId":"%s"}
						""".formatted(snapshotId)).exchange().expectStatus().isOk();
		client().post().uri("/api/article-generation/content")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"topic":"任务","title":"任务标题","outline":"一、这是足够长的任务大纲内容",
						 "platform":"wechat","taskMode":true,"contextSnapshotId":"%s"}
						""".formatted(snapshotId)).exchange().expectStatus().isOk();

		Long linkedRuns = db
				.sql("SELECT COUNT(*) AS n FROM ai_run "
						+ "WHERE context_snapshot_id=CAST(:id AS uuid) AND status='completed'")
				.bind("id", snapshotId).map(row -> row.get("n", Long.class)).one().block();
		assertThat(linkedRuns).isEqualTo(2);
	}

	@Test
	@DisplayName("snapshot provider metadata mismatch is rejected instead of using current config")
	void aiConfigurationDriftFailsClosed() {
		String snapshotId = seedSnapshot(ACCOUNT, "xiaohongshu", "graphic");
		db.sql("UPDATE platform_model_config SET model='changed-model' WHERE id=CAST(:id AS uuid)")
				.bind("id", platformConfigId).then().block();

		taskTitles(ACCOUNT, snapshotId, "xiaohongshu").expectStatus().isEqualTo(409);
		QWEN.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
	}

	@Test
	@DisplayName("抖音快照绑定走 DOUYIN 模板（任务书 #69 卡B：douyin 一等 platform 值）")
	void douyinSnapshotBindsDouyinPlatform() {
		String snapshotId = seedSnapshot(ACCOUNT, "douyin", "graphic");
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(
				okJson("""
						{"choices":[{"message":{"content":"{\\"titles\\":[{\\"title\\":\\"图集标题\\",\\"hook\\":\\"前10字抛冲突\\"}]}"}}],
						 "usage":{"prompt_tokens":21,"completion_tokens":8}}
						""")));

		taskTitles(ACCOUNT, snapshotId, "douyin").expectStatus().isOk().expectBody().jsonPath("$.data.titles[0].title")
				.isEqualTo("图集标题");

		// 上游收到的是抖音专属标题模板（而非借道小红书的笔记模板）
		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(containing("抖音图集标题策划师"))
				.withRequestBody(notContaining("小红书")));
	}

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec taskTitles(String accountId,
			String snapshotId, String platform) {
		return client().post().uri("/api/article-generation/titles")
				.header("X-Grassland-Identity", sign(accountId, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"topic":"任务","platform":"%s","taskMode":true,"contextSnapshotId":"%s"}
						""".formatted(platform, snapshotId)).exchange();
	}

	private String seedSnapshot(String accountId, String platform, String form) {
		return db.sql("""
				INSERT INTO creation_context_snapshot(
				    account_id, task_id, application_id, task_version, platform_id, content_form_id,
				    task_snapshot, platform_rules_snapshot, material_snapshot, ai_config_snapshot)
				VALUES (:account,:task,:application,3,:platform,:form,
				    '{"title":"探店任务","requirements":"必须包含门店名称"}'::jsonb,
				    '{"version":"2026-08-06","maxChars":1000}'::jsonb,
				    '{"items":[]}'::jsonb,
				    jsonb_build_object('resolutionType','PLATFORM','configId',:configId,
				        'provider','qwen','model','qwen-plus','platformModelVersion',7,
				        'modelRole','primary'))
				RETURNING id::text
				""").bind("account", accountId).bind("task", UUID.randomUUID().toString())
				.bind("application", UUID.randomUUID().toString()).bind("platform", platform).bind("form", form)
				.bind("configId", platformConfigId).map(row -> row.get("id", String.class)).one().block();
	}

	// ---------- 任务书 #62：任务携带目标问题 ----------

	@Test
	@DisplayName("zhihu 任务快照含 questionText → 生成时以快照为准，忽略请求体 question")
	void frozenQuestionOverridesRequestBody() {
		String snapshotId = seedZhihuAnswerSnapshot(ACCOUNT, "任务冻结的目标问题");
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
				{"choices":[{"message":{"content":"{\\"titles\\":[{\\"title\\":\\"开头候选\\",\\"hook\\":\\"h\\"}]}"}}],
				 "usage":{"prompt_tokens":21,"completion_tokens":8}}
				""")));

		client().post().uri("/api/article-generation/titles")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"platform":"zhihu","taskMode":true,"contextSnapshotId":"%s",
						 "answerMode":true,"question":"前端伪造的问题"}
						""".formatted(snapshotId)).exchange().expectStatus().isOk();

		// 上游实收：回答体 prompt + 快照问题；请求体伪造的问题不得出现
		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(containing("回答开头候选"))
				.withRequestBody(containing("任务冻结的目标问题")).withRequestBody(notContaining("前端伪造的问题")));
	}

	@Test
	@DisplayName("zhihu 任务快照无 questionText → 回落请求体 question（自由填写的回答任务）")
	void requestQuestionUsedWhenSnapshotHasNone() {
		String snapshotId = seedSnapshot(ACCOUNT, "zhihu", "graphic");
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
				{"choices":[{"message":{"content":"{\\"titles\\":[{\\"title\\":\\"开头\\",\\"hook\\":\\"h\\"}]}"}}],
				 "usage":{"prompt_tokens":21,"completion_tokens":8}}
				""")));

		client().post().uri("/api/article-generation/titles")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"platform":"zhihu","taskMode":true,"contextSnapshotId":"%s",
						 "answerMode":true,"question":"用户自填的问题"}
						""".formatted(snapshotId)).exchange().expectStatus().isOk();

		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(containing("用户自填的问题")));
	}

	@Test
	@DisplayName("回归：zhihu 任务不带 answerMode → 文章体 prompt，快照 question 不参与")
	void taskArticleModeUnaffectedByFrozenQuestion() {
		String snapshotId = seedZhihuAnswerSnapshot(ACCOUNT, "任务冻结的目标问题");
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(okJson("""
				{"choices":[{"message":{"content":"{\\"titles\\":[{\\"title\\":\\"文章标题\\",\\"hook\\":\\"h\\"}]}"}}],
				 "usage":{"prompt_tokens":21,"completion_tokens":8}}
				""")));

		client().post().uri("/api/article-generation/titles")
				.header("X-Grassland-Identity", sign(ACCOUNT, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"topic":"云原生成本","platform":"zhihu","taskMode":true,"contextSnapshotId":"%s"}
						""".formatted(snapshotId)).exchange().expectStatus().isOk();

		QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(containing("知乎专栏文章标题策划师"))
				.withRequestBody(containing("主题：云原生成本")));
	}

	/** zhihu graphic 快照 + taskSnapshot 携带 questionText（模拟商家任务指定回答形态）。 */
	private String seedZhihuAnswerSnapshot(String accountId, String questionText) {
		return db.sql("""
				INSERT INTO creation_context_snapshot(
				    account_id, task_id, application_id, task_version, platform_id, content_form_id,
				    task_snapshot, platform_rules_snapshot, material_snapshot, ai_config_snapshot)
				VALUES (:account,:task,:application,3,'zhihu','graphic',
				    jsonb_build_object('title','知乎回答任务','requirements','必须包含门店名称',
				        'questionText',:questionText,'questionRef','1999041081275355787'),
				    '{"version":"2026-08-06","maxChars":3000}'::jsonb,
				    '{"items":[]}'::jsonb,
				    jsonb_build_object('resolutionType','PLATFORM','configId',:configId,
				        'provider','qwen','model','qwen-plus','platformModelVersion',7,
				        'modelRole','primary'))
				RETURNING id::text
				""").bind("account", accountId).bind("task", UUID.randomUUID().toString())
				.bind("application", UUID.randomUUID().toString()).bind("questionText", questionText)
				.bind("configId", platformConfigId).map(row -> row.get("id", String.class)).one().block();
	}
}
