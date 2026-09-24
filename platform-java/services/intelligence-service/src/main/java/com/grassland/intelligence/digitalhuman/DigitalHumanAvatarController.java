package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.digitalhuman.DigitalHumanAuthorization.PersonalActor;
import com.grassland.intelligence.digitalhuman.DigitalHumanRecords.AvatarItem;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 自有形象端点（任务书 #105F C105F-01 / K03 API30～API32）。Controller 只装配：鉴权→严格解码→服务→
 * 信封；异步状态如实暴露（processing/ready/failed/revoked），不把处理中伪装成就绪。
 *
 * <p>
 * API30 202 AvatarItem（processing）；API31 200 AvatarItem（deleted 统一 404）；API32
 * 202 Operation （DELETE 带 JSON requestId，K03：不用 204）。所有正文 no-store。
 */
@RestController
public class DigitalHumanAvatarController {

	private final DigitalHumanAuthorization authorization;
	private final DigitalHumanAvatarService avatars;
	private final DigitalHumanOperations operations;

	public DigitalHumanAvatarController(DigitalHumanAuthorization authorization, DigitalHumanAvatarService avatars,
			DigitalHumanOperations operations) {
		this.authorization = authorization;
		this.avatars = avatars;
		this.operations = operations;
	}

	// API30
	@PostMapping(path = "/api/digital-human/avatars", consumes = "application/json")
	public Mono<ResponseEntity<Map<String, Object>>> create(@RequestBody String body, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			CreateRequest request = DigitalHumanOperations.parseStrict(body, CreateRequest.class);
			if (request.requestId() == null || request.mediaId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId/mediaId 必填。"));
			}
			return avatars
					.create(actor, request.mediaId(),
							request.rightsAccepted() == null ? false : request.rightsAccepted(),
							request.rightsVersion(), request.requestId(), true)
					.map(item -> ResponseEntity.accepted().cacheControl(noStore())
							.body(Map.of("success", true, "data", item)));
		});
	}

	// API31
	@GetMapping("/api/digital-human/avatars/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> get(@PathVariable UUID id, ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest())
				.flatMap(actor -> avatars.get(actor, id).map(item -> envelope(item)));
	}

	// API32：202 Operation（revoked 立即拒新建；活动会话引用 409）。
	@DeleteMapping("/api/digital-human/avatars/{id}")
	public Mono<ResponseEntity<Map<String, Object>>> delete(@PathVariable UUID id, @RequestBody String body,
			ServerWebExchange exchange) {
		return authorization.requirePersonal(exchange.getRequest()).flatMap(actor -> {
			DeleteRequest request = DigitalHumanOperations.parseStrict(body, DeleteRequest.class);
			if (request.requestId() == null) {
				return Mono.error(new IntelligenceException(422, "dh_invalid_input", "requestId 必填。"));
			}
			return avatars.delete(actor, id, request.requestId())
					.flatMap(row -> operations
							.findByKey(actor, DigitalHumanRecords.OperationKind.avatar_delete, request.requestId())
							.<ResponseEntity<Map<String, Object>>>map(operation -> ResponseEntity.accepted()
									.cacheControl(noStore()).body(operationEnvelope(operation))));
		});
	}

	private static Map<String, Object> operationEnvelope(DigitalHumanRecords.OperationRow operation) {
		Map<String, Object> data = new java.util.LinkedHashMap<>();
		data.put("id", operation.id());
		data.put("kind", operation.kind().name());
		data.put("state", operation.state().name());
		data.put("resourceId", operation.resourceId());
		data.put("resultRef", operation.resultRef());
		data.put("errorCode", operation.errorCode());
		data.put("createdAt", operation.createdAt().toString());
		data.put("updatedAt", operation.updatedAt().toString());
		return Map.of("success", true, "data", data);
	}

	private static ResponseEntity<Map<String, Object>> envelope(Object data) {
		return ResponseEntity.ok().cacheControl(noStore()).body(Map.of("success", true, "data", data));
	}

	private static CacheControl noStore() {
		return CacheControl.noStore();
	}

	/** API30 请求（严格解码；rightsAccepted 必须 true、rightsVersion='dh-avatar-v1'）。 */
	public record CreateRequest(UUID requestId, UUID mediaId, Boolean rightsAccepted, String rightsVersion) {
	}

	public record DeleteRequest(UUID requestId) {
	}
}
