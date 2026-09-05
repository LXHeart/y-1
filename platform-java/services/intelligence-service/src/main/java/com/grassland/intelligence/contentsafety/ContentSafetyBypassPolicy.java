package com.grassland.intelligence.contentsafety;

import com.grassland.intelligence.ai.byok.ByokRoutingService;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 内容安全「平台资助能力」绕过判定（任务书 #78 卡 B，D3 口径定死）：
 *
 * <p>
 * <b>凡该次主体的 text 路由解析为自有凭据即绕过</b>（个人 BYOK 与组织 BYOK 命中都算）， 平台凭据照旧。被绕过的平台免费能力 =
 * L2 AI 深检（{@link ContentSafetyAiChecker}）与
 * 内容修复（{@code ContentSafetyFixService}）；L1 词库 + SimHash 原创度是本地零成本底线，永不绕过。
 *
 * <p>
 * 判定方式 = 按同一 exchange 主体幂等重解析 text 路由（无副作用、不落 run）：商家活动身份
 * （caller.organizationId 非空）走组织段——组织 BYOK 命中即绕过；个人身份走总开关段。解析失败、 匿名主体或
 * DENIED/PLATFORM 一律<b>不绕过</b>（保守默认：深检照跑，平台底线不放松）。
 */
@Component
public class ContentSafetyBypassPolicy {

	private final IntelligenceCallerResolver callers;
	private final ByokRoutingService routing;

	public ContentSafetyBypassPolicy(IntelligenceCallerResolver callers, ByokRoutingService routing) {
		this.callers = callers;
		this.routing = routing;
	}

	/** true = 该主体本次 text 路由为自有凭据（BYOK），平台深检/修复应跳过。永不 error。 */
	public Mono<Boolean> isOwnSource(ServerWebExchange exchange) {
		return callers.resolveOptional(exchange.getRequest())
				.flatMap(caller -> routing.resolveProvider(caller.organizationId(), caller.accountId(), "text", true))
				.map(ByokRoutingService.ProviderResolution::isByok).defaultIfEmpty(false)
				.onErrorResume(error -> Mono.just(false));
	}
}
