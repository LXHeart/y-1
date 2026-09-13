package com.grassland.intelligence.creationstudio.render;

import com.grassland.intelligence.creationstudio.StudioRequestValidator;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 API101-18（C101-16）：POST /api/creation-studio/render-previews。
 * 纯计算端点：无 requestId（不落操作记录）、零模型 调用；theme 仅 standard／compact。
 */
@RestController
public class CreationRenderController {

	private static final Set<String> PREVIEW_FIELDS = Set.of("draftId", "version", "theme", "includeTitle",
			"citeExternalLinks");

	private final IntelligenceCallerResolver callers;
	private final CreationRenderService renders;

	public CreationRenderController(IntelligenceCallerResolver callers, CreationRenderService renders) {
		this.callers = callers;
		this.renders = renders;
	}

	@PostMapping("/api/creation-studio/render-previews")
	public Mono<Map<String, Object>> preview(@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
		StudioRequestValidator.requireObject(body, "请求体");
		StudioRequestValidator.rejectUnknownFields(body, PREVIEW_FIELDS);
		UUID draftId = StudioRequestValidator.requireUuid(body, "draftId");
		int version = StudioRequestValidator.requireInt(body, "version");
		if (version < 1) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "version 必须是正整数");
		}
		String theme = StudioRequestValidator.requireEnum(body, "theme", Set.of("standard", "compact"));
		Boolean includeTitle = StudioRequestValidator.optionalBoolean(body, "includeTitle");
		Boolean citeExternalLinks = StudioRequestValidator.optionalBoolean(body, "citeExternalLinks");
		var command = new CreationRenderService.PreviewCommand(draftId, version, theme,
				Boolean.TRUE.equals(includeTitle), Boolean.TRUE.equals(citeExternalLinks));
		return callers.requireUser(exchange.getRequest()).flatMap(caller -> renders.preview(caller, command))
				.map(outcome -> Map.of("success", true, "data", outcome.preview()));
	}
}
