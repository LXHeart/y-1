package com.grassland.intelligence.smoke;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
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
 * 内部冒烟端点（草场 intelligence Slice 1）——非业务，验证整条链路： edge-bff 断言 → intelligence
 * callerResolver → 执行环（预算闸/ai_run/BYOK 路由/积分闭环） → 平台 Qwen 完成 → SSE 透传。
 *
 * <p>
 * <b>扣积分 + 限流</b>（GL-P0-SEC-002）。本端点真实消耗平台 Qwen 上游，按
 * {@link CreditFeature#INTELLIGENCE_SMOKE} 扣积分，并由 {@link SmokePreflightFilter}
 * 做每账号限流。 GL-P3-AI-001 尾巴清偿后经执行环单环执行（流式收敛为完成聚合后单帧 content——冒烟本就验证
 * 链路而非打字机体验），402/502 在 SSE 前以 JSON 返回。
 */
@RestController
public class SmokeController {

	private static final String DEFAULT_PROMPT = "用一句话介绍草场（Grassland）这个内容撮合平台。";

	private final IntelligenceCallerResolver callers;
	private final FrozenTextExecutionService frozenText;
	private final ObjectMapper mapper = new ObjectMapper();

	public SmokeController(IntelligenceCallerResolver callers, FrozenTextExecutionService frozenText) {
		this.callers = callers;
		this.frozenText = frozenText;
	}

	@PostMapping("/api/intelligence/smoke/chat")
	public Mono<ResponseEntity<Flux<DataBuffer>>> chat(@RequestBody SmokeRequest body, ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest()).flatMap(caller -> {
			String prompt = (body != null && body.prompt() != null && !body.prompt().isBlank())
					? body.prompt()
					: DEFAULT_PROMPT;
			return frozenText.executeIndependent(exchange,
					List.of(ChatMessage.system("你是草场平台的助手，回答简短友好。"), ChatMessage.user(prompt)), 512,
					CreditFeature.INTELLIGENCE_SMOKE, completion -> completion.content());
		}).map(trace -> {
			Flux<String> payloads = Flux.just(contentPayload(trace.value()));
			Flux<DataBuffer> sseBody = Sse.stream(payloads, exchange.getResponse().bufferFactory());
			HttpHeaders h = new HttpHeaders();
			h.setContentType(MediaType.TEXT_EVENT_STREAM);
			h.set("X-Accel-Buffering", "no");
			h.setCacheControl("no-cache");
			return new ResponseEntity<>(sseBody, h, HttpStatus.OK);
		});
	}

	private String contentPayload(String content) {
		try {
			return mapper.writeValueAsString(Map.of("content", content == null ? "" : content));
		} catch (Exception e) {
			return "{\"content\":\"\"}";
		}
	}

	/** 冒烟请求体。prompt 可省略（走默认）。 */
	public record SmokeRequest(String prompt) {
	}
}
