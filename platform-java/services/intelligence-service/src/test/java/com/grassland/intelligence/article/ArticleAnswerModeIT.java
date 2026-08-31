package com.grassland.intelligence.article;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
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
 * 知乎回答模式三端点接线（任务书 #62 卡4）：answerMode 透传回答体 prompt、question 必填 400、 任务模式快照
 * question 优先于请求体、文章模式回归。
 */
class ArticleAnswerModeIT extends IntelligenceItSupport {

	private static final RoutedTextCompletionService.Routed ROUTED = new RoutedTextCompletionService.Routed(
			com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution.platform(null, "qwen",
					"http://localhost/v1", "qwen-plus", 1, null),
			"synthetic-key");

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

	@SuppressWarnings("unchecked")
	private void stubTitles(String modelOutput) {
		when(frozenText.executeIndependent(any(), any(), anyInt(),
				org.mockito.ArgumentMatchers.eq(CreditFeature.ARTICLE_GENERATION), any())).thenAnswer(invocation -> {
					Function<TextCompletionResult, Object> transform = (Function<TextCompletionResult, Object>) invocation
							.getArgument(4);
					return Mono.just(new FrozenTextExecutionService.Traced<>(
							transform.apply(new TextCompletionResult(modelOutput, 10, 5)), null, "qwen", "qwen-plus", 1,
							false));
				});
	}

	@SuppressWarnings("unchecked")
	private ArgumentCaptor<List<ChatMessage>> messageCaptor() {
		return ArgumentCaptor.forClass((Class<List<ChatMessage>>) (Class<?>) List.class);
	}

	// ---------- titles：开头候选 ----------

	@Test
	@DisplayName("titles answerMode → 回答体 prompt + 用户消息含问题；topic 变可选补充说明")
	void answerModeTitlesUsesAnswerPrompt() {
		stubTitles("{\"titles\":[{\"title\":\"我在大厂做了 8 年 HR，先说结论\",\"hook\":\"资历+结论\"}]}");

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("platform", "zhihu");
		body.put("answerMode", true);
		body.put("question", "大厂为什么开始弃用 Kubernetes");
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.titles[0].title").isEqualTo("我在大厂做了 8 年 HR，先说结论");

		ArgumentCaptor<List<ChatMessage>> captor = messageCaptor();
		verify(frozenText).executeIndependent(any(), captor.capture(), anyInt(),
				org.mockito.ArgumentMatchers.eq(CreditFeature.ARTICLE_GENERATION), any());
		List<ChatMessage> messages = captor.getValue();
		assertThat(messages).hasSize(2);
		assertThat(messages.get(0).content()).contains("知乎高赞回答写手").contains("生成 5 个回答开头候选").contains("首屏前 100 字");
		assertThat(messages.get(1).content()).isEqualTo("问题：大厂为什么开始弃用 Kubernetes");
	}

	@Test
	@DisplayName("titles answerMode 带 topic → 用户消息追加补充说明")
	void answerModeTitlesCarriesSupplement() {
		stubTitles("{\"titles\":[{\"title\":\"开头\",\"hook\":\"h\"}]}");

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("platform", "zhihu");
		body.put("answerMode", true);
		body.put("question", "如何看待远程办公");
		body.put("topic", "只聊国内中小厂");
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();

		ArgumentCaptor<List<ChatMessage>> captor = messageCaptor();
		verify(frozenText).executeIndependent(any(), captor.capture(), anyInt(), any(), any());
		assertThat(captor.getValue().get(1).content()).isEqualTo("问题：如何看待远程办公\n补充说明：只聊国内中小厂");
	}

	@Test
	@DisplayName("answerMode 缺 question → 400，零上游调用零扣费")
	void answerModeRequiresQuestion() {
		Map<String, Object> titles = new LinkedHashMap<>();
		titles.put("platform", "zhihu");
		titles.put("answerMode", true);
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(titles).exchange().expectStatus().isBadRequest()
				.expectBody().jsonPath("$.error").isEqualTo("回答模式必须提供目标问题");

		Map<String, Object> outline = new LinkedHashMap<>();
		outline.put("platform", "zhihu");
		outline.put("answerMode", true);
		outline.put("title", "开头段");
		client().post().uri("/api/article-generation/outline").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(outline).exchange().expectStatus().isBadRequest();

		Map<String, Object> content = new LinkedHashMap<>();
		content.put("platform", "zhihu");
		content.put("answerMode", true);
		content.put("title", "开头段");
		content.put("outline", "一、结论\n二、论证");
		client().post().uri("/api/article-generation/content").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(content).exchange().expectStatus().isBadRequest();

		verify(frozenText, never()).executeIndependent(any(), any(), anyInt(), any(), any());
		verify(ai, never()).streamWith(any(), any(), anyInt(), any(), any());
		verify(credits, never()).consume(any(), any());
	}

	// ---------- outline / content ----------

	@Test
	@DisplayName("outline answerMode → 回答体 outline prompt + 问题/选定开头用户消息")
	void answerModeOutlineUsesAnswerPrompt() {
		ArgumentCaptor<List<ChatMessage>> captor = messageCaptor();
		when(ai.streamWith(any(), captor.capture(), anyInt(), any(), any())).thenReturn(Flux.just(new ChatChunk("大纲")));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("platform", "zhihu");
		body.put("answerMode", true);
		body.put("question", "35 岁危机是真的吗");
		body.put("title", "我在人力资源做了 12 年，见过太多所谓的 35 岁危机，先给结论：它真实存在，但不是年龄问题。");
		client().post().uri("/api/article-generation/outline").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk().expectBody()
				.returnResult();

		assertThat(captor.getValue().get(0).content()).contains("知乎回答结构策划师").contains("首屏结论层").contains("大纲必须回应问题本身");
		assertThat(captor.getValue().get(1).content()).startsWith("问题：35 岁危机是真的吗\n选定开头：我在人力资源做了 12 年");
	}

	@Test
	@DisplayName("content answerMode → 回答体 content prompt + 问题/开头/大纲用户消息；lineage 记 contentMode=answer")
	void answerModeContentUsesAnswerPrompt() {
		ArgumentCaptor<List<ChatMessage>> captor = messageCaptor();
		when(ai.streamWith(any(), captor.capture(), anyInt(), any(), any()))
				.thenReturn(Flux.just(new ChatChunk("回答正文")));

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("platform", "zhihu");
		body.put("answerMode", true);
		body.put("question", "回答模式lineage样本问题");
		body.put("title", "我做这行 8 年，先给结论。");
		body.put("outline", "一、结论层\n二、论证层\n三、收尾");
		client().post().uri("/api/article-generation/content").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk().expectBody()
				.returnResult();

		String system = captor.getValue().get(0).content();
		assertThat(system).contains("知乎高赞回答写手").contains("回答问题本身").contains("首屏即答案");
		assertThat(captor.getValue().get(1).content())
				.isEqualTo("问题：回答模式lineage样本问题\n开头：我做这行 8 年，先给结论。\n\n大纲：\n一、结论层\n二、论证层\n三、收尾");

		String input = db.sql("""
				SELECT input_summary::text FROM creation_generation
				WHERE kind='article' AND mode='independent' AND prompt_text LIKE '%回答模式lineage样本问题%'
				ORDER BY created_at DESC LIMIT 1
				""").map(r -> r.get(0, String.class)).one().block();
		String normalized = input == null ? "" : input.replace(" ", "");
		assertThat(normalized).contains("\"contentMode\":\"answer\"");
		assertThat(normalized).contains("\"question\":\"回答模式lineage样本问题\"");
	}

	// ---------- 回归：文章模式与其它平台 ----------

	@Test
	@DisplayName("回归：知乎不带 answerMode → 文章体 prompt（专栏文章），用户消息仍是主题")
	void zhihuWithoutAnswerModeStaysArticle() {
		stubTitles("{\"titles\":[{\"title\":\"标题\",\"hook\":\"h\"}]}");

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("platform", "zhihu");
		body.put("topic", "云原生成本");
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();

		ArgumentCaptor<List<ChatMessage>> captor = messageCaptor();
		verify(frozenText).executeIndependent(any(), captor.capture(), anyInt(), any(), any());
		assertThat(captor.getValue().get(0).content()).contains("知乎专栏文章标题策划师").contains("每个标题不超过 25 字");
		assertThat(captor.getValue().get(1).content()).isEqualTo("主题：云原生成本");
	}

	@Test
	@DisplayName("回归红线：小红书传 answerMode → 仍走小红书 prompt（平台无关的 mode 被忽略）")
	void xiaohongshuIgnoresAnswerMode() {
		stubTitles("{\"titles\":[{\"title\":\"标题\",\"hook\":\"h\"}]}");

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("platform", "xiaohongshu");
		body.put("answerMode", true);
		body.put("question", "无关问题");
		body.put("topic", "通勤穿搭");
		client().post().uri("/api/article-generation/titles").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk();

		ArgumentCaptor<List<ChatMessage>> captor = messageCaptor();
		verify(frozenText).executeIndependent(any(), captor.capture(), anyInt(), any(), any());
		assertThat(captor.getValue().get(0).content()).contains("小红书爆款笔记标题策划师");
	}

	@Test
	@DisplayName("回答开头 120 字不被判非法（title 上限在回答模式放宽），800 字仍 400")
	void answerOpeningLengthCap() {
		ArgumentCaptor<List<ChatMessage>> captor = messageCaptor();
		when(ai.streamWith(any(), captor.capture(), anyInt(), any(), any())).thenReturn(Flux.just(new ChatChunk("大纲")));

		Map<String, Object> ok = new LinkedHashMap<>();
		ok.put("platform", "zhihu");
		ok.put("answerMode", true);
		ok.put("question", "问题");
		ok.put("title", "开".repeat(120));
		client().post().uri("/api/article-generation/outline").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(ok).exchange().expectStatus().isOk().expectBody()
				.returnResult();

		Map<String, Object> tooLong = new LinkedHashMap<>();
		tooLong.put("platform", "zhihu");
		tooLong.put("answerMode", true);
		tooLong.put("question", "问题");
		tooLong.put("title", "开".repeat(800));
		client().post().uri("/api/article-generation/outline").header("X-Grassland-Identity", signed())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(tooLong).exchange().expectStatus().isBadRequest();
	}
}
