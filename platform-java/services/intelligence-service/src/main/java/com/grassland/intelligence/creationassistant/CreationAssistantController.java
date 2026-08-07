package com.grassland.intelligence.creationassistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
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
 * <p>两个 SSE 端点（镜像 {@code ArticleController} 的流式范式 + 手写 consume/refund）：
 * <ul>
 *   <li>{@code POST /api/creation-assistant/score} — 内容评分（§4.9.6）：聚合 LLM 输出后解析 JSON，
 *       逐维度发 {@code {type:score,dimension,score,advice}} 帧 + overall 帧。</li>
 *   <li>{@code POST /api/creation-assistant/suggest} — 优化建议（§4.9.4）：纯流式 {@code {content}} 帧。</li>
 * </ul>
 *
 * <p>两个端点都扣 {@link CreditFeature#CREATION_ASSISTANT} 1 积分；上游失败退款（GL-P0-BILL-002）。
 */
@RestController
@RequestMapping("/api/creation-assistant")
public class CreationAssistantController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MIN_CONTENT_LENGTH = 10;

    private final IntelligenceCallerResolver callers;
    private final AiCapabilityAdapter ai;
    private final CreditsClient credits;

    public CreationAssistantController(
            IntelligenceCallerResolver callers,
            AiCapabilityAdapter ai,
            CreditsClient credits) {
        this.callers = callers;
        this.ai = ai;
        this.credits = credits;
    }

    /** 内容评分（§4.9.6）：聚合 LLM JSON 输出 → 逐维度发 SSE 帧。 */
    @PostMapping("/score")
    public Mono<ResponseEntity<Flux<DataBuffer>>> score(
            @RequestBody ScoreRequest body, ServerWebExchange exchange) {
        String content = requireContent(body);
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> credits.consume(caller.accountId(), CreditFeature.CREATION_ASSISTANT))
                .flatMap(charge -> ai.startTextRun(new TextRunCommand(
                        CreationAssistantPrompts.scoreMessages(content, body.platform(), body.title())))
                        .map(ChatChunk::content)
                        .collectList()
                        .map(chunks -> String.join("", chunks))
                        .map(raw -> parseScore(stripCodeFence(raw)))
                        // 上游失败：退回已扣积分后仍抛原始错误（GL-P0-BILL-002）
                        .onErrorResume(error -> credits.refund(charge, "内容评分失败自动退回")
                                .then(Mono.error(error))))
                .map(scored -> sseEntity(scored.toFrames(), exchange));
    }

    /** 优化建议（§4.9.4）：纯流式 SSE。 */
    @PostMapping("/suggest")
    public Mono<ResponseEntity<Flux<DataBuffer>>> suggest(
            @RequestBody ScoreRequest body, ServerWebExchange exchange) {
        String content = requireContent(body);
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> credits.consume(caller.accountId(), CreditFeature.CREATION_ASSISTANT))
                .map(charge -> {
                    Flux<String> payloads = ai.startTextRun(new TextRunCommand(
                            CreationAssistantPrompts.suggestMessages(content, body.platform(), body.title())))
                            .map(chunk -> frame(Map.of("content", chunk.content())))
                            // 头已随 200 + text/event-stream 发出，此处 Mono.error 客户端只会看到流被截断。
                            // 退款后改发错误帧（镜像 ArticleController 的 outline/content 流），前端可读可提示。
                            .onErrorResume(e -> credits.refund(charge, "优化建议失败自动退回")
                                    .thenMany(Flux.just(frame(Map.of("error", "优化建议生成失败")))));
                    return sseEntity(payloads, exchange);
                });
    }

    /**
     * 问答引导（§4.9.1/§4.9.2）：根据用户当前输入，AI 决定问下一个引导问题（ask）还是给出创作 brief。
     * brief 里推测/补全的字段标 inferred（§4.9.2「明确标记推测内容」）。聚合 LLM JSON → 发帧。
     */
    @PostMapping("/guide")
    public Mono<ResponseEntity<Flux<DataBuffer>>> guide(
            @RequestBody GuideRequest body, ServerWebExchange exchange) {
        if (body == null || body.userInput() == null || body.userInput().isBlank()) {
            return Mono.error(new IntelligenceException(400, "userInput 不能为空"));
        }
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> credits.consume(caller.accountId(), CreditFeature.CREATION_ASSISTANT))
                .flatMap(charge -> ai.startTextRun(new TextRunCommand(
                        CreationAssistantPrompts.guideMessages(body.userInput(), body.platform(), body.history())))
                        .map(ChatChunk::content)
                        .collectList()
                        .map(chunks -> String.join("", chunks))
                        .map(raw -> parseGuide(stripCodeFence(raw)))
                        .onErrorResume(error -> credits.refund(charge, "引导失败自动退回")
                                .then(Mono.error(error))))
                .map(frames -> sseEntity(frames, exchange));
    }

    /**
     * 任务覆盖检查（§4.9.3「任务模式中展示未覆盖的任务要求」）：比对内容与任务要求，逐差距发帧。
     * task 要求由前端从草场 task 快照传入（intelligence 不跨服务读 marketplace）。
     */
    @PostMapping("/task-coverage")
    public Mono<ResponseEntity<Flux<DataBuffer>>> taskCoverage(
            @RequestBody TaskCoverageRequest body, ServerWebExchange exchange) {
        if (body == null || body.content() == null || body.content().trim().length() < MIN_CONTENT_LENGTH) {
            return Mono.error(new IntelligenceException(400, "内容不能为空（至少 " + MIN_CONTENT_LENGTH + " 字）"));
        }
        if (body.taskRequirements() == null || body.taskRequirements().isBlank()) {
            return Mono.error(new IntelligenceException(400, "taskRequirements 不能为空"));
        }
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> credits.consume(caller.accountId(), CreditFeature.CREATION_ASSISTANT))
                .flatMap(charge -> ai.startTextRun(new TextRunCommand(
                        CreationAssistantPrompts.taskCoverageMessages(
                                body.content().trim(), body.taskRequirements(), body.platform())))
                        .map(ChatChunk::content)
                        .collectList()
                        .map(chunks -> String.join("", chunks))
                        .map(raw -> parseCoverage(stripCodeFence(raw)))
                        .onErrorResume(error -> credits.refund(charge, "任务覆盖检查失败自动退回")
                                .then(Mono.error(error))))
                .map(frames -> sseEntity(frames, exchange));
    }

    /**
     * 热点→选题（§4.9.5「从热点生成选题」）：把热点标题结构化为选题（角度/立意/受众/切入点），
     * 而非纯字符串。选题确认后由前端级联调既有 titles→outline→content→image-rec。
     * 聚合 LLM JSON → 发 topic 帧。
     */
    @PostMapping("/topic-from-hot")
    public Mono<ResponseEntity<Flux<DataBuffer>>> topicFromHot(
            @RequestBody TopicFromHotRequest body, ServerWebExchange exchange) {
        if (body == null || body.hotTitle() == null || body.hotTitle().isBlank()) {
            return Mono.error(new IntelligenceException(400, "hotTitle 不能为空"));
        }
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> credits.consume(caller.accountId(), CreditFeature.CREATION_ASSISTANT))
                .flatMap(charge -> ai.startTextRun(new TextRunCommand(
                        CreationAssistantPrompts.topicFromHotMessages(
                                body.hotTitle(), body.platform(), body.angleHint())))
                        .map(ChatChunk::content)
                        .collectList()
                        .map(chunks -> String.join("", chunks))
                        .map(raw -> parseTopic(stripCodeFence(raw)))
                        .onErrorResume(error -> credits.refund(charge, "热点选题失败自动退回")
                                .then(Mono.error(error))))
                .map(frames -> sseEntity(frames, exchange));
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
     * 序列化一个 SSE data 帧。值类型是 {@code Object} 而非 String —— boolean/数字必须以原生 JSON 类型
     * 出去：{@code {"covered":"false"}} 在 JS 里是 truthy 字符串，前端拿它做判断必然反向。
     */
    private static String frame(Map<String, Object> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            return "{\"error\":\"生成失败\"}";
        }
    }

    /** 剥 markdown code fence（{@code ```json ... ```}）。镜像 ArticleController.stripCodeFence。 */
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

    public record ScoreRequest(String content, String platform, String title) {}

    /** 引导请求：用户当前输入 + 目标平台（可空）+ 对话历史（可空，首轮）。 */
    public record GuideRequest(String userInput, String platform, String history) {}

    /** 任务覆盖检查请求：内容 + 任务要求（前端从 task 快照传入）+ 平台。 */
    public record TaskCoverageRequest(String content, String taskRequirements, String platform) {}

    /** 热点→选题请求：热点标题 + 目标平台（可空）+ 补充角度提示（可空）。 */
    public record TopicFromHotRequest(String hotTitle, String platform, String angleHint) {}

    /** 评分解析中间结果，累积逐维度 SSE 帧。 */
    private static final class ScoreResult {
        final java.util.List<String> frames = new java.util.ArrayList<>();
        int overall;

        void add(String dimension, int score, String advice) {
            frames.add(frame(Map.of("type", "score", "dimension", dimension,
                    "score", score, "advice", advice)));
        }

        Flux<String> toFrames() {
            java.util.List<String> all = new java.util.ArrayList<>(frames);
            all.add(frame(Map.of("type", "overall", "score", overall)));
            return Flux.fromIterable(all);
        }
    }
}
