package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.InputMode;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.Preflight;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.SessionSnapshot;
import com.grassland.intelligence.digitalhuman.DigitalHumanSessionService.CapacityFullException;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 会话端点（任务书 #105C C105C-01 / K03 API07、API08、API10）。Controller 只装配。
 *
 * <p>
 * API08 202（preparing/queued/connecting 经 runtime 派发后为 connecting）；队满 429 带
 * Retry-After； GET /sessions/{id} 回扁平 SessionSnapshot（replayComplete=false，普通
 * GET 不做重放）。
 */
@RestController
public class DigitalHumanSessionController {

	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanPreflightService preflights;
	private final DigitalHumanSessionService sessions;
	private final DigitalHumanLeaseService leases;

	public DigitalHumanSessionController(DigitalHumanAuthorization authorization,
			DigitalHumanPreflightService preflights, DigitalHumanSessionService sessions,
			DigitalHumanLeaseService leases) {
		this.authorization = authorization;
		this.preflights = preflights;
		this.sessions = sessions;
		this.leases = leases;
	}

	// API07
	@PostMapping("/api/digital-human/preflights")
	public Mono<ResponseEntity<Map<String, Object>>> createPreflight(@RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			PreflightRequest request = DigitalHumanOperations.parseStrict(body, PreflightRequest.class);
			if (request.profileId() == null || request.controllerId() == null || request.inputMode() == null) {
				return Mono.error(
						new IntelligenceException(422, "dh_invalid_input", "profileId/inputMode/controllerId 必填。"));
			}
			return preflights
					.check(actor, UUID.fromString(request.profileId()), request.profileVersion(),
							InputMode.valueOf(request.inputMode()), UUID.fromString(request.controllerId()))
					.map(preflight -> envelope(preflight));
		});
	}

	// API08
	@PostMapping("/api/digital-human/sessions")
	public Mono<ResponseEntity<Map<String, Object>>> createSession(@RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			CreateSessionRequest request = DigitalHumanOperations.parseStrict(body, CreateSessionRequest.class);
			if (request.preflightId() == null || request.requestId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "preflightId/requestId 必填。"));
			}
			return sessions
					.create(actor, UUID.fromString(request.preflightId()), request.requestId(),
							Boolean.TRUE.equals(request.saveTranscript()))
					.map(result -> ResponseEntity.accepted().cacheControl(CacheControl.noStore())
							.body(Map.of("success", true, "data", DigitalHumanSessionService.toDto(result.row()))));
		}).onErrorResume(CapacityFullException.class,
				error -> Mono.just(ResponseEntity.status(429).cacheControl(CacheControl.noStore())
						.header("Retry-After", error.retryAfterSeconds())
						.body(Map.of("success", false, "error", error.getMessage(), "code", error.code()))));
	}

	// API11：pause（隐藏不续命：pausedUntil 固定）。
	@PostMapping("/api/digital-human/sessions/{id}/pause")
	public Mono<ResponseEntity<Map<String, Object>>> pause(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			LeaseRequest request = DigitalHumanOperations.parseStrict(body, LeaseRequest.class);
			return leases.pause(actor, id, request.requestId(), request.leaseEpoch(), request.reason())
					.map(result -> envelope(result));
		});
	}

	// API12：resume（新控制器需 takeover=true）。
	@PostMapping("/api/digital-human/sessions/{id}/resume")
	public Mono<ResponseEntity<Map<String, Object>>> resume(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			ResumeRequest request = DigitalHumanOperations.parseStrict(body, ResumeRequest.class);
			return leases
					.resume(actor, id, request.leaseEpoch(), request.controllerId(),
							Boolean.TRUE.equals(request.takeover()), request.requestId())
					.map(result -> envelope(result));
		});
	}

	// API13：heartbeat（只续租约，不延长 pausedUntil）。
	@PostMapping("/api/digital-human/sessions/{id}/heartbeat")
	public Mono<ResponseEntity<Map<String, Object>>> heartbeat(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			LeaseRequest request = DigitalHumanOperations.parseStrict(body, LeaseRequest.class);
			return leases.heartbeat(actor, id, request.leaseEpoch(), request.controllerId())
					.map(result -> envelope(result));
		});
	}

	// API14：end（202 ending / 幂等同终态）。
	@PostMapping("/api/digital-human/sessions/{id}/end")
	public Mono<ResponseEntity<Map<String, Object>>> end(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			LeaseRequest request = DigitalHumanOperations.parseStrict(body, LeaseRequest.class);
			return sessions.end(actor, id, request.requestId(), request.reason())
					.map(result -> ResponseEntity.accepted().cacheControl(CacheControl.noStore())
							.body(Map.of("success", true, "data", Map.of("state", result.state()))));
		});
	}

	// API10
	@GetMapping("/api/digital-human/sessions/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> getSession(@PathVariable UUID id, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> sessions.get(actor, id))
				.map(snapshot -> envelope(snapshot));
	}

	private static ResponseEntity<Map<String, Object>> envelope(Object data) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("success", true, "data", data));
	}

	public record PreflightRequest(String profileId, Integer profileVersion, String inputMode, String controllerId) {
	}

	public record CreateSessionRequest(String preflightId, UUID requestId, Boolean saveTranscript) {
	}

	public record LeaseRequest(UUID requestId, Long leaseEpoch, UUID controllerId, String reason) {
	}

	public record ResumeRequest(UUID requestId, Long leaseEpoch, UUID controllerId, Boolean takeover) {
	}
}
