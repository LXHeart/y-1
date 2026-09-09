package com.grassland.intelligence.cardseries;

import com.grassland.intelligence.ai.Sse;
import com.grassland.intelligence.cardseries.CardSeriesService.BatchResponse;
import com.grassland.intelligence.cardseries.CardSeriesService.CardPlan;
import com.grassland.intelligence.cardseries.CardSeriesService.GenerateInput;
import com.grassland.intelligence.cardseries.CardSeriesService.PlanInput;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 系列 AI 图卡 API（任务书 #54）：
 * <ul>
 * <li>{@code POST /api/card-series/plan}——拆卡计划（SSE progress/result 帧；计费 JSON
 * 先于流， moments 同款契约）。计划不落库，前端编辑后随 generate 回传。</li>
 * <li>{@code POST /api/card-series/generate}——逐卡生图（JSON，部分成功语义；每卡独立预算闸）。</li>
 * <li>{@code POST /api/card-series/cards/{id}/persist}——TTL 卡转永久 media
 * 行；素材库注册由前端 复用 {@code POST /api/content-assets}（个人库）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/card-series")
public class CardSeriesController {

	static final String PLAN_ERROR_MESSAGE = "卡片计划生成失败";

	private final IntelligenceCallerResolver callers;
	private final CardSeriesService service;

	public CardSeriesController(IntelligenceCallerResolver callers, CardSeriesService service) {
		this.callers = callers;
		this.service = service;
	}

	@PostMapping("/plan")
	public Mono<ResponseEntity<Flux<DataBuffer>>> plan(@RequestBody PlanRequest body, ServerWebExchange exchange) {
		PlanInput input = new PlanInput(body.platform(), body.content(),
				body.cardCount() == null ? 1 : body.cardCount(), body.styleText(), body.layoutText(),
				body.paletteText(), body.brief(), body.contextSnapshotId());
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> service.planStream(input, caller.accountId(), caller.organizationId(), exchange)
						.map(frames -> sseEntity(frames, exchange)))
				.onErrorMap(error -> error instanceof IntelligenceException
						? error
						: new IntelligenceException(502, PLAN_ERROR_MESSAGE));
	}

	@PostMapping("/generate")
	public Mono<Map<String, Object>> generate(@RequestBody GenerateRequest body, ServerWebExchange exchange) {
		List<CardPlan> cards = new ArrayList<>();
		if (body.cards() != null) {
			for (CardRequest card : body.cards()) {
				cards.add(new CardPlan(card.cardId(), card.position(), card.role(), card.title(), card.bullets(),
						card.illustration(), card.caption()));
			}
		}
		GenerateInput input = new GenerateInput(body.platform(), cards, body.styleText(), body.layoutText(),
				body.paletteText(), body.size(), body.styleAnchor(), body.contextSnapshotId());
		return callers
				.requireUser(exchange.getRequest()).flatMap(caller -> service.generateRecorded(input,
						caller.accountId(), caller.organizationId(), body.requestId()))
				.map(CardSeriesController::toBody);
	}

	/** 生成操作查询（T21）：网络超时先查询本端点；待确认运行不自动重发。 */
	@GetMapping("/operations/{requestId}")
	public Mono<Map<String, Object>> operation(@PathVariable String requestId, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest())
				.flatMap(caller -> service.findOperation(caller.accountId(), requestId))
				.switchIfEmpty(Mono.error(new IntelligenceException(404, "图卡操作不存在")))
				.map(CardSeriesController::success);
	}

	@PostMapping("/cards/{id}/persist")
	public Mono<Map<String, Object>> persist(@PathVariable String id, ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> service.persist(id, caller.accountId()))
				.map(response -> success(Map.of("mediaId", response.mediaId())));
	}

	private static Map<String, Object> toBody(BatchResponse response) {
		List<Map<String, Object>> cards = new ArrayList<>();
		for (CardSeriesService.CardOutcome outcome : response.cards()) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("index", outcome.index());
			if (outcome.cardId() != null)
				item.put("cardId", outcome.cardId());
			if (outcome.role() != null)
				item.put("role", outcome.role());
			item.put("title", outcome.title());
			item.put("ok", outcome.ok());
			if (outcome.ok()) {
				item.put("url", outcome.imageUrl());
				item.put("revisedPrompt", outcome.revisedPrompt());
			} else {
				item.put("errorReason", outcome.errorReason());
			}
			cards.add(item);
		}
		return success(Map.of("cards", cards));
	}

	private static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}

	private ResponseEntity<Flux<DataBuffer>> sseEntity(Flux<String> payloads, ServerWebExchange exchange) {
		Flux<DataBuffer> sseBody = Sse.stream(payloads, exchange.getResponse().bufferFactory());
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.TEXT_EVENT_STREAM);
		headers.set("X-Accel-Buffering", "no");
		headers.setCacheControl("no-cache");
		return new ResponseEntity<>(sseBody, headers, HttpStatus.OK);
	}

	/** 计划请求：content 为已生成的长图文内容；模板描述词由前端常量组装（后端模板无关）。 */
	public record PlanRequest(String platform, String content, Integer cardCount, String styleText, String layoutText,
			String paletteText, Map<String, Object> brief, java.util.UUID contextSnapshotId) {
	}

	public record CardRequest(String cardId, Integer position, String role, String title, List<String> bullets,
			String illustration, String caption) {
		/** 旧客户端请求兼容：缺省身份时由服务端按请求内位置补齐。 */
		public CardRequest(String title, List<String> bullets, String illustration, String caption) {
			this(null, null, null, title, bullets, illustration, caption);
		}
	}

	public record GenerateRequest(String platform, List<CardRequest> cards, String styleText, String layoutText,
			String paletteText, String size, String styleAnchor, java.util.UUID contextSnapshotId, String requestId) {

		/** 旧客户端载荷（无操作记录/任务绑定）兼容。 */
		public GenerateRequest(String platform, List<CardRequest> cards, String styleText, String layoutText,
				String paletteText, String size, String styleAnchor) {
			this(platform, cards, styleText, layoutText, paletteText, size, styleAnchor, null, null);
		}
	}
}
