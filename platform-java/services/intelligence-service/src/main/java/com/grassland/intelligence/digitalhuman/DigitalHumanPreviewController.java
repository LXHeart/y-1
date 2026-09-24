package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanPreviewService.PreviewOutcome;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 试听公开端点（任务书 #105D C105D-04 / 共享契约 K03 API27～29）：固定句试听、状态、认证音频流。
 *
 * <p>
 * API27 只接受 voiceId/catalogVersion/requestId——固定句由服务端给出，不接受任意 text；API29 为认证流
 * （audio/wav），不写素材库；GET 过期 410。
 */
@RestController
public class DigitalHumanPreviewController {

	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanPreviewService previews;

	public DigitalHumanPreviewController(DigitalHumanAuthorization authorization, DigitalHumanPreviewService previews) {
		this.authorization = authorization;
		this.previews = previews;
	}

	// API27
	@PostMapping("/api/digital-human/voice-previews")
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody String body, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			var request = DigitalHumanOperations.parseStrict(body, CreatePreviewRequest.class);
			if (request.requestId() == null || request.voiceId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId/voiceId 必填。"));
			}
			return previews
					.create(actor, request.requestId(), request.voiceId(),
							request.catalogVersion() == null ? 0 : request.catalogVersion())
					.map(outcome -> ResponseEntity
							.status(outcome.state().equals("ready") ? 200 : HttpStatus.ACCEPTED.value())
							.cacheControl(CacheControl.noStore())
							.body(Map.of("success", true, "data",
									Map.of("id", outcome.id(), "state", outcome.state(), "expiresAt",
											outcome.expiresAt() == null ? "" : outcome.expiresAt().toString(),
											"errorCode", outcome.errorCode() == null ? "" : outcome.errorCode()))));
		});
	}

	// API28
	@GetMapping("/api/digital-human/voice-previews/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable UUID id, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest())
				.flatMap(actor -> previews.get(actor, id).map(this::outcomeResponse));
	}

	// API29
	@GetMapping("/api/digital-human/voice-previews/{id}/audio")
	public Mono<ResponseEntity<byte[]>> audio(@PathVariable UUID id, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest())
				.flatMap(actor -> previews.readAudio(actor, id)
						.map(wav -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
								.contentType(MediaType.parseMediaType("audio/wav"))
								.header("Content-Disposition", "inline").body(wav)));
	}

	// ---------- 私有 ----------

	private ResponseEntity<Map<String, Object>> outcomeResponse(PreviewOutcome outcome) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore())
				.body(Map.of("success", true, "data",
						Map.of("id", outcome.id(), "state", outcome.state(), "expiresAt",
								outcome.expiresAt() == null ? "" : outcome.expiresAt().toString(), "errorCode",
								outcome.errorCode() == null ? "" : outcome.errorCode())));
	}

	public record CreatePreviewRequest(UUID requestId, String voiceId, Integer catalogVersion) {
	}
}
