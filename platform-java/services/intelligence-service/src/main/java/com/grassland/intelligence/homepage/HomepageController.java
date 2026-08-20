package com.grassland.intelligence.homepage;

import com.grassland.intelligence.hottopic.HotTopicFilter;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 首页热点 HTTP 入口（GL: homepage 迁移）。响应契约与 legacy 1:1（前端零改动）。
 *
 * <p>
 * <b>不强制登录</b>：登录用户按其 homepage settings 选 provider；未登录走平台默认（60s）。
 */
@RestController
public class HomepageController {

	private final IntelligenceCallerResolver callers;
	private final HomepageHotService hotService;
	private final HotItemsHistoryService hotHistory;

	public HomepageController(IntelligenceCallerResolver callers, HomepageHotService hotService,
			HotItemsHistoryService hotHistory) {
		this.callers = callers;
		this.hotService = hotService;
		this.hotHistory = hotHistory;
	}

	/**
	 * 热点历史聚合（缺口清偿之八 / PRD §4.3 时间范围）：range=today|week，公开无鉴权（对齐 hot-items）。 provider
	 * 分源（之八遗留清偿）：登录用户按其 homepage settings 选源，未登录走平台默认 60s—— 与实时榜同语义，两源同 platform
	 * 组不跨源混并。
	 */
	@GetMapping("/api/homepage/hot-items/history")
	public Mono<ResponseEntity<Map<String, Object>>> hotItemsHistory(ServerHttpRequest request,
			@RequestParam(defaultValue = "today") String range) {
		if (!"today".equals(range) && !"week".equals(range)) {
			throw new IntelligenceException(400, "range 仅支持 today/week");
		}
		return callers.resolveOptional(request).map(IntelligenceCallerResolver.Caller::accountId).defaultIfEmpty("")
				.flatMap(accountId -> hotService.providerFor(accountId.isBlank() ? null : accountId))
				.flatMap(provider -> hotHistory.history(range, provider))
				.map(result -> ResponseEntity.ok(Map.of("success", true, "data", result)));
	}

	@GetMapping("/api/homepage/hot-items")
	public Mono<ResponseEntity<Map<String, Object>>> hotItems(ServerHttpRequest request,
			@RequestParam(name = "industry", required = false) List<String> industries,
			@RequestParam(name = "city", required = false) List<String> cities,
			@RequestParam(name = "contentType", required = false) List<String> contentTypes,
			@RequestParam(name = "includeExpired", defaultValue = "false") boolean includeExpired) {
		HotTopicFilter filter = HotTopicFilter.from(industries, cities, contentTypes, includeExpired);
		return callers.resolveOptional(request).map(IntelligenceCallerResolver.Caller::accountId).defaultIfEmpty("")
				.flatMap(accountId -> hotService.loadHotItems(accountId.isBlank() ? null : accountId, filter))
				.map(result -> ResponseEntity.ok(Map.of("success", true, "data", result)));
	}

	@ExceptionHandler(IntelligenceException.class)
	public ResponseEntity<Map<String, Object>> handleError(IntelligenceException error) {
		return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
	}
}
