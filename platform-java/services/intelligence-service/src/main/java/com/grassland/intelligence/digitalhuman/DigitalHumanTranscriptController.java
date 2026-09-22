package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 转写端点（任务书 #105C C105C-04 / K03 API22～26）。版本/owner 校验在服务层；导出为 text/plain
 * 附件（filename 不使用原 prompt）。
 */
@RestController
public class DigitalHumanTranscriptController {

	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanTranscriptService transcripts;

	public DigitalHumanTranscriptController(DigitalHumanAuthorization authorization,
			DigitalHumanTranscriptService transcripts) {
		this.authorization = authorization;
		this.transcripts = transcripts;
	}

	// API22
	@PatchMapping("/api/digital-human/sessions/{id}/transcript-preference")
	public Mono<ResponseEntity<Map<String, Object>>> setPreference(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			PreferenceRequest request = DigitalHumanOperations.parseStrict(body, PreferenceRequest.class);
			if (request.requestId() == null || request.expectedVersion() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId/expectedVersion 必填。"));
			}
			return transcripts.setPreference(actor, id, Boolean.TRUE.equals(request.saveTranscript()),
					request.expectedVersion(), request.requestId()).map(data -> envelope(data));
		});
	}

	// API23
	@PostMapping("/api/digital-human/sessions/{id}/transcript-save")
	public Mono<ResponseEntity<Map<String, Object>>> save(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			PreferenceRequest request = DigitalHumanOperations.parseStrict(body, PreferenceRequest.class);
			if (request.requestId() == null || request.expectedVersion() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId/expectedVersion 必填。"));
			}
			return transcripts.saveBuffered(actor, id, request.expectedVersion(), request.requestId())
					.map(data -> envelope(data));
		});
	}

	// API24
	@GetMapping("/api/digital-human/sessions/{id}/transcript")
	public Mono<ResponseEntity<Map<String, Object>>> list(@PathVariable UUID id,
			@RequestParam(name = "cursor", required = false) String cursor,
			@RequestParam(name = "limit", required = false) Integer limit, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest())
				.flatMap(actor -> transcripts.list(actor, id, cursor, limit)).map(page -> envelope(page));
	}

	// API25：text/plain 附件；只导出已保存文本。
	@GetMapping("/api/digital-human/sessions/{id}/transcript/export")
	public Mono<ResponseEntity<String>> export(@PathVariable UUID id, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> transcripts.exportText(actor, id))
				.map(text -> ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN)
						.header("Content-Disposition", "attachment; filename=\"dh-transcript.txt\"")
						.cacheControl(CacheControl.noStore()).body(text));
	}

	// API26：删除墓碑（202 Operation）。
	@DeleteMapping("/api/digital-human/sessions/{id}/transcript")
	public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			DeleteRequest request = DigitalHumanOperations.parseStrict(body, DeleteRequest.class);
			if (request.requestId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId 必填。"));
			}
			return transcripts.delete(actor, id, request.requestId()).map(data -> ResponseEntity.accepted()
					.cacheControl(CacheControl.noStore()).body(Map.of("success", true, "data", data)));
		});
	}

	private static ResponseEntity<Map<String, Object>> envelope(Object data) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("success", true, "data", data));
	}

	public record PreferenceRequest(UUID requestId, Integer expectedVersion, Boolean saveTranscript) {
	}

	public record DeleteRequest(UUID requestId) {
	}
}
