package com.grassland.intelligence.homepage;

import com.grassland.intelligence.hottopic.HotTopicFilter;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 首页热点 HTTP 入口（GL: homepage 迁移）。响应契约与 legacy 1:1（前端零改动）。
 *
 * <p>
 * <b>公开无鉴权</b>：数据源由平台级配置决定（任务书 #47 S7b——{@code homepage_hot_config}，
 * 管理后台维护），对所有访问者一致，不再按登录身份分源。
 */
@RestController
public class HomepageController {

	private final HomepageHotService hotService;
	private final HotItemsHistoryService hotHistory;

	public HomepageController(HomepageHotService hotService, HotItemsHistoryService hotHistory) {
		this.hotService = hotService;
		this.hotHistory = hotHistory;
	}

	/**
	 * 热点历史聚合（缺口清偿之八 / PRD §4.3 时间范围）：range=today|week，公开无鉴权（对齐 hot-items）。
	 * provider 分源：与实时榜同读平台配置，两源同 platform 组不跨源混并。
	 */
	@GetMapping("/api/homepage/hot-items/history")
	public Mono<ResponseEntity<Map<String, Object>>> hotItemsHistory(
			@RequestParam(defaultValue = "today") String range) {
		if (!"today".equals(range) && !"week".equals(range)) {
			throw new IntelligenceException(400, "range 仅支持 today/week");
		}
		return hotService.provider()
				.flatMap(provider -> hotHistory.history(range, provider))
				.map(result -> ResponseEntity.ok(Map.of("success", true, "data", result)));
	}

	@GetMapping("/api/homepage/hot-items")
	public Mono<ResponseEntity<Map<String, Object>>> hotItems(
			@RequestParam(name = "industry", required = false) List<String> industries,
			@RequestParam(name = "city", required = false) List<String> cities,
			@RequestParam(name = "contentType", required = false) List<String> contentTypes,
			@RequestParam(name = "includeExpired", defaultValue = "false") boolean includeExpired) {
		HotTopicFilter filter = HotTopicFilter.from(industries, cities, contentTypes, includeExpired);
		return hotService.loadHotItems(filter)
				.map(result -> ResponseEntity.ok(Map.of("success", true, "data", result)));
	}

	@ExceptionHandler(IntelligenceException.class)
	public ResponseEntity<Map<String, Object>> handleError(IntelligenceException error) {
		return ResponseEntity.status(error.status()).body(Map.of("success", false, "error", error.getMessage()));
	}
}
