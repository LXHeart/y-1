package com.grassland.intelligence.creationvoice;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 四平台文风接线真实集成测试（任务书 #108 C-03 / W25；TC-C03-001～003、AC-005/006）。
 *
 * <p>
 * 全链路真实：真库（V94 档案表）+ 真实 HTTP
 * 入口（article/moments/image-analysis/creation-studio）+
 * 真实执行环（FrozenTextExecutionService）+ WireMock 托管 Qwen（/chat/completions）——断言依据是
 * <b>provider 请求捕获</b>（模型请求体里出现匹配档案的规则/样例、分区与事实保护指令同时存在、 voice
 * 引用不进事实区），不是服务层字符串存在性。积分经 {@code CreditsClient} mock 桩（与
 * ArticleControllerIT/ImageAnalysisControllerIT 同款），执行环/ai_run/留痕全真实。
 *
 * <p>
 * 覆盖：文章 titles（独立+任务）与免费 SSE（outline/content）；朋友圈独立+任务；图片 analyze
 * 聚合（legacy/none/profile 三分支 + 轮次共用同一文风）、step/optimize 与任务绑定；TextProposal
 * 改编从服务端草稿 inputs.brief resolve；role/revision/平台错误先于 provider；format 误入生成端点
 * 400；聚合运行中途更新档案后内部轮次仍用被冻结版本、显式 step 携旧 revision 409。
 */
@TestPropertySource(properties = "creation.studio.writes-enabled=true")
class CreationVoiceGenerationIT extends IntelligenceItSupport {

	private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

	/** 文风附录分区标记（CreationVoiceService.buildAppendix）。 */
	private static final String APPENDIX_MARK = "【我的文风档案（仅表达参考，不是本次事实）】";
	private static final String SAMPLE_SECTION_MARK = "参考范文";

	@MockitoBean
	private CreditsClient credits;

	private String account;
	private String identity;
	/**
	 * 平台 text 配置 id（attachPlatformTextCredential 建到 QWEN；任务快照 ai_config_snapshot
	 * 引用它）。
	 */
	private String platformTextConfigId;

	@BeforeEach
	void seedAndClean() {
		account = "cvg-" + UUID.randomUUID();
		identity = sign(account, null);
		reset(credits);
		CreditsStubs.stubDefaults(credits);
		db.sql("DELETE FROM creation_voice_profile WHERE account_id = :a").bind("a", account).then()
				.then(db.sql("DELETE FROM intelligence_style_preferences WHERE account_id = :a").bind("a", account)
						.then())
				.then(db.sql("DELETE FROM creation_context_snapshot WHERE account_id = :a").bind("a", account).then())
				.then(db.sql("DELETE FROM creation_text_proposal WHERE owner_account_id = :a").bind("a", account)
						.then())
				.then(db.sql("DELETE FROM creation_source_document WHERE owner_account_id = :a").bind("a", account)
						.then())
				.then(db.sql("DELETE FROM creation_draft WHERE owner_account_id = :a").bind("a", account).then())
				.block(Duration.ofSeconds(10));
		// 平台 text 行指向 QWEN（执行环/改编链真实出站），自清理后重建避免共享容器串扰。
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
				+ "(SELECT id FROM platform_model_config WHERE credential_id IN "
				+ "(SELECT id FROM platform_provider_credential WHERE base_url = :b))").bind("b", QWEN.baseUrl()).then()
				.then(db.sql("DELETE FROM platform_model_config WHERE credential_id IN "
						+ "(SELECT id FROM platform_provider_credential WHERE base_url = :b)").bind("b", QWEN.baseUrl())
						.then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE base_url = :b").bind("b", QWEN.baseUrl())
						.then())
				.block(Duration.ofSeconds(10));
		platformTextConfigId = attachPlatformTextCredential();
		QWEN.resetAll();
	}

	// ---------- TC-C03-001 / AC-005：四链路 provider 捕获 ----------

	@Test
	void previewUsesRoutedProviderButDoesNotPersistUntilPut() {
		stubCompletion("[\"少用感叹号\"]");
		client().post().uri("/api/creation-voice/consumer/preview").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("original", "很好！！！", "edited", "很好。", "reason", "style", "platform", "zhihu", "genre",
						"article"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.candidates[0]").isEqualTo("少用感叹号");
		client().get().uri("/api/creation-voice/consumer").header("X-Grassland-Identity", identity).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.revision").isEqualTo(0);
	}

	@Test
	@DisplayName("文章 titles 独立与任务模式：provider 捕获到匹配档案规则与平台体裁样例")
	void articleTitlesCarryMatchedVoiceForIndependentAndTask() {
		putProfile("consumer", 0, true, List.of("句子长短错落，少用感叹号"), List
				.of(sample("zhihu", "article", "知乎长文样例：先给结论，再给依据。"), sample("dianping", "note", "点评笔记样例不该被文章链路选中")));
		stubCompletion("{\"titles\":[{\"title\":\"拿铁探店\",\"hook\":\"价格与安静\"}]}");

		// 独立模式（计费经执行环；voice 解析错误先于扣费与模型）。
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "A店拿铁", "platform", "zhihu", "brief",
						profileBrief("consumer", 1, "周六到A店，拿铁38元，二楼安静")))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.titles[0].title").isEqualTo("拿铁探店");
		assertThat(requestBodies()).hasSize(1);
		assertThat(lastBody()).contains("句子长短错落，少用感叹号").contains("知乎长文样例").contains("拿铁38元").doesNotContain("点评笔记样例")
				.contains(APPENDIX_MARK).contains(SAMPLE_SECTION_MARK).doesNotContain("\"voice\"");

		// 任务模式（冻结快照 zhihu + brief.voice → resolve → 执行环）。
		String snapshotId = seedSnapshot("zhihu", "graphic");
		QWEN.resetRequests();
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "A店拿铁", "platform", "zhihu", "taskMode", true, "contextSnapshotId",
						snapshotId, "brief", profileBrief("consumer", 1, "周六到A店，拿铁38元，二楼安静")))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.titles[0].hook").isEqualTo("价格与安静");
		assertThat(requestBodies()).hasSize(1);
		assertThat(lastBody()).contains("句子长短错落，少用感叹号").contains("知乎长文样例").contains("必须遵守任务要求和平台规则")
				.doesNotContain("点评笔记样例");
	}

	@Test
	@DisplayName("文章免费 SSE（outline/content）：同一次流式请求携带文风附录且只出现一段，SSE 帧契约不变")
	void articleFreeSseCarriesVoiceAppendixOnce() {
		putProfile("consumer", 0, true, List.of("先给结论再给依据"), List.of());
		stubStreaming("第一段提纲");

		String outline = client().post().uri("/api/article-generation/outline").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "A店拿铁", "title", "拿铁探店", "platform", "xiaohongshu", "brief",
						profileBrief("consumer", 1, "周六到A店，拿铁38元")))
				.exchange().expectStatus().isOk().expectHeader().contentType(MediaType.TEXT_EVENT_STREAM)
				.expectBody(String.class).returnResult().getResponseBody();
		assertThat(outline).contains("\"content\"").contains("第一段提纲").doesNotContain("\"error\"");
		assertThat(countOf(lastBody(), APPENDIX_MARK)).isEqualTo(1);
		assertThat(lastBody()).contains("先给结论再给依据").doesNotContain("\"voice\"");

		stubStreaming("正文第一段。");
		String content = client().post().uri("/api/article-generation/content").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "A店拿铁", "title", "拿铁探店", "outline", "开头段与两个分论点，合计三段结构", "platform",
						"xiaohongshu", "brief", profileBrief("consumer", 1, "周六到A店，拿铁38元")))
				.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		assertThat(content).contains("\"content\"").contains("正文第一段。").doesNotContain("\"error\"");
		assertThat(countOf(lastBody(), APPENDIX_MARK)).isEqualTo(1);
		assertThat(lastBody()).contains("先给结论再给依据");
	}

	@Test
	@DisplayName("朋友圈独立与任务：moments/short-post 匹配样例进入 provider 请求")
	void momentsCarryVoiceForIndependentAndTask() {
		putProfile("merchant", 0, true, List.of("口语短句，不堆形容词"),
				List.of(sample("moments", "short-post", "朋友圈样例：三行说完，配图走起。")));
		stubCompletion("{\"copy\":\"开业第一天，二楼坐满了。\",\"imageOrder\":[],\"captions\":[]}");

		client().post().uri("/api/moments-generation/generate").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "新店开业", "style", "lifestyle", "brief",
						profileBrief("merchant", 1, "本店拿铁38元，二楼有座位")))
				.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		assertThat(requestBodies()).hasSize(1);
		assertThat(lastBody()).contains("口语短句，不堆形容词").contains("朋友圈样例").contains("本店拿铁38元").contains(APPENDIX_MARK)
				.doesNotContain("\"voice\"");

		String snapshotId = seedSnapshot("moments", "image-text");
		QWEN.resetRequests();
		client().post().uri("/api/moments-generation/generate").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "新店开业", "style", "lifestyle", "taskMode", true, "contextSnapshotId",
						snapshotId, "brief", profileBrief("merchant", 1, "本店拿铁38元，二楼有座位")))
				.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		assertThat(requestBodies()).hasSize(1);
		assertThat(lastBody()).contains("口语短句，不堆形容词").contains("权威朋友圈图文任务上下文");
	}

	@Test
	@DisplayName("图片 analyze 三分支：缺省走旧偏好、显式 none 不回落、profile 替代旧附录且各轮共用同一文风")
	void imageAnalyzeVoiceBranchesReplaceLegacyAppendix() {
		// 旧偏好存在（legacy 缺省分支仍生效——旧客户端回归红线）。
		client().put().uri("/api/image-analysis/style-preferences").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("preferences", List.of("旧版偏好：结尾带表情")))
				.exchange().expectStatus().isOk();
		putProfile("consumer", 0, true, List.of("只用确认过的信息写体验"), List.of(sample("dianping", "note", "点评样例：拿铁好喝，楼梯窄。")));
		stubCompletion("{\"review\":\"周六到A店，拿铁38元，二楼安静。\",\"title\":\"t\",\"tags\":[]}");

		// 1) 不传 voice 的旧请求：旧偏好附录仍在（TC-C03-001 回归）。
		client().post().uri("/api/image-analysis/analyze").header("X-Grassland-Identity", identity)
				.contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(analyzeForm("dianping", null, null, null))
				.exchange().expectStatus().isOk();
		assertThat(lastBody()).contains("旧版偏好：结尾带表情").doesNotContain(APPENDIX_MARK);

		// 2) 显式 none：不查旧偏好、不读档案。
		QWEN.resetRequests();
		client().post().uri("/api/image-analysis/analyze").header("X-Grassland-Identity", identity)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(analyzeForm("dianping", Map.of("voice", Map.of("mode", "none")), null, null)).exchange()
				.expectStatus().isOk();
		assertThat(requestBodies()).hasSize(2); // 无风格附录 → draft+optimize 两轮
		assertThat(lastBody()).doesNotContain("旧版偏好").doesNotContain(APPENDIX_MARK);

		// 3) profile：替代旧账号附录；聚合 3 轮（draft/optimize/style-refine）共用同一文风段。
		QWEN.resetRequests();
		client().post().uri("/api/image-analysis/analyze").header("X-Grassland-Identity", identity)
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.bodyValue(analyzeForm("dianping", profileBrief("consumer", 1, "周六到A店，拿铁38元"), null, null)).exchange()
				.expectStatus().isOk();
		List<String> bodies = requestBodies();
		assertThat(bodies).hasSize(3); // 附录非空 → style-refine 轮启用（费用/轮次机制不变）
		for (String body : bodies) {
			assertThat(body).contains("只用确认过的信息写体验").contains("点评样例").doesNotContain("旧版偏好").contains(APPENDIX_MARK);
			assertThat(countOf(body, APPENDIX_MARK)).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("图片 step/optimize 与任务绑定：profile 进入 JSON 步骤与冻结任务分支")
	void imageStepAndTaskBindingUseVoice() {
		putProfile("consumer", 0, true, List.of("短句收尾"), List.of());
		stubCompletion("{\"review\":\"优化后的评价。\",\"title\":null,\"tags\":[]}");

		client().post().uri("/api/image-analysis/step/optimize").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("review", "待优化评价", "platform", "dianping", "brief",
						profileBrief("consumer", 1, "周六到A店，拿铁38元")))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.review").isEqualTo("优化后的评价。");
		assertThat(lastBody()).contains("短句收尾").contains(APPENDIX_MARK);

		// 任务绑定（冻结 dianping 快照）：profile 分支生效，task context 进入请求。
		String snapshotId = seedSnapshot("dianping", "graphic");
		QWEN.resetRequests();
		client().post().uri("/api/image-analysis/step/optimize").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("review", "待优化评价", "platform", "dianping", "taskMode", true, "contextSnapshotId",
						snapshotId, "brief", profileBrief("consumer", 1, "周六到A店，拿铁38元")))
				.exchange().expectStatus().isOk();
		assertThat(requestBodies()).hasSize(1);
		assertThat(lastBody()).contains("短句收尾").contains("权威图文任务上下文");
	}

	// ---------- W24：TextProposal 改编从服务端草稿 resolve ----------

	@Test
	@DisplayName("文章改编：从草稿 inputs.brief 读取选择与事实后 resolve，原稿事实与档案分区同时进入模型")
	void textProposalAdaptResolvesVoiceFromServerDraftBrief() throws Exception {
		putProfile("consumer", 0, true, List.of("价格紧跟数字写"), List.of(sample("zhihu", "article", "知乎样例：结论先行。")));
		stubCompletion("{\"title\":null,\"body\":\"改编后的正文。\",\"changes\":[\"保留事实\"]}");

		// 草稿：platform zhihu + inputs.brief（事实 + voice profile + adapt
		// 模式标记）。平台来自草稿，不从请求覆盖。
		Map<String, Object> draftBrief = new LinkedHashMap<>(profileBrief("consumer", 1, "门店三年，人均68元，招牌面32元"));
		draftBrief.put("processingMode", "adapt");
		Map<String, Object> workspace = Map.of("capability", "article", "inputs", Map.of("brief", draftBrief));
		Map<String, Object> draftBody = new LinkedHashMap<>();
		draftBody.put("sourceType", "independent");
		draftBody.put("title", "文风改编 IT 草稿");
		draftBody.put("platform", "zhihu");
		draftBody.put("contentForm", "graphic");
		draftBody.put("capability", "article");
		draftBody.put("content", "第一段：门店三年，人均 68 元。\n\n第二段：招牌面 32 元，日销两百碗。");
		draftBody.put("workspace", workspace);
		Map<String, Object> created = client().post().uri("/api/creation-drafts")
				.header("X-Grassland-Identity", identity).contentType(MediaType.APPLICATION_JSON).bodyValue(draftBody)
				.exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		String draftId = ((Map<?, ?>) created.get("data")).get("id").toString();
		int version = (Integer) ((Map<?, ?>) created.get("data")).get("version");

		Map<String, Object> source = (Map<String, Object>) ((Map<?, ?>) client().post()
				.uri("/api/creation-studio/sources").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId, "expectedDraftVersion",
						version, "kind", "draft-content"))
				.exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody()
				.get("data"));
		List<String> blockIds = ((List<?>) source.get("blocks")).stream()
				.map(block -> ((Map<?, ?>) block).get("id").toString()).toList();

		client().post().uri("/api/creation-studio/text-proposals").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", UUID.randomUUID().toString(), "draftId", draftId, "expectedDraftVersion",
						version, "action", "adapt-body", "source",
						Map.of("id", source.get("id"), "contentHash", source.get("contentHash")), "selectedBlockIds",
						blockIds, "instructions", "更自然一点"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("ready");

		assertThat(requestBodies()).hasSize(1);
		assertThat(lastBody()).contains("价格紧跟数字写").contains("知乎样例").contains("人均 68 元").contains(APPENDIX_MARK)
				.doesNotContain("\"voice\"").contains("本次为改编");
	}

	// ---------- TC-C03-002 / AC-006：错误先于 provider、事实分区保护 ----------

	@Test
	@DisplayName("role 不一致/自定义身份/过期 revision/缺槽/停用/不支持平台：全部在 provider 前拒绝")
	void voiceErrorsRejectedBeforeProviderCall() {
		putProfile("consumer", 0, true, List.of("规则一"), List.of());
		stubCompletion("{\"titles\":[{\"title\":\"t\",\"hook\":\"h\"}]}");

		// profile 角色与 authorRole 不一致 → 409 VOICE_ROLE_MISMATCH。
		postTitles_expectError(Map.of("authorRole", "merchant", "facts", List.of(Map.of("statement", "周六到A店")), "voice",
				Map.of("mode", "profile", "role", "consumer", "revision", 1)), 409, "VOICE_ROLE_MISMATCH");
		// 自定义 authorRole（非四值）+ profile → 409。
		postTitles_expectError(
				Map.of("authorRole", "teacher", "voice", Map.of("mode", "profile", "role", "consumer", "revision", 1)),
				409, "VOICE_ROLE_MISMATCH");
		// 过期 revision → 409 VOICE_REVISION_CONFLICT（RULE-010 不默默退回）。
		postTitles_expectError(profileBrief("consumer", 99, "事实"), 409, "VOICE_REVISION_CONFLICT");
		// 缺槽角色 → 409。
		postTitles_expectError(profileBrief("researcher", 1, "事实"), 409, "VOICE_REVISION_CONFLICT");
		// 停用档案 → 409。
		putProfile("commercial-creator", 0, false, List.of("停用规则"), List.of());
		postTitles_expectError(withAuthorRole(profileBrief("commercial-creator", 1, "事实"), "commercial-creator"), 409,
				"VOICE_REVISION_CONFLICT");
		// 非支持平台（article wechat）+ profile → 400 VOICE_INVALID_INPUT。
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "A店拿铁", "platform", "wechat", "brief",
						withAuthorRole(profileBrief("consumer", 1, "事实"), "consumer")))
				.exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code")
				.isEqualTo("VOICE_INVALID_INPUT");

		assertThat(QWEN.getAllServeEvents()).isEmpty(); // 全部先于模型调用
	}

	@Test
	@DisplayName("voice 判别联合严校验与 format 误入生成端点：统一 400 INVALID_CREATION_BRIEF")
	void malformedVoiceAndFormatBriefRejected() {
		List<Map<String, Object>> badVoices = List.of(Map.of("mode", "none", "role", "consumer"), // none 附 role
				Map.of("mode", "none", "revision", 1), // none 附 revision
				Map.of("mode", "profile", "role", "consumer"), // profile 缺 revision
				Map.of("mode", "profile", "role", "consumer", "revision", 0), // revision<1
				Map.of("mode", "profile", "role", "admin", "revision", 1), // 非法 role
				Map.of("mode", "profile", "role", "consumer", "revision", 1.5), // 非整数
				Map.of("mode", "profile", "role", "consumer", "revision", 1, "extra", 1)); // 未知字段
		for (Map<String, Object> voice : badVoices) {
			postTitles_expectError(Map.of("voice", voice), 400, "INVALID_CREATION_BRIEF");
		}
		// voice:null 与非对象（null 不等同缺省，必须拒绝）。
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(
						"{\"topic\":\"A店拿铁\",\"platform\":\"zhihu\",\"brief\":{\"authorRole\":\"consumer\",\"voice\":null}}")
				.exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code")
				.isEqualTo("INVALID_CREATION_BRIEF");
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"topic\":\"A店拿铁\",\"platform\":\"zhihu\",\"brief\":{\"voice\":\"profile\"}}").exchange()
				.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_CREATION_BRIEF");

		// format 误入四条生成端点（article titles / moments / image step-optimize / image
		// analyze multipart）。
		Map<String, Object> formatBrief = Map.of("processingMode", "format");
		postTitles_expectError(formatBrief, 400, "INVALID_CREATION_BRIEF");
		client().post().uri("/api/moments-generation/generate").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "开业", "style", "lifestyle", "brief", formatBrief)).exchange().expectStatus()
				.isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_CREATION_BRIEF");
		client().post().uri("/api/image-analysis/step/optimize").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("review", "评价内容", "platform", "dianping", "brief", formatBrief)).exchange()
				.expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("INVALID_CREATION_BRIEF");
		client().post().uri("/api/image-analysis/analyze").header("X-Grassland-Identity", identity)
				.contentType(MediaType.MULTIPART_FORM_DATA).bodyValue(analyzeForm("dianping", formatBrief, null, null))
				.exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.code")
				.isEqualTo("INVALID_CREATION_BRIEF");

		assertThat(QWEN.getAllServeEvents()).isEmpty();
	}

	@Test
	@DisplayName("样例与事实分区：范文价格不进事实区、事实保护指令与原始材料仍存在（A+B+C 材料）")
	void sampleFactsPartitionAndProtectionInstructionsRetained() throws Exception {
		putProfile("consumer", 0, true, List.of("表达规则R1"),
				List.of(sample("xiaohongshu", "article", "去年去B店，咖啡36元，居然没人排队")));
		stubCompletion("{\"titles\":[{\"title\":\"t\",\"hook\":\"h\"}]}");

		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "A店拿铁", "platform", "xiaohongshu",
						"brief", profileBrief("consumer", 1, "周六到A店，拿铁38元，二楼安静")))
				.exchange().expectStatus().isOk();

		String body = lastBody();
		// 事实区（简报渲染）含 38 元与事实保护指令；voice 引用不进事实区。
		assertThat(body).contains("拿铁38元").contains("创作简报（用户提供的资料与表达要求").contains("仅 user-confirmed 的体验可写成作者自述")
				.doesNotContain("\"voice\"");
		// 样例只在参考区（36 元出现在范文分区内，且位于事实区之后），不写入 facts。
		int facts = body.indexOf("拿铁38元");
		int sampleSection = body.indexOf(SAMPLE_SECTION_MARK);
		int sampleText = body.indexOf("咖啡36元");
		assertThat(sampleSection).isGreaterThan(facts);
		assertThat(sampleText).isGreaterThan(sampleSection);
		// 附录自带范文不贡献事实的保护指令。
		assertThat(body).contains("都不是本次材料");
	}

	// ---------- TC-C03-003 / AC-006：聚合冻结与显式 step 过期 ----------

	@Test
	@DisplayName("聚合 analyze 首轮后更新档案：内部轮次仍用被冻结版本；显式 step 携旧 revision 409")
	void aggregateAnalyzeFreezesVoiceAndStaleStepConflicts() throws Exception {
		putProfile("consumer", 0, true, List.of("冻结期规则A"), List.of());
		String reviewJson = "{\"review\":\"评价。\",\"title\":null,\"tags\":[]}";
		// 场景桩：首轮延迟响应，为档案更新留出窗口；后续轮次立即响应。
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).inScenario("freeze").whenScenarioStateIs("Started")
				.willReturn(okJson(completionBody(reviewJson)).withFixedDelay(1500)).willSetStateTo("warmed"));
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).inScenario("freeze").whenScenarioStateIs("warmed")
				.willReturn(okJson(completionBody(reviewJson))));

		Thread updater = new Thread(() -> {
			awaitProviderRequest(Duration.ofSeconds(10));
			// 首轮请求已抵达（resolve 已完成）→ 档案更新为 rev2/规则B。
			client().put().uri("/api/creation-voice/consumer").header("X-Grassland-Identity", identity)
					.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedRevision", 1, "enabled", true,
							"rules", List.of("更新后规则B"), "samples", List.of()))
					.exchange().expectStatus().isOk();
		});
		updater.start();
		try {
			client().post().uri("/api/image-analysis/analyze").header("X-Grassland-Identity", identity)
					.contentType(MediaType.MULTIPART_FORM_DATA)
					.bodyValue(analyzeForm("dianping", profileBrief("consumer", 1, "周六到A店，拿铁38元"), null, null))
					.exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
		} finally {
			updater.join(15000);
		}

		List<String> bodies = requestBodies();
		assertThat(bodies).hasSize(3); // 单次聚合运行 3 轮，不因接线新增/重扣轮次
		for (String body : bodies) {
			assertThat(body).contains("冻结期规则A").doesNotContain("更新后规则B");
		}

		// 显式 step 请求携旧 revision → 409（新请求须重新读取，不承诺跨请求快照）。
		stubCompletion(reviewJson);
		QWEN.resetRequests();
		client().post().uri("/api/image-analysis/step/optimize").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("review", "评价。", "platform", "dianping", "brief",
						profileBrief("consumer", 1, "周六到A店，拿铁38元")))
				.exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code")
				.isEqualTo("VOICE_REVISION_CONFLICT");
		assertThat(QWEN.getAllServeEvents()).isEmpty();
	}

	// ---------- helpers ----------

	private void postTitles_expectError(Map<String, Object> brief, int status, String code) {
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "A店拿铁", "platform", "zhihu", "brief", brief)).exchange().expectStatus()
				.isEqualTo(status).expectBody().jsonPath("$.code").isEqualTo(code);
	}

	/** brief = 事实 + authorRole + voice profile（authorRole 默认与 role 一致）。 */
	private static Map<String, Object> profileBrief(String role, long revision, String fact) {
		Map<String, Object> brief = new LinkedHashMap<>();
		if (fact != null) {
			brief.put("facts", List.of(Map.of("statement", fact, "basis", "user-confirmed")));
		}
		brief.put("authorRole", role);
		brief.put("voice", Map.of("mode", "profile", "role", role, "revision", revision));
		return brief;
	}

	private static Map<String, Object> withAuthorRole(Map<String, Object> brief, String role) {
		Map<String, Object> copy = new LinkedHashMap<>(brief);
		copy.put("authorRole", role);
		return copy;
	}

	private static Map<String, Object> sample(String platform, String genre, String text) {
		return Map.of("id", UUID.randomUUID().toString(), "platform", platform, "genre", genre, "text", text, "consent",
				true);
	}

	/** PUT /api/creation-voice/{role}（断言 200 + revision=expected+1）。 */
	private void putProfile(String role, long expectedRevision, boolean enabled, List<String> rules,
			List<Map<String, Object>> samples) {
		client().put().uri("/api/creation-voice/" + role).header("X-Grassland-Identity", identity)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedRevision", expectedRevision, "enabled", enabled, "rules", rules, "samples",
						samples))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.revision")
				.isEqualTo((int) expectedRevision + 1);
	}

	/**
	 * 任务快照：ai_config_snapshot 从真实平台 text 配置行冻结（configId/version/provider/model 一致）。
	 */
	private String seedSnapshot(String platform, String form) {
		return db.sql("""
				INSERT INTO creation_context_snapshot(
				    account_id, task_id, application_id, task_version, platform_id, content_form_id,
				    task_snapshot, platform_rules_snapshot, material_snapshot, ai_config_snapshot)
				SELECT :account, :task, :application, 3, :platform, :form,
				    '{"title":"任务","requirements":"要求"}'::jsonb,
				    '{"version":"v"}'::jsonb, '{"items":[]}'::jsonb,
				    jsonb_build_object('resolutionType','PLATFORM','configId',c.id,
				        'provider',c.provider,'model',c.model,'platformModelVersion',c.version,
				        'modelRole',c.model_role)
				FROM platform_model_config c
				WHERE c.id::text = :configId
				RETURNING id::text
				""").bind("account", account).bind("task", UUID.randomUUID().toString())
				.bind("application", UUID.randomUUID().toString()).bind("platform", platform).bind("form", form)
				.bind("configId", platformTextConfigId).map(row -> row.get("id", String.class)).one()
				.block(Duration.ofSeconds(10));
	}

	private static org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> analyzeForm(
			String platform, Map<String, Object> brief, String taskMode, String snapshotId) {
		MultipartBodyBuilder b = new MultipartBodyBuilder();
		b.part("reviewLength", "100").contentType(MediaType.TEXT_PLAIN);
		b.part("platform", platform).contentType(MediaType.TEXT_PLAIN);
		if (brief != null) {
			b.part("brief", writeJson(brief)).contentType(MediaType.TEXT_PLAIN);
		}
		if (taskMode != null) {
			b.part("taskMode", taskMode).contentType(MediaType.TEXT_PLAIN);
		}
		if (snapshotId != null) {
			b.part("contextSnapshotId", snapshotId).contentType(MediaType.TEXT_PLAIN);
		}
		b.part("images", pngResource()).contentType(MediaType.IMAGE_PNG);
		return b.build();
	}

	private static ByteArrayResource pngResource() {
		return new ByteArrayResource(new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01, 0x02}) {
			@Override
			public String getFilename() {
				return "photo.png";
			}
		};
	}

	private static String writeJson(Object value) {
		try {
			return MAPPER.writeValueAsString(value);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private static String completionBody(String modelContent) {
		return "{\"choices\":[{\"message\":{\"content\":" + writeJson(modelContent)
				+ "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
	}

	private void stubCompletion(String modelContent) {
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(200)
				.withHeader("Content-Type", "application/json").withBody(completionBody(modelContent))));
	}

	/** 免费流式链路（outline/content）：SSE chunk + [DONE]。 */
	private void stubStreaming(String chunk) {
		String body = "data: {\"choices\":[{\"delta\":{\"content\":" + writeJson(chunk) + "}}]}\n\n"
				+ "data: [DONE]\n\n";
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(
				aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(body)));
	}

	private List<String> requestBodies() {
		List<String> bodies = new ArrayList<>();
		for (var event : QWEN.getAllServeEvents()) {
			byte[] body = event.getRequest().getBody();
			if (body != null) {
				bodies.add(new String(body, java.nio.charset.StandardCharsets.UTF_8));
			}
		}
		return bodies;
	}

	private String lastBody() {
		List<String> bodies = requestBodies();
		assertThat(bodies).isNotEmpty();
		return bodies.get(bodies.size() - 1);
	}

	private static int countOf(String body, String marker) {
		int count = 0;
		int index = 0;
		while ((index = body.indexOf(marker, index)) >= 0) {
			count++;
			index += marker.length();
		}
		return count;
	}

	private static void awaitProviderRequest(Duration timeout) {
		long deadline = System.currentTimeMillis() + timeout.toMillis();
		while (System.currentTimeMillis() < deadline) {
			if (!QWEN.getAllServeEvents().isEmpty()) {
				return;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}
}
