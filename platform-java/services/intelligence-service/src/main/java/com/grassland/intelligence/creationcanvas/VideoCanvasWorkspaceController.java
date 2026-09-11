package com.grassland.intelligence.creationcanvas;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * API-07（任务书 #100 C100-04）：POST /api/video-production/storyboards/{id}/workspace。
 * 按分镜唯一补草稿关联；新建与重放都返回同一关联（WorkspaceBindingResult）。
 * 业务裁决在 {@link VideoCanvasWorkspaceService}，本层只做 wire。
 */
@RestController
public class VideoCanvasWorkspaceController {

	private final IntelligenceCallerResolver callers;
	private final VideoCanvasWorkspaceService service;

	public VideoCanvasWorkspaceController(IntelligenceCallerResolver callers,
			VideoCanvasWorkspaceService service) {
		this.callers = callers;
		this.service = service;
	}

	@PostMapping("/api/video-production/storyboards/{id}/workspace")
	public Mono<ResponseEntity<Map<String, Object>>> bind(@PathVariable String id,
			@RequestBody VideoCanvasWorkspaceService.BindingRequest body, ServerWebExchange exchange) {
		UUID storyboardId;
		try {
			storyboardId = UUID.fromString(id);
		} catch (Exception e) {
			return Mono.error(new IntelligenceException(400, "id 格式无效"));
		}
		return callers.resolve(exchange.getRequest())
				.flatMap(caller -> service.bind(caller, storyboardId, body))
				.map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)));
	}
}
