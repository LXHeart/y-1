package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 事件端点（任务书 #105C C105C-03 / K03 API18）：鉴权 + SSE 心跳。
 *
 * <p>
 * {@code afterSeq} 与 {@code Last-Event-ID} 同时有值 → 422（K03）；响应
 * {@code text/event-stream + no-store}；心跳为 20s 注释帧（不被普通 HTTP 代理聚合）。
 */
@RestController
public class DigitalHumanEventController {

	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanEventService events;

	public DigitalHumanEventController(DigitalHumanAuthorization authorization, DigitalHumanEventService events) {
		this.authorization = authorization;
		this.events = events;
	}

	@GetMapping("/api/digital-human/sessions/{id}/events")
	public Mono<ResponseEntity<Flux<ServerSentEvent<String>>>> stream(@PathVariable UUID id,
			@RequestParam(name = "afterSeq", required = false) Long afterSeq, ServerWebExchange exchange) {
		String lastEventId = exchange.getRequest().getHeaders().getFirst("Last-Event-ID");
		if (afterSeq != null && lastEventId != null && !lastEventId.isBlank()) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "afterSeq 与 Last-Event-ID 不能同时提供。"));
		}
		final Long from = resolveFrom(afterSeq, lastEventId);
		if (from == null && lastEventId != null && !lastEventId.isBlank()) {
			return Mono.error(new IntelligenceException(422, "dh_invalid_input", "Last-Event-ID 无效。"));
		}
		// 预检（401/404/429）在 ResponseEntity 产生前完成——SSE 响应一经提交只能关连接不能改状态码。
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> events.open(actor, id, from))
				.map(flux -> ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM)
						.<Flux<ServerSentEvent<String>>>body(flux));
	}

	private static Long resolveFrom(Long afterSeq, String lastEventId) {
		if (afterSeq != null) {
			return afterSeq;
		}
		if (lastEventId == null || lastEventId.isBlank()) {
			return null;
		}
		try {
			return Long.parseLong(lastEventId.trim());
		} catch (NumberFormatException invalid) {
			return null;
		}
	}
}
