package com.grassland.intelligence.article;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.run.RoutedTextCompletionService;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.article.ArticlePrompts.Platform;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 文章生成（草场 intelligence Slice 3）：titles / outline / content 三端点。
 * {@code /api/article-generation/*} 文本与图片端点均由 intelligence 承载，前端路径不变。
 *
 * <p>
 * 与 legacy 行为一致：<b>仅 titles
 * 扣积分</b>（{@link CreditFeature#ARTICLE_GENERATION}，独立模式经 执行环闭环），outline /
 * content 是免费 SSE（创作者已为 titles 付费后的免费跟进）。titles 非流式—— 单次完成聚合后剥 markdown code
 * fence 解析 {@code {titles:[{title,hook}]}}，失败→502。
 */
@RestController
public class ArticleController {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private final IntelligenceCallerResolver callers;
	private final RoutedTextCompletionService routed;
	private final FrozenTextExecutionService frozenText;
	private final ArticleCreationContext creationContexts;
	private final com.grassland.intelligence.contentsafety.ContentSafetyService safety;
	private final com.grassland.intelligence.creationlineage.TextCreationLineageService lineage;
	private final com.grassland.intelligence.creationstyle.CreationStyleSkillService styleSkills;

	// 任务书 #61：去AI味 skill 注入（免费 Routed 通道显式接入；计费流在执行环内统一注入）
	private final com.grassland.intelligence.humanize.HumanizeInjectionService humanize;

	public ArticleController(IntelligenceCallerResolver callers, RoutedTextCompletionService routed,
			FrozenTextExecutionService frozenText, ArticleCreationContext creationContexts,
			com.grassland.intelligence.contentsafety.ContentSafetyService safety,
			com.grassland.intelligence.creationlineage.TextCreationLineageService lineage,
			com.grassland.intelligence.creationstyle.CreationStyleSkillService styleSkills,
			com.grassland.intelligence.humanize.HumanizeInjectionService humanize) {
		this.callers = callers;
		this.routed = routed;
		this.frozenText = frozenText;
		this.creationContexts = creationContexts;
		this.safety = safety;
		this.lineage = lineage;
		this.styleSkills = styleSkills;
		this.humanize = humanize;
	}

	// ---------- style skill 注入（任务书 #57）：解析必须先于任何上游调用与扣费 ----------

	/**
	 * titles system 消息组装（含标题套路注入段）。空 code → 未选 → base prompt 逐字节不变； code 未知/停用 →
	 * 400 在此短路（执行环之前，零上游调用、零扣费）。
	 */
	private Mono<com.grassland.intelligence.ai.ChatMessage> titlesSystemMessage(Platform platform,
			ArticlePrompts.Mode mode, String titleFormula) {
		return styleSkills
				.requireEnabled(com.grassland.intelligence.creationstyle.CreationStyleSkillCategory.TITLE_FORMULA,
						titleFormula)
				.map(skill -> ArticlePrompts.titlesSystem(platform, mode,
						com.grassland.intelligence.creationstyle.CreationStyleSkill.SkillPrompt.from(skill)))
				.defaultIfEmpty(ArticlePrompts.titlesSystem(platform, mode, null));
	}

	/** content 已解析的风格载体（genre/style 任一可空=未选；lineage 记 code+name 用）。 */
	private record ContentStyles(com.grassland.intelligence.creationstyle.CreationStyleSkill genre,
			com.grassland.intelligence.creationstyle.CreationStyleSkill style) {
	}

	/** 任务模式 content 三元载体（binding + 风格 + traced 结果）。 */
	private record TaskContentBound(ArticleCreationContext.Binding binding, ContentStyles styles,
			FrozenTextExecutionService.Traced<String> trace) {
	}

	/** content 风格解析：体裁+文风 zip（均为非空 Mono，绝无空信号陷阱）；任一无效 → 400 短路。 */
	private Mono<ContentStyles> resolveContentStyles(String genreCode, String styleCode) {
		var genreMono = styleSkills
				.requireEnabled(com.grassland.intelligence.creationstyle.CreationStyleSkillCategory.GENRE, genreCode)
				.map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty());
		var styleMono = styleSkills
				.requireEnabled(com.grassland.intelligence.creationstyle.CreationStyleSkillCategory.STYLE, styleCode)
				.map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty());
		return Mono.zip(genreMono, styleMono)
				.map(t -> new ContentStyles(t.getT1().orElse(null), t.getT2().orElse(null)));
	}

	private static com.grassland.intelligence.ai.ChatMessage contentSystemMessage(Platform platform,
			ArticlePrompts.Mode mode, ContentStyles styles) {
		return ArticlePrompts.contentSystem(platform, mode,
				styles.genre() == null
						? null
						: com.grassland.intelligence.creationstyle.CreationStyleSkill.SkillPrompt.from(styles.genre()),
				styles.style() == null
						? null
						: com.grassland.intelligence.creationstyle.CreationStyleSkill.SkillPrompt.from(styles.style()));
	}

	// ---------- 双模式解析（任务书 #62）----------

	/**
	 * 模式解析：{@code answerMode=true} → ANSWER（question 必填已由请求体校验保证），否则 ARTICLE。
	 * 非知乎平台由 {@link ArticlePrompts} 侧忽略 mode（回归红线在 prompt 层兜底）。
	 */
	private static ArticlePrompts.Mode modeOf(boolean answerMode) {
		return answerMode ? ArticlePrompts.Mode.ANSWER : ArticlePrompts.Mode.ARTICLE;
	}

	/** 任务模式权威问题：快照冻结值优先，缺失时回落请求体（自由创作恒走请求体）。 */
	private static String questionOf(String bodyQuestion, ArticleCreationContext.Binding binding) {
		String frozen = binding == null ? null : binding.frozenQuestion();
		return frozen != null ? frozen : bodyQuestion;
	}

	/**
	 * titles 用户消息：回答模式 = 问题（+ 可选补充说明），文章模式 = 主题（§4.1）。
	 */
	private static com.grassland.intelligence.ai.ChatMessage titlesUserMessage(TitlesRequest body, String question) {
		return body.isAnswerMode()
				? ArticlePrompts.answerTitlesUser(question, body.topic())
				: ArticlePrompts.titlesUser(body.topic());
	}

	/** outline 用户消息：回答模式的 title 字段承载选定开头段（§4.1）。 */
	private static com.grassland.intelligence.ai.ChatMessage outlineUserMessage(OutlineRequest body, String question) {
		return body.isAnswerMode()
				? ArticlePrompts.answerOutlineUser(question, body.title())
				: ArticlePrompts.outlineUser(body.topic(), body.title());
	}

	/** content 用户消息：回答模式的 title 字段承载选定开头段（§4.1）。 */
	private static com.grassland.intelligence.ai.ChatMessage contentUserMessage(ContentRequest body, String question) {
		return body.isAnswerMode()
				? ArticlePrompts.answerContentUser(question, body.title(), body.outline())
				: ArticlePrompts.contentUser(body.topic(), body.title(), body.outline());
	}

	// ---------- titles：扣积分 + 聚合流式 → 解析 JSON ----------

	@PostMapping("/api/article-generation/titles")
	public Mono<Map<String, Object>> titles(@RequestBody TitlesRequest body, ServerWebExchange exchange) {
		Platform platform = Platform.fromKey(body.platform());
		if (body.isTaskMode()) {
			return callers.requireUser(exchange.getRequest()).flatMap(
					caller -> creationContexts.bind(body.contextSnapshotId(), caller.accountId(), body.platform()))
					.flatMap(binding -> titlesSystemMessage(binding.platform(), modeOf(body.isAnswerMode()),
							body.titleFormula())
							.flatMap(system -> frozenText.execute(exchange, body.contextSnapshotId(),
									List.of(system, binding.promptContext(),
											titlesUserMessage(body, questionOf(body.question(), binding))),
									1024, CreditFeature.ARTICLE_GENERATION,
									completion -> parseTitles(completion.content()))))
					.flatMap(titles -> titlesBody(titles));
		}
		// GL-P3-AI-001 尾巴清偿：独立模式经执行环（预算闸/ai_run 留痕/积分闭环/失败退款一套机器），
		// 控制器不再手动 consume/refund；402 拒绝与 502 解析失败均为 JSON 先于 SSE。
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> titlesSystemMessage(platform, modeOf(body.isAnswerMode()), body.titleFormula())
						.flatMap(system -> frozenText.executeIndependent(exchange,
								List.of(system, titlesUserMessage(body, body.question())), 1024,
								CreditFeature.ARTICLE_GENERATION, completion -> parseTitles(completion.content()))))
				.flatMap(trace -> titlesBody(trace.value()));
	}

	/** titles 响应：data 内嵌 safety 块（标题为短文本仅 L1；任务书 #34 D8）。 */
	private Mono<Map<String, Object>> titlesBody(List<Title> titles) {
		String joined = titles.stream().map(Title::title).reduce("", (a, b) -> a + " " + b);
		return Mono.just(Map.<String, Object>of("success", true, "data",
				Map.of("titles", titles, "safety", safety.reportBody(safety.checkShallow(joined)))));
	}

	// ---------- outline：免费 SSE ----------

	@PostMapping("/api/article-generation/outline")
	public Mono<ResponseEntity<Flux<DataBuffer>>> outline(@RequestBody OutlineRequest body,
			ServerWebExchange exchange) {
		Platform platform = Platform.fromKey(body.platform());
		ArticlePrompts.Mode mode = modeOf(body.isAnswerMode());
		if (body.isTaskMode()) {
			return taskStream(exchange, body.contextSnapshotId(), body.platform(),
					binding -> List.of(ArticlePrompts.outlineSystem(binding.platform(), mode), binding.promptContext(),
							outlineUserMessage(body, questionOf(body.question(), binding))),
					2048, "大纲生成失败", false);
		}
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> routed.resolveFor(caller.accountId(), caller.organizationId()).map(resolution -> {
					Flux<String> payloads = humanize
							.injectCreative(List.of(ArticlePrompts.outlineSystem(platform, mode),
									outlineUserMessage(body, body.question())))
							.flatMapMany(msgs -> routed.streamWith(resolution, msgs, 2048, null, "大纲生成失败"))
							.map(chunk -> frame(Map.of("content", chunk.content())))
							.onErrorResume(e -> Flux.just(frame(Map.of("error", "大纲生成失败"))));
					return sseEntity(payloads, exchange);
				}));
	}

	// ---------- content：免费 SSE ----------

	@PostMapping("/api/article-generation/content")
	public Mono<ResponseEntity<Flux<DataBuffer>>> content(@RequestBody ContentRequest body,
			ServerWebExchange exchange) {
		Platform platform = Platform.fromKey(body.platform());
		if (body.isTaskMode()) {
			return contentTaskStream(exchange, body);
		}
		return callers.resolve(exchange.getRequest()).flatMap(caller -> resolveContentStyles(body.genre(), body.style())
				.flatMap(styles -> routed.resolveFor(caller.accountId(), caller.organizationId()).map(resolution -> {
					com.grassland.intelligence.ai.ChatMessage system = contentSystemMessage(platform,
							modeOf(body.isAnswerMode()), styles);
					StringBuilder accumulated = new StringBuilder();
					java.util.function.Function<String, String> textOf = com.grassland.intelligence.contentsafety.ContentSafetyService
							.contentFieldExtractor();
					Flux<String> payloads = humanize
							.injectCreative(List.of(system, contentUserMessage(body, body.question())))
							.flatMapMany(msgs -> routed.streamWith(resolution, msgs, 2048, null, "正文生成失败"))
							.map(chunk -> frame(Map.of("content", chunk.content()))).doOnNext(item -> {
								String text = textOf.apply(item);
								if (text != null) {
									accumulated.append(text);
								}
							}).onErrorResume(e -> Flux.just(frame(Map.of("error", "正文生成失败"))))
							// 任务书 #44 登记扩展：正文产出落 lineage（SSE 尾部落痕，失败不破坏内容流）。
							// provider/model 回填本次流的真实路由解析（#58：env 默认 model 兜底已删）
							.concatWith(Mono.defer(() -> lineage.recordAdvisory(
									new com.grassland.intelligence.creationlineage.CreationGenerationRecorder.Command(
											com.grassland.intelligence.creationlineage.CreationGeneration.Kind.ARTICLE,
											com.grassland.intelligence.creationlineage.CreationGeneration.Mode.INDEPENDENT,
											null, null,
											com.grassland.intelligence.creationlineage.CreationGeneration.Resolution.PLATFORM,
											resolution.resolution().provider(), resolution.resolution().model(), null,
											null, contentPrompt(body, body.question()),
											contentInput(body, styles, body.question()), List.of(),
											Map.of("contentLength", accumulated.length()), List.of(),
											caller.accountId(), caller.organizationId()))
									.then(Mono.<String>empty())));
					// 任务书 #34 D8：正文（长文本）流尾追加安全检查帧（L1 必跑 + L2 已配置时深检）
					return sseEntity(safety.appendSafetyFrame(exchange, payloads,
							com.grassland.intelligence.contentsafety.ContentSafetyService.contentFieldExtractor(),
							body.platform(), null, null), exchange);
				})));
	}

	// ---------- helpers ----------

	private Mono<ResponseEntity<Flux<DataBuffer>>> taskStream(ServerWebExchange exchange, UUID snapshotId,
			String platform,
			java.util.function.Function<ArticleCreationContext.Binding, List<com.grassland.intelligence.ai.ChatMessage>> messages,
			int maxTokens, String failureMessage, boolean appendSafety) {
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> creationContexts.bind(snapshotId, caller.accountId(), platform))
				.flatMap(binding -> frozenText.execute(exchange, snapshotId, messages.apply(binding), maxTokens, null,
						completion -> completion.content()).map(content -> Map.entry(binding, content)))
				.map(bound -> {
					ArticleCreationContext.Binding binding = bound.getKey();
					String content = bound.getValue();
					Flux<String> frames = Flux.just(frame(Map.of("content", content)));
					if (appendSafety) {
						var snapshot = binding.snapshot();
						frames = safety.appendSafetyFrame(exchange, frames,
								com.grassland.intelligence.contentsafety.ContentSafetyService.contentFieldExtractor(),
								snapshot.platformId(),
								com.grassland.intelligence.contentsafety.ContentSafetyService
										.industryFromSnapshot(snapshot),
								com.grassland.intelligence.contentsafety.ContentSafetyService
										.generationContext(snapshot));
					}
					return sseEntity(frames, exchange);
				})
				.onErrorMap(error -> error instanceof IntelligenceException
						? error
						: new IntelligenceException(502, failureMessage));
	}

	/**
	 * 正文任务模式（任务书 #44 登记扩展）：executeTraced 携带 run/provider/model 落 lineage——
	 * 正文是文章创作的最终产出物，titles/outline 是中间步骤不落痕。
	 */
	private Mono<ResponseEntity<Flux<DataBuffer>>> contentTaskStream(ServerWebExchange exchange, ContentRequest body) {
		return callers
				.requireUser(
						exchange.getRequest())
				.flatMap(caller -> creationContexts.bind(body.contextSnapshotId(), caller.accountId(), body.platform()))
				.flatMap(
						binding -> resolveContentStyles(body.genre(), body.style()).flatMap(styles -> frozenText
								.executeTraced(exchange, body.contextSnapshotId(),
										List.of(contentSystemMessage(binding.platform(), modeOf(body.isAnswerMode()),
												styles), binding.promptContext(),
												contentUserMessage(body, questionOf(body.question(), binding))),
										4096, null, completion -> completion.content())
								.map(trace -> new TaskContentBound(binding, styles, trace))).map(bound -> {
									Flux<String> frames = Flux.just(frame(Map.of("content", bound.trace().value())))
											.concatWith(Mono.defer(() -> lineage.recordAdvisory(
													new com.grassland.intelligence.creationlineage.CreationGenerationRecorder.Command(
															com.grassland.intelligence.creationlineage.CreationGeneration.Kind.ARTICLE,
															com.grassland.intelligence.creationlineage.CreationGeneration.Mode.TASK,
															body.contextSnapshotId(), bound.trace().runId(),
															bound.trace().byok()
																	? com.grassland.intelligence.creationlineage.CreationGeneration.Resolution.BYOK
																	: com.grassland.intelligence.creationlineage.CreationGeneration.Resolution.PLATFORM,
															bound.trace().provider(), bound.trace().model(),
															bound.trace().platformModelVersion(), null,
															contentPrompt(body,
																	questionOf(body.question(), bound.binding())),
															contentInput(body, bound.styles(),
																	questionOf(body.question(), bound.binding())),
															List.of(),
															Map.of("contentLength",
																	bound.trace().value() == null
																			? 0
																			: bound.trace().value().length()),
															List.of(), bound.binding().snapshot().accountId(),
															bound.binding().snapshot().organizationId()))
													.then(Mono.<String>empty())));
									var snapshot = bound.binding().snapshot();
									frames = safety.appendSafetyFrame(exchange, frames,
											com.grassland.intelligence.contentsafety.ContentSafetyService
													.contentFieldExtractor(),
											snapshot.platformId(),
											com.grassland.intelligence.contentsafety.ContentSafetyService
													.industryFromSnapshot(snapshot),
											com.grassland.intelligence.contentsafety.ContentSafetyService
													.generationContext(snapshot));
									return sseEntity(frames, exchange);
								}))
				.onErrorMap(error -> error instanceof IntelligenceException
						? error
						: new IntelligenceException(502, "正文生成失败"));
	}

	/** lineage 输入速写（任务书 #44：prompt=主题+标题+大纲，input=结构化摘要，正文只记长度不落全文）。 */
	private static String contentPrompt(ContentRequest body, String question) {
		if (body.isAnswerMode()) {
			// 任务书 #62：回答模式无标题，速写记问题与开头（lineage 可读性）
			return "问题：" + question + "；开头：" + body.title() + "；大纲：" + body.outline();
		}
		return "主题：" + body.topic() + "；标题：" + body.title() + "；大纲：" + body.outline();
	}

	private static Map<String, Object> contentInput(ContentRequest body, ContentStyles styles, String question) {
		Map<String, Object> input = new java.util.LinkedHashMap<>();
		input.put("topic", body.topic());
		input.put("title", body.title());
		input.put("platform", body.platform());
		input.put("outlineLength", body.outline() == null ? 0 : body.outline().length());
		// 任务书 #62：回答/文章共用 kind=ARTICLE，contentMode 是二者在 lineage 里的唯一区分位
		input.put("contentMode", body.isAnswerMode() ? "answer" : "article");
		if (body.isAnswerMode()) {
			input.put("question", question);
		}
		// 任务书 #57 决策 I：styleSelection 记 code+name（未选时无此键）
		if (styles != null && (styles.genre() != null || styles.style() != null)) {
			Map<String, Object> selection = new java.util.LinkedHashMap<>();
			if (styles.genre() != null) {
				selection.put("genre", Map.of("code", styles.genre().code(), "name", styles.genre().name()));
			}
			if (styles.style() != null) {
				selection.put("style", Map.of("code", styles.style().code(), "name", styles.style().name()));
			}
			input.put("styleSelection", selection);
		}
		return input;
	}

	private ResponseEntity<Flux<DataBuffer>> sseEntity(Flux<String> payloads, ServerWebExchange exchange) {
		Flux<DataBuffer> sseBody = Sse.stream(payloads, exchange.getResponse().bufferFactory());
		HttpHeaders h = new HttpHeaders();
		h.setContentType(MediaType.TEXT_EVENT_STREAM);
		h.set("X-Accel-Buffering", "no");
		h.setCacheControl("no-cache");
		return new ResponseEntity<>(sseBody, h, HttpStatus.OK);
	}

	private static String frame(Map<String, String> fields) {
		try {
			return MAPPER.writeValueAsString(fields);
		} catch (Exception e) {
			return "{\"error\":\"生成失败\"}";
		}
	}

	/**
	 * 剥 markdown code fence（{@code ```json ... ```}）→ 解析
	 * {@code {titles:[{title,hook}]}}。
	 */
	private static List<Title> parseTitles(String raw) {
		String stripped = stripCodeFence(raw);
		JsonNode root;
		try {
			root = MAPPER.readTree(stripped);
		} catch (Exception e) {
			throw new IntelligenceException(502, "标题生成返回了无法解析的内容");
		}
		JsonNode arr = root.path("titles");
		if (!arr.isArray()) {
			throw new IntelligenceException(502, "标题生成返回了无效数据");
		}
		List<Title> titles = new ArrayList<>();
		for (JsonNode item : arr) {
			String t = item.path("title").asText("");
			if (!t.isEmpty()) {
				titles.add(new Title(t, item.path("hook").asText("")));
			}
		}
		if (titles.isEmpty()) {
			throw new IntelligenceException(502, "标题生成返回了空标题列表");
		}
		return titles;
	}

	private static String stripCodeFence(String raw) {
		String trimmed = raw.trim();
		if (trimmed.startsWith("```")) {
			int firstNl = trimmed.indexOf('\n');
			if (firstNl > 0) {
				trimmed = trimmed.substring(firstNl + 1);
			}
			int lastFence = trimmed.lastIndexOf("```");
			if (lastFence >= 0) {
				trimmed = trimmed.substring(0, lastFence);
			}
		}
		return trimmed.trim();
	}

	/** titles 响应项。 */
	public record Title(String title, String hook) {
	}

	/**
	 * topic 1-200；platform 可省略（默认 wechat）；titleFormula 可空=不注入（任务书 #57）。
	 *
	 * <p>
	 * 任务书 #62 回答模式（{@code answerMode=true}）：{@code question} 必填，topic 降级为
	 * <b>可选</b>「补充说明」（回答的标题就是问题本身，不再需要主题）。
	 */
	public record TitlesRequest(String topic, String platform, Boolean taskMode, UUID contextSnapshotId,
			String titleFormula, Boolean answerMode, String question) {
		public TitlesRequest(String topic, String platform) {
			this(topic, platform, false, null, null, false, null);
		}

		public TitlesRequest {
			topic = topic == null ? "" : topic.trim();
			titleFormula = normalizeSkillCode(titleFormula);
			question = normalizeQuestion(question);
			if (Boolean.TRUE.equals(answerMode)) {
				requireQuestion(question);
				if (topic.length() > 200) {
					throw new IllegalArgumentException("补充说明过长");
				}
			} else if (topic.isEmpty() || topic.length() > 200) {
				throw new IllegalArgumentException("请输入主题或关键词");
			}
		}

		boolean isTaskMode() {
			return Boolean.TRUE.equals(taskMode);
		}

		boolean isAnswerMode() {
			return Boolean.TRUE.equals(answerMode);
		}
	}

	/**
	 * topic 1-200、title 1-100；platform 可省略。
	 *
	 * <p>
	 * 任务书 #62 回答模式：{@code title} 承载<b>选定开头段全文</b>（回答无标题），因此长度上限 放宽到
	 * {@value #MAX_OPENING_CHARS}——开头 prompt 要求 60-120 字，按 100 字上限校验会把
	 * 合规开头判成非法；topic 降级为可选补充说明。
	 */
	public record OutlineRequest(String topic, String title, String platform, Boolean taskMode, UUID contextSnapshotId,
			Boolean answerMode, String question) {
		/** 开头段长度上限（prompt 要求 60-120 字，留足模型溢出余量）。 */
		static final int MAX_OPENING_CHARS = 500;

		public OutlineRequest(String topic, String title, String platform) {
			this(topic, title, platform, false, null, false, null);
		}

		public OutlineRequest {
			topic = topic == null ? "" : topic.trim();
			title = title == null ? "" : title.trim();
			question = normalizeQuestion(question);
			if (Boolean.TRUE.equals(answerMode)) {
				requireQuestion(question);
				if (topic.length() > 200) {
					throw new IllegalArgumentException("补充说明过长");
				}
				if (title.isEmpty() || title.length() > MAX_OPENING_CHARS) {
					throw new IllegalArgumentException("请选择或输入回答开头");
				}
			} else {
				if (topic.isEmpty() || topic.length() > 200) {
					throw new IllegalArgumentException("请输入主题");
				}
				if (title.isEmpty() || title.length() > 100) {
					throw new IllegalArgumentException("请选择或输入标题");
				}
			}
		}

		boolean isTaskMode() {
			return Boolean.TRUE.equals(taskMode);
		}

		boolean isAnswerMode() {
			return Boolean.TRUE.equals(answerMode);
		}
	}

	/**
	 * topic 1-200、title 1-100、outline ≥10；platform 可省略；genre/style 可空=不注入（任务书 #57）。
	 *
	 * <p>
	 * 任务书 #62 回答模式：{@code title} 承载选定开头段全文（上限同 outline 端点）， {@code question}
	 * 必填，topic 降级为可选补充说明。
	 */
	public record ContentRequest(String topic, String title, String outline, String platform, Boolean taskMode,
			UUID contextSnapshotId, String genre, String style, Boolean answerMode, String question) {
		public ContentRequest(String topic, String title, String outline, String platform) {
			this(topic, title, outline, platform, false, null, null, null, false, null);
		}

		public ContentRequest {
			topic = topic == null ? "" : topic.trim();
			title = title == null ? "" : title.trim();
			outline = outline == null ? "" : outline.trim();
			genre = normalizeSkillCode(genre);
			style = normalizeSkillCode(style);
			question = normalizeQuestion(question);
			if (Boolean.TRUE.equals(answerMode)) {
				requireQuestion(question);
				if (topic.length() > 200) {
					throw new IllegalArgumentException("补充说明过长");
				}
				if (title.isEmpty() || title.length() > OutlineRequest.MAX_OPENING_CHARS) {
					throw new IllegalArgumentException("请选择或输入回答开头");
				}
			} else {
				if (topic.isEmpty() || topic.length() > 200) {
					throw new IllegalArgumentException("请输入主题");
				}
				if (title.isEmpty() || title.length() > 100) {
					throw new IllegalArgumentException("请选择或输入标题");
				}
			}
			if (outline.length() < 10) {
				throw new IllegalArgumentException("大纲内容过短");
			}
		}

		boolean isTaskMode() {
			return Boolean.TRUE.equals(taskMode);
		}

		boolean isAnswerMode() {
			return Boolean.TRUE.equals(answerMode);
		}
	}

	/** 目标问题归一：trim、空串→null（任务书 #62）。 */
	private static String normalizeQuestion(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	/** 回答模式判据的必填半边（任务书 #62 全局约束 2）：answerMode 为真时 question 不可空。 */
	private static void requireQuestion(String question) {
		if (question == null) {
			throw new IllegalArgumentException("回答模式必须提供目标问题");
		}
		if (question.length() > 500) {
			throw new IllegalArgumentException("目标问题过长");
		}
	}

	/** skill code 归一：trim、空串→null（空=未选=不注入=现状，回归红线）。 */
	private static String normalizeSkillCode(String raw) {
		if (raw == null) {
			return null;
		}
		String trimmed = raw.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
