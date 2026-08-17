package com.grassland.intelligence.article;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.article.ArticlePrompts.Platform;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
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
 * <p>与 legacy 行为一致：<b>仅 titles 扣 1 积分</b>（{@link CreditFeature#ARTICLE_GENERATION}），
 * outline / content 是免费 SSE（创作者已为 titles 付费后的免费跟进）。titles 非流式——聚合流式输出
 * 后剥 markdown code fence 解析 {@code {titles:[{title,hook}]}}，失败→502。
 */
@RestController
public class ArticleController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final IntelligenceCallerResolver callers;
    private final AiCapabilityAdapter ai;
    private final CreditsClient credits;
    private final FrozenTextExecutionService frozenText;
    private final ArticleCreationContext creationContexts;
    private final com.grassland.intelligence.contentsafety.ContentSafetyService safety;

    public ArticleController(IntelligenceCallerResolver callers, AiCapabilityAdapter ai, CreditsClient credits,
                             FrozenTextExecutionService frozenText, ArticleCreationContext creationContexts,
                             com.grassland.intelligence.contentsafety.ContentSafetyService safety) {
        this.callers = callers;
        this.ai = ai;
        this.credits = credits;
        this.frozenText = frozenText;
        this.creationContexts = creationContexts;
        this.safety = safety;
    }

    // ---------- titles：扣积分 + 聚合流式 → 解析 JSON ----------

    @PostMapping("/api/article-generation/titles")
    public Mono<Map<String, Object>> titles(@RequestBody TitlesRequest body, ServerWebExchange exchange) {
        Platform platform = Platform.fromKey(body.platform());
        if (body.isTaskMode()) {
            return callers.requireUser(exchange.getRequest())
                    .flatMap(caller -> creationContexts.bind(
                            body.contextSnapshotId(), caller.accountId(), body.platform()))
                    .flatMap(binding -> frozenText.execute(
                            exchange, body.contextSnapshotId(), List.of(
                                    ArticlePrompts.titlesSystem(binding.platform()),
                                    binding.promptContext(), ArticlePrompts.titlesUser(body.topic())),
                            1024, CreditFeature.ARTICLE_GENERATION,
                            completion -> parseTitles(completion.content())))
                    .flatMap(titles -> titlesBody(titles));
        }
        return callers.resolve(exchange.getRequest())
                .flatMap(caller -> credits.consume(caller.accountId(), CreditFeature.ARTICLE_GENERATION))
                .flatMap(charge -> ai.startTextRun(new TextRunCommand(List.of(
                        ArticlePrompts.titlesSystem(platform), ArticlePrompts.titlesUser(body.topic()))))
                        .map(ChatChunk::content)
                        .collectList()
                        .map(chunks -> String.join("", chunks))
                        .map(ArticleController::parseTitles)
                        // 上游失败：退回已扣积分后仍抛原始错误（GL-P0-BILL-002）
                        .onErrorResume(error -> credits.refund(charge, "标题生成失败自动退回")
                                .then(Mono.error(error))))
                .flatMap(this::titlesBody);
    }

    /** titles 响应：data 内嵌 safety 块（标题为短文本仅 L1；任务书 #34 D8）。 */
    private Mono<Map<String, Object>> titlesBody(List<Title> titles) {
        String joined = titles.stream()
                .map(Title::title)
                .reduce("", (a, b) -> a + " " + b);
        return Mono.just(Map.<String, Object>of(
                "success", true,
                "data", Map.of(
                        "titles", titles,
                        "safety", safety.reportBody(safety.checkShallow(joined)))));
    }

    // ---------- outline：免费 SSE ----------

    @PostMapping("/api/article-generation/outline")
    public Mono<ResponseEntity<Flux<DataBuffer>>> outline(@RequestBody OutlineRequest body, ServerWebExchange exchange) {
        Platform platform = Platform.fromKey(body.platform());
        if (body.isTaskMode()) {
            return taskStream(exchange, body.contextSnapshotId(), body.platform(), binding -> List.of(
                    ArticlePrompts.outlineSystem(binding.platform()), binding.promptContext(),
                    ArticlePrompts.outlineUser(body.topic(), body.title())), 2048, "大纲生成失败", false);
        }
        return callers.resolve(exchange.getRequest()).map(caller -> {
            Flux<String> payloads = ai.startTextRun(new TextRunCommand(List.of(
                    ArticlePrompts.outlineSystem(platform),
                    ArticlePrompts.outlineUser(body.topic(), body.title()))))
                    .map(chunk -> frame(Map.of("content", chunk.content())))
                    .onErrorResume(e -> Flux.just(frame(Map.of("error", "大纲生成失败"))));
            return sseEntity(payloads, exchange);
        });
    }

    // ---------- content：免费 SSE ----------

    @PostMapping("/api/article-generation/content")
    public Mono<ResponseEntity<Flux<DataBuffer>>> content(@RequestBody ContentRequest body, ServerWebExchange exchange) {
        Platform platform = Platform.fromKey(body.platform());
        if (body.isTaskMode()) {
            return taskStream(exchange, body.contextSnapshotId(), body.platform(), binding -> List.of(
                    ArticlePrompts.contentSystem(binding.platform()), binding.promptContext(),
                    ArticlePrompts.contentUser(body.topic(), body.title(), body.outline())), 4096, "正文生成失败", true);
        }
        return callers.resolve(exchange.getRequest()).map(caller -> {
            Flux<String> payloads = ai.startTextRun(new TextRunCommand(List.of(
                    ArticlePrompts.contentSystem(platform),
                    ArticlePrompts.contentUser(body.topic(), body.title(), body.outline()))))
                    .map(chunk -> frame(Map.of("content", chunk.content())))
                    .onErrorResume(e -> Flux.just(frame(Map.of("error", "正文生成失败"))));
            // 任务书 #34 D8：正文（长文本）流尾追加安全检查帧（L1 必跑 + L2 已配置时深检）
            return sseEntity(safety.appendSafetyFrame(exchange, payloads,
                    com.grassland.intelligence.contentsafety.ContentSafetyService.contentFieldExtractor()),
                    exchange);
        });
    }

    // ---------- helpers ----------

    private Mono<ResponseEntity<Flux<DataBuffer>>> taskStream(
            ServerWebExchange exchange, UUID snapshotId, String platform,
            java.util.function.Function<ArticleCreationContext.Binding, List<com.grassland.intelligence.ai.ChatMessage>> messages,
            int maxTokens, String failureMessage, boolean appendSafety) {
        return callers.requireUser(exchange.getRequest())
                .flatMap(caller -> creationContexts.bind(snapshotId, caller.accountId(), platform))
                .flatMap(binding -> frozenText.execute(
                        exchange, snapshotId, messages.apply(binding), maxTokens, null,
                        completion -> completion.content()))
                .map(content -> {
                    Flux<String> frames = Flux.just(frame(Map.of("content", content)));
                    if (appendSafety) {
                        frames = safety.appendSafetyFrame(exchange, frames,
                                com.grassland.intelligence.contentsafety.ContentSafetyService.contentFieldExtractor());
                    }
                    return sseEntity(frames, exchange);
                })
                .onErrorMap(error -> error instanceof IntelligenceException
                        ? error : new IntelligenceException(502, failureMessage));
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

    /** 剥 markdown code fence（{@code ```json ... ```}）→ 解析 {@code {titles:[{title,hook}]}}。 */
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
    public record Title(String title, String hook) {}

    /** topic 1-200；platform 可省略（默认 wechat）。 */
    public record TitlesRequest(
            String topic, String platform, Boolean taskMode, UUID contextSnapshotId) {
        public TitlesRequest(String topic, String platform) {
            this(topic, platform, false, null);
        }

        public TitlesRequest {
            topic = topic == null ? "" : topic.trim();
            if (topic.isEmpty() || topic.length() > 200) {
                throw new IllegalArgumentException("请输入主题或关键词");
            }
        }

        boolean isTaskMode() { return Boolean.TRUE.equals(taskMode); }
    }

    /** topic 1-200、title 1-100；platform 可省略。 */
    public record OutlineRequest(
            String topic, String title, String platform, Boolean taskMode, UUID contextSnapshotId) {
        public OutlineRequest(String topic, String title, String platform) {
            this(topic, title, platform, false, null);
        }

        public OutlineRequest {
            topic = topic == null ? "" : topic.trim();
            title = title == null ? "" : title.trim();
            if (topic.isEmpty() || topic.length() > 200) {
                throw new IllegalArgumentException("请输入主题");
            }
            if (title.isEmpty() || title.length() > 100) {
                throw new IllegalArgumentException("请选择或输入标题");
            }
        }

        boolean isTaskMode() { return Boolean.TRUE.equals(taskMode); }
    }

    /** topic 1-200、title 1-100、outline ≥10；platform 可省略。 */
    public record ContentRequest(
            String topic, String title, String outline, String platform,
            Boolean taskMode, UUID contextSnapshotId) {
        public ContentRequest(String topic, String title, String outline, String platform) {
            this(topic, title, outline, platform, false, null);
        }

        public ContentRequest {
            topic = topic == null ? "" : topic.trim();
            title = title == null ? "" : title.trim();
            outline = outline == null ? "" : outline.trim();
            if (topic.isEmpty() || topic.length() > 200) {
                throw new IllegalArgumentException("请输入主题");
            }
            if (title.isEmpty() || title.length() > 100) {
                throw new IllegalArgumentException("请选择或输入标题");
            }
            if (outline.length() < 10) {
                throw new IllegalArgumentException("大纲内容过短");
            }
        }

        boolean isTaskMode() { return Boolean.TRUE.equals(taskMode); }
    }
}
