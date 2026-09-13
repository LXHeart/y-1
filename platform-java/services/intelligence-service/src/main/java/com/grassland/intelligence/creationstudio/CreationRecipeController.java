package com.grassland.intelligence.creationstudio;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 任务书 #101 API101-01：{@code GET /api/creation-studio/recipes}——模板目录读取。
 *
 * <p>
 * query platform／contentForm／processingMode 均可省略（返回可见集合）；非空时必须是 合法枚举值（未知值 400
 * STUDIO_INVALID_INPUT，不静默忽略过滤器）。登录用户可见； Edge
 * 前缀路由（EDGE_ROUTE_CREATION_STUDIO_INTELLIGENCE，默认关）由 §6.10 收口， 未实现端点保持
 * Controller 404，不返回占位成功。公开响应不携带 prompt。
 */
@RestController
public class CreationRecipeController {

	private static final Set<String> PLATFORMS = Set.of("xiaohongshu", "douyin", "dianping", "kuaishou",
			"wechat-channels", "bilibili", "wechat-official", "zhihu", "moments");
	private static final Set<String> CONTENT_FORMS = Set.of("graphic", "video", "image-text", "video-text");
	private static final Set<String> PROCESSING_MODES = Set.of("create", "adapt", "format");

	private final IntelligenceCallerResolver callers;

	public CreationRecipeController(IntelligenceCallerResolver callers) {
		this.callers = callers;
	}

	@GetMapping("/api/creation-studio/recipes")
	public Mono<Map<String, Object>> recipes(@RequestParam(required = false) String platform,
			@RequestParam(required = false) String contentForm, @RequestParam(required = false) String processingMode,
			ServerWebExchange exchange) {
		return callers.requireUser(exchange.getRequest()).map(caller -> {
			requireLegalEnum("platform", platform, PLATFORMS);
			requireLegalEnum("contentForm", contentForm, CONTENT_FORMS);
			requireLegalEnum("processingMode", processingMode, PROCESSING_MODES);
			List<Map<String, Object>> items = new ArrayList<>();
			for (CreationRecipeCatalog.RecipeDefinition recipe : CreationRecipeCatalog.visible(platform, contentForm,
					processingMode)) {
				items.add(toBody(recipe));
			}
			return success(Map.of("items", items));
		});
	}

	private static void requireLegalEnum(String field, String value, Set<String> allowed) {
		if (value != null && !allowed.contains(value)) {
			throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "字段 " + field + " 的值不合法");
		}
	}

	private static Map<String, Object> toBody(CreationRecipeCatalog.RecipeDefinition recipe) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("id", recipe.id());
		body.put("version", recipe.version());
		body.put("label", recipe.label());
		body.put("enabled", recipe.enabled());
		body.put("platformIds", recipe.platformIds());
		body.put("contentForms", recipe.contentForms());
		body.put("processingModes", recipe.processingModes());
		body.put("minItems", recipe.minItems());
		body.put("maxItems", recipe.maxItems());
		body.put("defaultAspect", recipe.defaultAspect());
		body.put("supportedStrategies", recipe.supportedStrategies());
		return body;
	}

	private static Map<String, Object> success(Object data) {
		return Map.of("success", true, "data", data);
	}
}
