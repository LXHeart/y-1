package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.TurnReceipt;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 轮次公开端点（任务书 #105D C105D-05 / 共享契约 K03 API19～21）：DTO/owner/lease 校验后转 service。
 */
@RestController
public class DigitalHumanTurnController {

	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanTurnService turns;

	public DigitalHumanTurnController(DigitalHumanAuthorization authorization, DigitalHumanTurnService turns) {
		this.authorization = authorization;
		this.turns = turns;
	}

	// API19
	@PostMapping("/api/digital-human/sessions/{id}/turns")
	public Mono<ResponseEntity<Map<String, Object>>> startTurn(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			TurnRequest request = DigitalHumanOperations.parseStrict(body, TurnRequest.class);
			if (request.requestId() == null || request.leaseEpoch() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId/leaseEpoch 必填。"));
			}
			return turns.startTurn(actor, id, request.requestId(), request.leaseEpoch(), request.text())
					.map(receipt -> accepted(receipt));
		});
	}

	// API20
	@PostMapping("/api/digital-human/sessions/{id}/interrupt")
	public Mono<ResponseEntity<Map<String, Object>>> interrupt(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			InterruptRequest request = DigitalHumanOperations.parseStrict(body, InterruptRequest.class);
			if (request.requestId() == null || request.leaseEpoch() == null || request.turnId() == null
					|| request.turnEpoch() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input",
						"requestId/leaseEpoch/turnId/turnEpoch 必填。"));
			}
			return turns
					.interrupt(actor, id, request.requestId(), request.leaseEpoch(), UUID.fromString(request.turnId()),
							request.turnEpoch())
					.map(result -> ResponseEntity.accepted().cacheControl(CacheControl.noStore())
							.body(Map.of("success", true, "data", result)));
		});
	}

	// API21：greeting（TTS-only，每场一次受理）。
	@PostMapping("/api/digital-human/sessions/{id}/greeting")
	public Mono<ResponseEntity<Map<String, Object>>> greeting(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			TurnRequest request = DigitalHumanOperations.parseStrict(body, TurnRequest.class);
			if (request.requestId() == null || request.leaseEpoch() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId/leaseEpoch 必填。"));
			}
			return turns.greeting(actor, id, request.requestId(), request.leaseEpoch()).map(this::accepted);
		});
	}

	// ---------- 私有 ----------

	private ResponseEntity<Map<String, Object>> accepted(TurnReceipt receipt) {
		return ResponseEntity.accepted().cacheControl(CacheControl.noStore())
				.body(Map.of("success", true, "data", Map.of("id", receipt.id(), "sessionId", receipt.sessionId(),
						"turnEpoch", receipt.turnEpoch(), "state", receipt.state().name())));
	}

	public record TurnRequest(UUID requestId, Long leaseEpoch, String text) {
	}

	public record InterruptRequest(UUID requestId, Long leaseEpoch, String turnId, Long turnEpoch) {
	}
}
