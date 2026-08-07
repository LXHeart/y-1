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
                            .onErrorResume(e -> credits.refund(charge, "优化建议失败自动退回")
                                    .then(Mono.error(e)));
                    return sseEntity(payloads, exchange);
                });
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

    // ---- SSE helpers（镜像 ArticleController，现有惯例各 controller 自持副本）----

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

    /** 评分解析中间结果，累积逐维度 SSE 帧。 */
    private static final class ScoreResult {
        final java.util.List<String> frames = new java.util.ArrayList<>();
        int overall;

        void add(String dimension, int score, String advice) {
            frames.add(frame(Map.of("type", "score", "dimension", dimension,
                    "score", String.valueOf(score), "advice", advice)));
        }

        Flux<String> toFrames() {
            java.util.List<String> all = new java.util.ArrayList<>(frames);
            all.add(frame(Map.of("type", "overall", "score", String.valueOf(overall))));
            return Flux.fromIterable(all);
        }
    }
}
