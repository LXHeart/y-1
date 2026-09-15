package com.grassland.finance.escrow;

import com.grassland.finance.security.FinanceCallerResolver;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 退出资金权威事实内部端点（任务书 #103 C103-03 / §6.2）：仅 marketplace-service principal 可读；
 * 不在公共 Edge 清单注册（/internal 前缀保持服务间私网）。404 = 无事实；组织不符按 404 处理
 * （不泄露他组织资源存在性）。
 */
@RestController
public class EngagementFundsQueryController {

	private final FinanceCallerResolver callers;
	private final EngagementFundsQueryService service;

	public EngagementFundsQueryController(FinanceCallerResolver callers, EngagementFundsQueryService service) {
		this.callers = callers;
		this.service = service;
	}

	@GetMapping("/internal/engagements/{engagementRef}/exit-facts")
	public Mono<ResponseEntity<Map<String, Object>>> exitFacts(@PathVariable String engagementRef,
			@RequestParam String organizationId, ServerHttpRequest request) {
		return callers.requireServiceForOrg(request, organizationId, FinanceCallerResolver.MARKETPLACE_SERVICE)
				.then(service.find(engagementRef, organizationId))
				.map(data -> ResponseEntity.ok(Map.of("success", true, "data", data)))
				.onErrorResume(IllegalArgumentException.class,
						e -> Mono.just(ResponseEntity.status(404).body(Map.of("success", false,
								"error", "engagement funds not found in this organization"))));
	}
}
