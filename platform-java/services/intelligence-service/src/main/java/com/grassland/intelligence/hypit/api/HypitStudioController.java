package com.grassland.intelligence.hypit.api;

import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Hypit 会话端点（任务书 #107-1 §6.2 / C107-03 声明；C107-11/12/13 接 Studio/预览实现）。 会话由
 * Java 创建、sidecar 只接可信内部绑定——本卡不签发票据。
 */
@RestController
public class HypitStudioController {

	private final IntelligenceCallerResolver callers;
	private final HypitAccessService access;
	private final HypitProperties properties;

	private final com.grassland.intelligence.hypit.preview.HypitPreviewService previews;
	private final com.grassland.intelligence.hypit.studio.HypitStudioSessionService sessions;

	public HypitStudioController(IntelligenceCallerResolver callers, HypitAccessService access,
			HypitProperties properties, com.grassland.intelligence.hypit.preview.HypitPreviewService previews,
			com.grassland.intelligence.hypit.studio.HypitStudioSessionService sessions) {
		this.callers = callers;
		this.access = access;
		this.properties = properties;
		this.previews = previews;
		this.sessions = sessions;
	}

	private <T> Mono<T> pending(String what) {
		return Mono.error(properties.enabled() ? HypitAccessService.unavailable(what) : HypitAccessService.disabled());
	}

	/** C107-11：同文档预览会话——绑定 run/revision，缺失素材如实返回，不隐式 build。 */
	@PostMapping("/api/hypit/projects/{projectId}/preview-sessions")
	public Mono<ResponseEntity<Map<String, Object>>> preview(@PathVariable String projectId,
			@org.springframework.web.bind.annotation.RequestBody(required = false) PreviewSessionRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(previews.openSession(caller.accountId(), UUID.fromString(projectId),
								body == null ? null : body.requestId(), body == null ? null : body.runFile(),
								body == null ? null : body.revision())))
				.map(view -> ResponseEntity.status(HttpStatus.ACCEPTED)
						.body(HypitDtos.success(Map.of("sessionId", view.sessionId(), "ticketUrl", view.ticketUrl(),
								"expiresAt", view.expiresAt().toString(), "revision", view.revision(),
								"missingMaterials", view.missingMaterials()))));
	}

	public record PreviewSessionRequest(UUID requestId, String runFile, Long revision) {
	}

	/** C107-12：Studio 会话——一次性 ticket URL、同 Run 复用活跃会话、readOnly 拒写回。 */
	@PostMapping("/api/hypit/projects/{projectId}/studio-sessions")
	public Mono<ResponseEntity<Map<String, Object>>> studio(@PathVariable String projectId,
			@org.springframework.web.bind.annotation.RequestBody(required = false) StudioSessionRequest body,
			ServerWebExchange exchange) {
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> access.requireProjectOwner(caller, projectId)
						.then(sessions.openSession(caller.accountId(), UUID.fromString(projectId),
								body == null ? null : body.requestId(), body == null ? null : body.runFile(),
								body == null ? null : body.revision(), body != null && body.readOnly())))
				.map(view -> ResponseEntity.status(HttpStatus.ACCEPTED)
						.body(HypitDtos.success(Map.of("sessionId", view.sessionId(), "ticketUrl", view.ticketUrl(),
								"expiresAt", view.expiresAt(), "revision", view.revision(), "readOnly", view.readOnly(),
								"reused", view.reused()))));
	}

	public record StudioSessionRequest(UUID requestId, String runFile, Long revision, boolean readOnly) {
	}

	private static <T> ResponseEntity<Map<String, Object>> neverMap(T ignored) {
		throw new AssertionError("unreachable: pending() always errors");
	}
}
