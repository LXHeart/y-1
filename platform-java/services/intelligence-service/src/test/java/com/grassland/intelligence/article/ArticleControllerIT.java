package com.grassland.intelligence.article;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 文章三端点端到端（草场 intelligence Slice 3）：titles（经执行环扣积分+聚合解析 JSON）/ outline /
 * content（免费 SSE）。 关键不变量：仅 titles 扣积分（独立模式经执行环闭环）；outline/content 不扣。
 */
class ArticleControllerIT extends IntelligenceItSupport {

	/** 路由决策替身（平台解析），供 streamWith 两步桩使用。 */
	private static final com.grassland.intelligence.ai.run.RoutedTextCompletionService.Routed ROUTED =
			new com.grassland.intelligence.ai.run.RoutedTextCompletionService.Routed(
					com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution.platform(
							null, "qwen", "http://localhost/v1", "qwen-plus", 1, null), "synthetic-key");


	@MockitoBean
	private RoutedTextCompletionService ai;

	@MockitoBean
	private CreditsClient credits;

	@MockitoBean
	private FrozenTextExecutionService frozenText;

	@BeforeEach
	void stubDefaults() {
		reset(ai, credits, frozenText);
		when(ai.resolveFor(any(), any())).thenReturn(Mono.just(ROUTED));
		CreditsStubs.stubDefaults(credits);
	}

	private String signed() {
		return sign(UUID.randomUUID().toString(), "recommender");
	}

	/** 桩执行环出口：把环入口捕获的 transform 应用到给定模型输出（覆盖剥 fence + JSON 解析路径）。 */
	@SuppressWarnings("unchecked")
	private void stubIndependentAppliesTransform(String modelOutput) {
		when(frozenText.executeIndependent(any(), any(), org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.eq(CreditFeature.ARTICLE_GENERATION), any())).thenAnswer(invocation -> {
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

	// ---------- titles ----------

	@Test
	@DisplayName("titles 无断言 → 401；题材空 → 400；均不触达执行环")
	void titlesAuthAndValidation() {
		client().post().uri("/api/article-generation/titles").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "职场")).exchange().expectStatus().isUnauthorized();
		verify(frozenText, never()).executeIndependent(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
				any());

		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "  ")).exchange().expectStatus()
				.isBadRequest();
		verify(frozenText, never()).executeIndependent(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
				any());
	}

	@Test
	@DisplayName("titles 积分不足 → 402（环内拒绝透传），不调 AI")
	void titlesInsufficientCredits() {
		when(frozenText.executeIndependent(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(), any()))
				.thenReturn(Mono.error(new IntelligenceException(402, "积分不足")));
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "职场", "platform", "wechat"))
				.exchange().expectStatus().isEqualTo(402);
		verify(ai, never()).streamWith(any(), any(), anyInt(), any(), any());
	}

	@Test
	@DisplayName("titles 成功 → 经执行环（ARTICLE_GENERATION）+ 剥 code fence 解析 {title,hook} + prompt 断言")
	void titlesAggregatesAndParses() {
		stubIndependentAppliesTransform("```json\n{\"titles\":[{\"title\":\"爆款\",\"hook\":\"好奇\"}]}\n```");

		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "职场", "platform", "wechat"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.success").isEqualTo(true)
				.jsonPath("$.data.titles[0].title").isEqualTo("爆款").jsonPath("$.data.titles[0].hook").isEqualTo("好奇");

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor
				.forClass((Class<List<ChatMessage>>) (Class<?>) List.class);
		verify(frozenText).executeIndependent(any(), messagesCaptor.capture(), org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.eq(CreditFeature.ARTICLE_GENERATION), any());
		assertThat(messagesCaptor.getValue()).hasSize(2);
		assertThat(messagesCaptor.getValue().get(0).content()).contains("标题");
		assertThat(messagesCaptor.getValue().get(1).content()).contains("职场");
		verify(credits, never()).consume(any(), any());
	}

	@Test
	@DisplayName("titles 返回非 JSON → 502（解析失败透传，不落 500）")
	void titlesUnparseableReturns502() {
		stubIndependentAppliesTransform("这不是JSON");
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "职场")).exchange().expectStatus()
				.isEqualTo(502);
	}

	// ---------- style skill 注入（任务书 #57）：种子行由启动 Seeder 落库 ----------

	@Test
	@DisplayName("titles 带 titleFormula=number → system 消息含【标题套路：数字型】注入段（追加不换消息）")
	void titlesInjectsTitleFormula() {
		stubIndependentAppliesTransform("{\"titles\":[{\"title\":\"3个免费技巧\",\"hook\":\"数字\"}]}");

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("topic", "职场");
		body.put("platform", "xiaohongshu");
		body.put("titleFormula", "number");
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.titles[0].title").isEqualTo("3个免费技巧");

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor
				.forClass((Class<List<ChatMessage>>) (Class<?>) List.class);
		verify(frozenText).executeIndependent(any(), messagesCaptor.capture(),
				org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.eq(CreditFeature.ARTICLE_GENERATION), any());
		List<ChatMessage> messages = messagesCaptor.getValue();
		assertThat(messages).hasSize(2); // 注入追加进既有 system，不新增消息
		String system = messages.get(0).content();
		assertThat(system).contains("【标题套路：数字型】");
		assertThat(system).contains("数字尽量放在标题前半段"); // 种子 prompt 片段
		assertThat(system).contains("与前文「风格多样化」的要求冲突时，以本段为准"); // 优先级句
		assertThat(system.indexOf("【标题套路：数字型】")).isGreaterThan(0); // 追加在 base 之后
	}

	@Test
	@DisplayName("content 带 genre+style → 两段注入且体裁在前、文风在后；lineage 记 styleSelection")
	void contentInjectsGenreAndStyleInOrder() {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<java.util.List<com.grassland.intelligence.ai.ChatMessage>> msgsCaptor =
				ArgumentCaptor.forClass(java.util.List.class);
		when(ai.streamWith(any(), msgsCaptor.capture(), anyInt(), any(), any()))
				.thenReturn(Flux.just(new ChatChunk("正文段落")));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("topic", "风格注入样本");
		body.put("title", "打工人的清晨");
		body.put("outline", "一、开头引子\n二、展开吐槽\n三、收尾升华");
		body.put("platform", "xiaohongshu");
		body.put("genre", "practical_guide");
		body.put("style", "professional");
		client().post().uri("/api/article-generation/content").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk()
				.expectBody().returnResult(); // 消费整流（lineage 落库在流尾）

		String system = msgsCaptor.getValue().get(0).content();
		assertThat(system).contains("【内容体裁：干货攻略型】");
		assertThat(system).contains("【文风口吻：专业博主风】");
		assertThat(system.indexOf("【内容体裁：干货攻略型】"))
				.isLessThan(system.indexOf("【文风口吻：专业博主风】")); // 体裁在前、文风在后
		assertThat(system).contains("与前文默认语气冲突时，以本段为准"); // 文风优先级句（覆盖闺蜜口吻）

		String inputSummary = db.sql("""
				SELECT input_summary::text FROM creation_generation
				WHERE kind='article' AND mode='independent' AND prompt_text LIKE '%风格注入样本%'
				ORDER BY created_at DESC LIMIT 1
				""").map(r -> r.get(0, String.class)).one().block();
		// jsonb::text 键后带空格，统一去掉再断言
		String normalized = inputSummary == null ? "" : inputSummary.replace(" ", "");
		assertThat(normalized).contains("\"styleSelection\"");
		assertThat(normalized).contains("\"code\":\"practical_guide\"");
		assertThat(normalized).contains("\"name\":\"干货攻略型\"");
		assertThat(normalized).contains("\"name\":\"专业博主风\"");
	}

	@Test
	@DisplayName("无效/停用 code → 400 明确文案，零上游调用、零积分变动（校验先于执行环）")
	void invalidSkillCodeRejectsBeforeUpstreamAndCredits() {
		Map<String, Object> titles = new LinkedHashMap<>();
		titles.put("topic", "职场");
		titles.put("platform", "xiaohongshu");
		titles.put("titleFormula", "no_such_formula");
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(titles).exchange().expectStatus().isBadRequest()
				.expectBody().jsonPath("$.error").isEqualTo("所选标题套路无效或已停用，请重新选择");

		// 停用走真实库行：直接禁用 STYLE/bestie（测完恢复）
		db.sql("UPDATE creation_style_skill SET enabled=false WHERE category='STYLE' AND code='bestie'")
				.then().block();
		try {
			Map<String, Object> content = new LinkedHashMap<>();
			content.put("topic", "职场");
			content.put("title", "标题");
			content.put("outline", "一、开头引子\n二、展开吐槽\n三、收尾升华");
			content.put("platform", "xiaohongshu");
			content.put("style", "bestie");
			client().post().uri("/api/article-generation/content").header("X-Grassland-Identity", signed())
					.contentType(MediaType.APPLICATION_JSON).bodyValue(content).exchange().expectStatus()
					.isBadRequest().expectBody().jsonPath("$.error").isEqualTo("所选文风无效或已停用，请重新选择");
		} finally {
			db.sql("UPDATE creation_style_skill SET enabled=true WHERE category='STYLE' AND code='bestie'")
					.then().block();
		}

		verify(frozenText, never()).executeIndependent(any(), any(), org.mockito.ArgumentMatchers.anyInt(), any(),
				any());
		verify(ai, never()).streamWith(any(), any(), anyInt(), any(), any());
		verify(credits, never()).consume(any(), any());
		verify(credits, never()).reserveUsage(any(), any(), any(), anyLong(), any());
	}
	@Test
	@DisplayName("admin PUT 改 promptContent 后下一次生成即注入新文本（决策 F：直读无缓存实证）")
	void adminEditTakesEffectOnNextGeneration() {
		String id = db.sql(
				"SELECT id::text FROM creation_style_skill WHERE category='TITLE_FORMULA' AND code='number'")
				.map(r -> r.get(0, String.class)).one().block();
		String original = db.sql(
				"SELECT prompt_content FROM creation_style_skill WHERE category='TITLE_FORMULA' AND code='number'")
				.map(r -> r.get(0, String.class)).one().block();
		try {
			client().put().uri("/api/admin/creation-style-skills/" + id)
					.header("X-Grassland-Identity", signAdmin(UUID.randomUUID().toString()))
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue(Map.of("name", "数字型", "description", "数字量化收获，阅读门槛低",
							"promptContent", "独特修订特征词XYZZY量化一切", "enabled", true,
							"expectedVersion", 0))
					.exchange().expectStatus().isOk();

			stubIndependentAppliesTransform("{\"titles\":[{\"title\":\"7个技巧\",\"hook\":\"数字\"}]}");
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("topic", "职场");
			body.put("platform", "xiaohongshu");
			body.put("titleFormula", "number");
			client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
					.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();

			@SuppressWarnings("unchecked")
			ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor
					.forClass((Class<List<ChatMessage>>) (Class<?>) List.class);
			verify(frozenText).executeIndependent(any(), messagesCaptor.capture(),
					org.mockito.ArgumentMatchers.anyInt(),
					org.mockito.ArgumentMatchers.eq(CreditFeature.ARTICLE_GENERATION), any());
			assertThat(messagesCaptor.getValue().get(0).content()).contains("独特修订特征词XYZZY量化一切");
		} finally {
			db.sql("UPDATE creation_style_skill SET prompt_content = :p, enabled = true, version = 0 "
					+ "WHERE id = CAST(:id AS uuid)")
					.bind("p", original).bind("id", id).then().block();
		}
	}

	@Test
	@DisplayName("回归红线：不带新字段的请求 → system 文本与现状逐字节一致（注入零发生）")
	void requestWithoutSkillFieldsKeepsPromptByteIdentical() {
		stubIndependentAppliesTransform("{\"titles\":[{\"title\":\"爆款\",\"hook\":\"好奇\"}]}");

		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "职场", "platform", "wechat")).exchange().expectStatus().isOk();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ChatMessage>> messagesCaptor = ArgumentCaptor
				.forClass((Class<List<ChatMessage>>) (Class<?>) List.class);
		verify(frozenText).executeIndependent(any(), messagesCaptor.capture(),
				org.mockito.ArgumentMatchers.anyInt(),
				org.mockito.ArgumentMatchers.eq(CreditFeature.ARTICLE_GENERATION), any());
		String system = messagesCaptor.getValue().get(0).content();
		assertThat(system).isEqualTo("""
				你是一位专业的微信公众号爆款标题策划师。根据用户提供的主题，生成 5 个有吸引力的文章标题选项。

				要求：
				- 标题要能引起读者好奇心和点击欲望
				- 风格多样化：疑问句、数字列表、故事感、对比冲突、情感共鸣等
				- 适合微信公众号阅读场景，标题直接决定打开率
				- 每个标题附带一行 hook 说明（简短描述为什么这个标题有效）

				你必须且只能返回以下 JSON 格式，不要返回任何其他文字：
				{
				  "titles": [
				    {"title": "标题文字", "hook": "这个标题有效的原因"}
				  ]
				}""");
	}

	// ---------- outline / content：免费 SSE ----------

	@Test
	@DisplayName("outline 成功 → 免费 SSE（不扣积分）；prompt 含主题/标题")
	void outlineFreeStream() {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<java.util.List<com.grassland.intelligence.ai.ChatMessage>> msgsCaptor =
				ArgumentCaptor.forClass(java.util.List.class);
		when(ai.streamWith(any(), msgsCaptor.capture(), anyInt(), any(), any()))
				.thenReturn(Flux.just(new ChatChunk("# 一、开头")));

		byte[] body = client().post().uri("/api/article-generation/outline").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("topic", "职场", "title", "打工人的清晨", "platform", "wechat")).exchange().expectStatus()
				.isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).expectBody()
				.returnResult().getResponseBody();

		assertThat(new String(body, UTF_8)).isEqualTo("data: {\"content\":\"# 一、开头\"}\n\ndata: [DONE]\n\n");
		verify(credits, never()).consume(any(), any()); // outline 免费
		java.util.List<com.grassland.intelligence.ai.ChatMessage> messages = msgsCaptor.getValue();
		assertThat(messages.get(0).content()).contains("大纲");
		assertThat(messages.get(1).content()).contains("主题：职场").contains("标题：打工人的清晨");
	}

	@Test
	@DisplayName("content 成功 → 免费 SSE；prompt 含大纲")
	void contentFreeStream() {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<java.util.List<com.grassland.intelligence.ai.ChatMessage>> msgsCaptor =
				ArgumentCaptor.forClass(java.util.List.class);
		when(ai.streamWith(any(), msgsCaptor.capture(), anyInt(), any(), any()))
				.thenReturn(Flux.just(new ChatChunk("正文段落")));

		client().post().uri("/api/article-generation/content").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(outlineBody()).exchange().expectStatus().isOk()
				.expectHeader().valueEquals("X-Accel-Buffering", "no");

		verify(credits, never()).consume(any(), any()); // content 免费
		assertThat(msgsCaptor.getValue().get(1).content()).contains("大纲：").contains("一、开头");
	}

	@Test
	@DisplayName("content 大纲过短 → 400")
	void contentShortOutline() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("topic", "职场");
		body.put("title", "标题");
		body.put("outline", "短");
		client().post().uri("/api/article-generation/content").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isBadRequest();
	}

	private static Map<String, Object> outlineBody() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("topic", "职场");
		body.put("title", "打工人的清晨");
		body.put("outline", "一、开头引子\n二、展开吐槽\n三、收尾升华");
		body.put("platform", "wechat");
		return body;
	}
}
