package com.grassland.intelligence.smoke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.List;
import java.util.Map;
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
 * 内部冒烟端点（草场 intelligence Slice 1）——非业务，验证整条链路：
 * edge-bff 断言 → intelligence callerResolver → 平台默认 Qwen 流式 → SSE 字节级透传。
 *
 * <p>不扣积分（冒烟免扣；积分链路由 {@code CreditsClient} 单测 + 后续业务 slice 真实验证）。
 * 后续业务 controller（脱口秀等）结构与本端点同构：{@code resolve → startTextRun → Sse.stream}。
 */
@RestController
public class SmokeController {

    private static final String DEFAULT_PROMPT = "用一句话介绍草场（Grassland）这个内容撮合平台。";

    private final IntelligenceCallerResolver callers;
    private final AiCapabilityAdapter ai;
    private final ObjectMapper mapper = new ObjectMapper();

    public SmokeController(IntelligenceCallerResolver callers, AiCapabilityAdapter ai) {
        this.callers = callers;
        this.ai = ai;
    }

    @PostMapping("/api/intelligence/smoke/chat")
    public Mono<ResponseEntity<Flux<DataBuffer>>> chat(@RequestBody SmokeRequest body, ServerWebExchange exchange) {
        return callers.resolve(exchange.getRequest())
                .map(c -> {
                    String prompt = (body != null && body.prompt() != null && !body.prompt().isBlank())
                            ? body.prompt()
                            : DEFAULT_PROMPT;
                    Flux<String> payloads = ai.startTextRun(new TextRunCommand(List.of(
                            ChatMessage.system("你是草场平台的助手，回答简短友好。"),
                            ChatMessage.user(prompt))))
                            .map(this::contentPayload);
                    Flux<DataBuffer> sseBody = Sse.stream(payloads, exchange.getResponse().bufferFactory());
                    HttpHeaders h = new HttpHeaders();
                    h.setContentType(MediaType.TEXT_EVENT_STREAM);
                    h.set("X-Accel-Buffering", "no");
                    h.setCacheControl("no-cache");
                    return new ResponseEntity<>(sseBody, h, HttpStatus.OK);
                });
    }

    private String contentPayload(ChatChunk chunk) {
        try {
            return mapper.writeValueAsString(Map.of("content", chunk.content()));
        } catch (Exception e) {
            return "{\"content\":\"\"}";
        }
    }

    /** 冒烟请求体。prompt 可省略（走默认）。 */
    public record SmokeRequest(String prompt) {}
}
