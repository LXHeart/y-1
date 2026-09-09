package com.grassland.marketplace.reputation;

import com.grassland.marketplace.security.MarketplaceCallerResolver;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 任务书 #98 C98-04（§6）：商家信用读端点——指标 + 三档标签 + 口径版本；登录用户可读
 * （推荐官选人参考），无任何写路径（读时派生，D98-04 无自动惩罚）。
 */
@RestController
public class MerchantCreditController {

	private final MarketplaceCallerResolver callers;
	private final MerchantCreditService credits;

	public MerchantCreditController(MarketplaceCallerResolver callers, MerchantCreditService credits) {
		this.callers = callers;
		this.credits = credits;
	}

	@GetMapping("/api/merchants/{orgId}/credit")
	public Mono<ResponseEntity<Map<String, Object>>> credit(@PathVariable String orgId, ServerHttpRequest request) {
		return callers.requireUser(request).then(credits.compute(orgId))
				.map(credit -> ResponseEntity.ok(Map.of("success", true, "data", credits.fullBody(credit))));
	}
}
