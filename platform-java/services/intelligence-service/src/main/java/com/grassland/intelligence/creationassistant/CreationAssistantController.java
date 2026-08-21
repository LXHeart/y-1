package com.grassland.intelligence.creationassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 智能创作助手 API（草场 PRD §4.9.4/§4.9.6 / Slice 15 Stage 2）。
 *
 * <p>
 * SSE 端点（聚合型流已迁执行环——GL-P3-AI-001 尾巴清偿：预算闸/ai_run 留痕/BYOK 路由/ 积分闭环/失败退款一套机器，SSE
 * 在执行完成后发帧）：
 * <ul>
 * <li>{@code POST /api/creation-assistant/score} — 内容评分（§4.9.6）：聚合 LLM 输出后解析
 * JSON， 逐维度发 {@code {type:score,dimension,score,advice}} 帧 + overall 帧。</li>
 * <li>{@code POST /api/creation-assistant/suggest} — 优化建议（§4.9.4）：纯流式
 * {@code {content}} 帧。</li>
 * <li>{@code POST /api/creation-assistant/guide} — 问答引导（§4.9.1/§4.9.2），lineage
 * 落环内真值。</li>
 * <li>{@code POST /api/creation-assistant/task-coverage} — 任务覆盖检查（§4.9.3）。</li>
 * <li>{@code POST /api/creation-assistant/topic-from-hot} — 热点→选题（§4.9.5）。</li>
 * </ul>
 *
 * <p>
 * 所有端点扣 {@link CreditFeature#CREATION_ASSISTANT}，全部经执行环闭环（GL-P3-AI-001 尾巴清偿）：
 * 预算闸/ai_run 留痕/BYOK 路由/积分闭环/失败退款一套机器；SSE 在执行完成后发帧，402/502 在 SSE 前以 JSON 返回。
 */
@RestController
@RequestMapping("/api/creation-assistant")
public class CreationAssistantController {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int MIN_CONTENT_LENGTH = 10;
	private static final int ASSISTANT_MAX_TOKENS = 1024;

	private final IntelligenceCallerResolver callers;
	private final FrozenTextExecutionService frozenText;
	private final com.grassland.intelligence.creationlineage.TextCreationLineageService lineage;
	private final com.grassland.intelligence.contentsafety.ContentSafetyService safety;

	public CreationAssistantController(IntelligenceCallerResolver callers, FrozenTextExecutionService frozenText,
			com.grassland.intelligence.creationlineage.TextCreationLineageService lineage,
			com.grassland.intelligence.contentsafety.ContentSafetyService safety) {
		this.callers = callers;
		this.frozenText = frozenText;
		this.lineage = lineage;
		this.safety = safety;
	}

	/** 内容评分（§4.9.6）：经执行环聚合 LLM JSON 输出 → 逐维度发 SSE 帧。 */
	@PostMapping("/score")
	public Mono<ResponseEntity<Flux<DataBuffer>>> score(@RequestBody ScoreRequest body, ServerWebExchange exchange) {
		String content = requireContent(body);
		return callers.resolve(exchange.getRequest()).flatMap(caller -> frozenText.executeIndependent(exchange,
				CreationAssistantPrompts.scoreMessages(content, body.platform(), body.title()), ASSISTANT_MAX_TOKENS,
				CreditFeature.CREATION_ASSISTANT, completion -> parseScore(stripCodeFence(completion.content()))))
				.map(trace -> sseEntity(trace.value().toFrames(), exchange));
	}

	/**
	 * 优化建议（§4.9.4）：经执行环聚合后一次性发 {@code {content}} 帧（GL-P3-AI-001 尾巴清偿：
	 * 纯流式契约收敛为「先执行后发帧」，402/502 在 SSE 前以 JSON 返回，失败退款在环内闭环； 前端 useCreationAssistant
	 * 对单帧 content 与非 ok JSON 均兼容）。
	 */
	@PostMapping("/suggest")
	public Mono<ResponseEntity<Flux<DataBuffer>>> suggest(@RequestBody ScoreRequest body, ServerWebExchange exchange) {
		String content = requireContent(body);
		// ADR-D16 尾巴（2026-08-21 接入）：优化建议为长文本输出，流尾追加安全检查帧（L1 必跑 +
		// L2 已配置时深检），与喜剧脚本/文章正文同口径
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> frozenText.executeIndependent(exchange,
						CreationAssistantPrompts.suggestMessages(content, body.platform(), body.title()), 2048,
						CreditFeature.CREATION_ASSISTANT, completion -> completion.content()))
				.map(trace -> sseEntity(
						safety.appendSafetyFrame(exchange, Flux.just(frame(Map.of("content", trace.value()))),
								com.grassland.intelligence.contentsafety.ContentSafetyService.contentFieldExtractor(),
								body.platform(), null, null),
						exchange));
	}

	/**
	 * 问答引导（§4.9.1/§4.9.2）：根据用户当前输入，AI 决定问下一个引导问题（ask）还是给出创作 brief。 brief 里推测/补全的字段标
	 * inferred（§4.9.2「明确标记推测内容」）。经执行环聚合 LLM JSON → 发帧； lineage
	 * 落环内真值（runId/provider/model/byok 来自执行 trace，任务书 #44 登记扩展）。
	 */
	@PostMapping("/guide")
	public Mono<ResponseEntity<Flux<DataBuffer>>> guide(@RequestBody GuideRequest body, ServerWebExchange exchange) {
		if (body == null || body.userInput() == null || body.userInput().isBlank()) {
			return Mono.error(new IntelligenceException(400, "userInput 不能为空"));
		}
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> frozenText.executeIndependent(exchange,
						CreationAssistantPrompts.guideMessages(body.userInput(), body.platform(), body.history()),
						ASSISTANT_MAX_TOKENS, CreditFeature.CREATION_ASSISTANT,
						completion -> parseGuide(stripCodeFence(completion.content())))
						.flatMap(trace -> lineage.recordAdvisory(guideLineageCommand(caller, trace, body))
								.thenReturn(trace)))
				.map(trace -> sseEntity(trace.value(), exchange));
	}

	/**
	 * 引导流 lineage 命令：run/provider/model/resolution 从执行环 trace
	 * 装配（advisory，失败不破坏内容流）。
	 */
	private static com.grassland.intelligence.creationlineage.CreationGenerationRecorder.Command guideLineageCommand(
			IntelligenceCallerResolver.Caller caller, FrozenTextExecutionService.Traced<Flux<String>> trace,
			GuideRequest body) {
		return new com.grassland.intelligence.creationlineage.CreationGenerationRecorder.Command(
				com.grassland.intelligence.creationlineage.CreationGeneration.Kind.ASSISTANT_GUIDE,
				com.grassland.intelligence.creationlineage.CreationGeneration.Mode.INDEPENDENT, null, trace.runId(),
				trace.byok()
						? com.grassland.intelligence.creationlineage.CreationGeneration.Resolution.BYOK
						: com.grassland.intelligence.creationlineage.CreationGeneration.Resolution.PLATFORM,
				trace.provider(), trace.model(), trace.platformModelVersion(), null,
				"平台：" + (body.platform() == null ? "" : body.platform()) + "；用户输入：" + body.userInput(),
				Map.of("platform", body.platform() == null ? "" : body.platform(), "userInputLength",
						body.userInput().length(), "historyLength",
						body.history() == null ? 0 : body.history().length()),
				java.util.List.of(), Map.of(), java.util.List.of(), caller.accountId(), caller.organizationId());
	}

	/**
	 * 任务覆盖检查（§4.9.3「任务模式中展示未覆盖的任务要求」）：比对内容与任务要求，逐差距发帧。 task 要求由前端从草场 task
	 * 快照传入（intelligence 不跨服务读 marketplace）。经执行环聚合。
	 */
	@PostMapping("/task-coverage")
	public Mono<ResponseEntity<Flux<DataBuffer>>> taskCoverage(@RequestBody TaskCoverageRequest body,
			ServerWebExchange exchange) {
		if (body == null || body.content() == null || body.content().trim().length() < MIN_CONTENT_LENGTH) {
			return Mono.error(new IntelligenceException(400, "内容不能为空（至少 " + MIN_CONTENT_LENGTH + " 字）"));
		}
		if (body.taskRequirements() == null || body.taskRequirements().isBlank()) {
			return Mono.error(new IntelligenceException(400, "taskRequirements 不能为空"));
		}
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> frozenText.executeIndependent(exchange,
						CreationAssistantPrompts.taskCoverageMessages(body.content().trim(), body.taskRequirements(),
								body.platform()),
						ASSISTANT_MAX_TOKENS, CreditFeature.CREATION_ASSISTANT,
						completion -> parseCoverage(stripCodeFence(completion.content()))))
				.map(trace -> sseEntity(trace.value(), exchange));
	}

	/**
	 * 热点→选题（§4.9.5「从热点生成选题」）：把热点标题结构化为选题（角度/立意/受众/切入点）， 而非纯字符串。选题确认后由前端级联调既有
	 * titles→outline→content→image-rec。 经执行环聚合 LLM JSON → 发 topic 帧。
	 */
	@PostMapping("/topic-from-hot")
	public Mono<ResponseEntity<Flux<DataBuffer>>> topicFromHot(@RequestBody TopicFromHotRequest body,
			ServerWebExchange exchange) {
		if (body == null || body.hotTitle() == null || body.hotTitle().isBlank()) {
			return Mono.error(new IntelligenceException(400, "hotTitle 不能为空"));
		}
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> frozenText.executeIndependent(exchange,
						CreationAssistantPrompts.topicFromHotMessages(body.hotTitle(), body.platform(),
								body.angleHint()),
						ASSISTANT_MAX_TOKENS, CreditFeature.CREATION_ASSISTANT,
						completion -> parseTopic(stripCodeFence(completion.content()))))
				.map(trace -> sseEntity(trace.value(), exchange));
	}

	// ---- 评分解析 ----

	/** 解析 LLM 返回的评分 JSON 为结构化帧。 */
	private static ScoreResult parseScore(String json) {
		JsonNode root;
		try {
			root = MAPPER.readTree(json);
		} catch (Exception e) {
			throw new IntelligenceException(502, "评分返回了无法解析的内容");
		}
		JsonNode dims = root.path("dimensions");
		if (!dims.isArray() || dims.isEmpty()) {
			throw new IntelligenceException(502, "评分返回了无效数据");
		}
		ScoreResult result = new ScoreResult();
		for (JsonNode dim : dims) {
			String dimension = dim.path("dimension").asText("");
			int score = dim.path("score").asInt(0);
			String advice = dim.path("advice").asText("");
			if (!dimension.isBlank() && score > 0) {
				result.add(dimension, score, advice);
			}
		}
		result.overall = root.path("overall").asInt(0);
		if (result.frames.isEmpty()) {
			throw new IntelligenceException(502, "评分返回了无效数据");
		}
		return result;
	}

	/** 解析引导 JSON → 发 ask 帧（引导问题）或 brief 帧（创作 brief，含 inferredFields 标记推测）。 */
	private static Flux<String> parseGuide(String json) {
		JsonNode root;
		try {
			root = MAPPER.readTree(json);
		} catch (Exception e) {
			throw new IntelligenceException(502, "引导返回了无法解析的内容");
		}
		String action = root.path("action").asText("");
		if ("ask".equals(action)) {
			String question = root.path("question").asText("");
			if (question.isBlank()) {
				throw new IntelligenceException(502, "引导返回了无效数据");
			}
			return Flux.just(frame(Map.of("type", "ask", "question", question)));
		}
		if ("brief".equals(action)) {
			JsonNode brief = root.path("brief");
			if (brief.isMissingNode()) {
				throw new IntelligenceException(502, "引导返回了无效数据");
			}
			// inferredFields 是数组，序列化为逗号分隔字符串（frame 只收 String 值）
			java.util.List<String> inferred = new java.util.ArrayList<>();
			JsonNode inferredNode = brief.path("inferredFields");
			if (inferredNode.isArray()) {
				inferredNode.forEach(n -> inferred.add(n.asText()));
			}
			Map<String, Object> fields = new java.util.LinkedHashMap<>();
			fields.put("type", "brief");
			fields.put("angle", brief.path("angle").asText(""));
			fields.put("audience", brief.path("audience").asText(""));
			fields.put("structure", brief.path("structure").asText(""));
			fields.put("inferredFields", String.join(",", inferred));
			return Flux.just(frame(fields));
		}
		throw new IntelligenceException(502, "引导返回了无法识别的 action: " + action);
	}

	/** 解析任务覆盖 JSON → 逐差距发帧 + covered 帧。 */
	private static Flux<String> parseCoverage(String json) {
		JsonNode root;
		try {
			root = MAPPER.readTree(json);
		} catch (Exception e) {
			throw new IntelligenceException(502, "任务覆盖检查返回了无法解析的内容");
		}
		boolean covered = root.path("covered").asBoolean(false);
		JsonNode gaps = root.path("gaps");
		java.util.List<String> frames = new java.util.ArrayList<>();
		if (gaps.isArray()) {
			for (JsonNode gap : gaps) {
				Map<String, Object> fields = new java.util.LinkedHashMap<>();
				fields.put("type", "gap");
				fields.put("requirement", gap.path("requirement").asText(""));
				fields.put("status", gap.path("status").asText("missing"));
				fields.put("hint", gap.path("hint").asText(""));
				if (!String.valueOf(fields.get("requirement")).isBlank()) {
					frames.add(frame(fields));
				}
			}
		}
		frames.add(frame(Map.of("type", "covered", "covered", covered)));
		return Flux.fromIterable(frames);
	}

	/** 解析热点选题 JSON → 发 topic 帧（角度/立意/受众/切入点）。 */
	private static Flux<String> parseTopic(String json) {
		JsonNode root;
		try {
			root = MAPPER.readTree(json);
		} catch (Exception e) {
			throw new IntelligenceException(502, "热点选题返回了无法解析的内容");
		}
		String topic = root.path("topic").asText("");
		if (topic.isBlank()) {
			throw new IntelligenceException(502, "热点选题返回了无效数据");
		}
		// entryPoints 是数组，序列化为逗号分隔（frame 只收 String 值）
		java.util.List<String> entryPoints = new java.util.ArrayList<>();
		JsonNode epNode = root.path("entryPoints");
		if (epNode.isArray()) {
			epNode.forEach(n -> entryPoints.add(n.asText()));
		}
		Map<String, Object> fields = new java.util.LinkedHashMap<>();
		fields.put("type", "topic");
		fields.put("topic", topic);
		fields.put("angle", root.path("angle").asText(""));
		fields.put("thesis", root.path("thesis").asText(""));
		fields.put("audience", root.path("audience").asText(""));
		fields.put("entryPoints", String.join("；", entryPoints));
		return Flux.just(frame(fields));
	}

	// ---- SSE helpers（镜像 ArticleController，现有惯例各 controller 自持副本）----

	private ResponseEntity<Flux<DataBuffer>> sseEntity(Flux<String> payloads, ServerWebExchange exchange) {
		Flux<DataBuffer> sseBody = Sse.stream(payloads, exchange.getResponse().bufferFactory());
		HttpHeaders h = new HttpHeaders();
		h.setContentType(MediaType.TEXT_EVENT_STREAM);
		h.set("X-Accel-Buffering", "no");
		h.setCacheControl("no-cache");
		return new ResponseEntity<>(sseBody, h, HttpStatus.OK);
	}

	/**
	 * Preserve native JSON types so boolean and numeric SSE fields are not emitted
	 * as truthy strings.
	 */
	private static String frame(Map<String, Object> fields) {
		try {
			return MAPPER.writeValueAsString(fields);
		} catch (Exception e) {
			return "{\"error\":\"生成失败\"}";
		}
	}

	/**
	 * 剥 markdown code fence（{@code ```json ... ```}）。镜像
	 * ArticleController.stripCodeFence。
	 */
	private static String stripCodeFence(String raw) {
		String trimmed = raw.trim();
		if (trimmed.startsWith("```")) {
			int firstNewline = trimmed.indexOf('\n');
			if (firstNewline > 0) {
				trimmed = trimmed.substring(firstNewline + 1);
			}
			if (trimmed.endsWith("```")) {
				trimmed = trimmed.substring(0, trimmed.length() - 3);
			}
		}
		return trimmed.trim();
	}

	private static String requireContent(ScoreRequest body) {
		if (body == null || body.content() == null || body.content().trim().length() < MIN_CONTENT_LENGTH) {
			throw new IntelligenceException(400, "内容不能为空（至少 " + MIN_CONTENT_LENGTH + " 字）");
		}
		return body.content().trim();
	}

	// ---- DTO ----

	public record ScoreRequest(String content, String platform, String title) {
	}

	/** 引导请求：用户当前输入 + 目标平台（可空）+ 对话历史（可空，首轮）。 */
	public record GuideRequest(String userInput, String platform, String history) {
	}

	/** 任务覆盖检查请求：内容 + 任务要求（前端从 task 快照传入）+ 平台。 */
	public record TaskCoverageRequest(String content, String taskRequirements, String platform) {
	}

	/** 热点→选题请求：热点标题 + 目标平台（可空）+ 补充角度提示（可空）。 */
	public record TopicFromHotRequest(String hotTitle, String platform, String angleHint) {
	}

	/** 评分解析中间结果，累积逐维度 SSE 帧。 */
	private static final class ScoreResult {
		final java.util.List<String> frames = new java.util.ArrayList<>();
		int overall;

		void add(String dimension, int score, String advice) {
			frames.add(frame(Map.of("type", "score", "dimension", dimension, "score", score, "advice", advice)));
		}

		Flux<String> toFrames() {
			java.util.List<String> all = new java.util.ArrayList<>(frames);
			all.add(frame(Map.of("type", "overall", "score", overall)));
			return Flux.fromIterable(all);
		}
	}
}
