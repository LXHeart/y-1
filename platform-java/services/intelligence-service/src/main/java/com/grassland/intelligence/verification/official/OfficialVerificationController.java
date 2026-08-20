package com.grassland.intelligence.verification.official;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 官方数据源内部端点（P1 骨架，ADR-D04）：{@code POST /internal/verification/official-data}。 仅
 * marketplace 服务断言可调（不进 edge RouteManifest，外部不可达）。
 *
 * <p>
 * 响应三态：{@code configured:false}（网关未启用，marketplace 省略 official_data 检查项）/
 * {@code configured:true, unavailable:true}（网关故障 → inconclusive）/ 数据体（三态字段 +
 * 归一化指标）。平台与目标链接必填（422→调用方按契约错误处理）；commentText 可选 （评论存在性判定仅评论任务需要）。
 */
@RestController
public class OfficialVerificationController {

	private final IntelligenceCallerResolver callers;
	private final ObjectProvider<OfficialVerificationGateway> gateway;

	public OfficialVerificationController(IntelligenceCallerResolver callers,
			ObjectProvider<OfficialVerificationGateway> gateway) {
		this.callers = callers;
		this.gateway = gateway;
	}

	@PostMapping("/internal/verification/official-data")
	public Mono<Map<String, Object>> fetch(@RequestBody Map<String, Object> body, ServerWebExchange exchange) {
		String platform = text(body, "platform");
		String contentUrl = text(body, "contentUrl");
		if (platform.isBlank() || contentUrl.isBlank()) {
			throw new IllegalArgumentException("platform 与 contentUrl 不能为空");
		}
		return callers.requireServicePrincipal(exchange.getRequest(), IntelligenceCallerResolver.MARKETPLACE_SERVICE)
				.flatMap(caller -> {
					OfficialVerificationGateway adapter = gateway.getIfAvailable();
					if (adapter == null) {
						return Mono.just(Map.<String, Object>of("success", true, "data", Map.of("configured", false)));
					}
					return adapter.fetch(platform, contentUrl, text(body, "platformHandle"), text(body, "commentText"))
							.map(data -> {
								Map<String, Object> payload = new LinkedHashMap<>();
								payload.put("configured", true);
								payload.put("accountMatch", data.accountMatch());
								payload.put("published", data.published());
								payload.put("commentFound", data.commentFound());
								payload.put("metrics", data.metrics());
								return Map.<String, Object>of("success", true, "data", payload);
							}).defaultIfEmpty(unavailable()).onErrorResume(error -> Mono.just(unavailable()));
				});
	}

	private static Map<String, Object> unavailable() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("configured", true);
		payload.put("unavailable", true);
		return Map.of("success", true, "data", payload);
	}

	private static String text(Map<String, Object> body, String key) {
		Object value = body == null ? null : body.get(key);
		return value instanceof String string && !string.isBlank() ? string.trim() : "";
	}

	/** 契约错误自含映射 400（内部端点不依赖全局 advice 的完整装配）。 */
	@org.springframework.web.bind.annotation.ExceptionHandler(IllegalArgumentException.class)
	public org.springframework.http.ResponseEntity<Map<String, Object>> handleBadInput(IllegalArgumentException error) {
		return org.springframework.http.ResponseEntity.badRequest()
				.body(Map.of("success", false, "error", error.getMessage()));
	}

	/** 鉴权/断言失败自含映射（401/403 语义随 IntelligenceException.status）。 */
	@org.springframework.web.bind.annotation.ExceptionHandler(com.grassland.intelligence.security.IntelligenceException.class)
	public org.springframework.http.ResponseEntity<Map<String, Object>> handleIntelligence(
			com.grassland.intelligence.security.IntelligenceException error) {
		return org.springframework.http.ResponseEntity.status(error.status())
				.body(Map.of("success", false, "error", error.getMessage()));
	}
}
