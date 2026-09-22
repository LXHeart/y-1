package com.grassland.intelligence.digitalhuman;

import com.grassland.intelligence.compliance.IntelligenceAccountLifecycleRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 数字人个人鉴权（任务书 #105B C105B-02 / K01/K02）：从已验证 Caller 取 account，scope 固定个人。
 *
 * <p>
 * 只调用 {@link IntelligenceCallerResolver#requireUser} 一次（不重复消费内部断言）；service
 * principal 被过滤不能冒充个人。{@link PersonalActor} 只有 accountId——即使 Caller 带商家
 * org，也<b>不</b>把 organizationId/预算上下文传入 dh 域（DH-R01：个人归属固定，orgId 不由浏览器提交、也不由
 * Caller 穿透）。
 */
@Component
public class DigitalHumanAuthorization {

	/** 个人执行者（K07.1）：organizationId 固定 null，不带 org key/预算。 */
	public record PersonalActor(String accountId) {
		public PersonalActor {
			if (accountId == null || accountId.isBlank()) {
				throw new IllegalArgumentException("accountId 必填");
			}
		}
	}

	/** 归属复查目标：任何携带 owner 的行实现本接口即可参与 owner 复查。 */
	public interface ResourceOwner {

		String ownerAccountId();
	}

	private final IntelligenceCallerResolver callers;
	private final IntelligenceAccountLifecycleRepository lifecycle;

	public DigitalHumanAuthorization(IntelligenceCallerResolver callers,
			IntelligenceAccountLifecycleRepository lifecycle) {
		this.callers = callers;
		this.lifecycle = lifecycle;
	}

	/**
	 * 个人身份门：未登录/断言失效 → 401 {@code dh_auth_required}；service principal 或无账号 → 403
	 * {@code dh_account_unavailable}（K01 口径）。
	 */
	public Mono<PersonalActor> requirePersonal(ServerHttpRequest request) {
		return callers.requireUser(request).map(caller -> new PersonalActor(caller.accountId()))
				.onErrorMap(IntelligenceException.class, error -> switch (error.status()) {
					// 断言缺/过期/无效（resolve 抛 401）→ 统一 dh_auth_required。
					case 401 -> new IntelligenceException(401, "dh_auth_required", "请先登录后再使用数字人工作台。");
					// requireUser 的 403 只剩「service principal / 无账号」形态。
					case 403 -> new IntelligenceException(403, "dh_account_unavailable", "当前身份不能使用数字人工作台。");
					default -> error;
				});
	}

	/**
	 * owner 复查：非本人资源统一 404 {@code dh_not_found}（与不存在同文案，不泄露存在性）。
	 */
	public Mono<Void> requireOwned(PersonalActor actor, ResourceOwner owner) {
		return requireOwned(actor, owner == null ? null : owner.ownerAccountId());
	}

	public Mono<Void> requireOwned(PersonalActor actor, String ownerAccountId) {
		if (ownerAccountId != null && actor.accountId().equals(ownerAccountId)) {
			return Mono.empty();
		}
		return Mono.error(new IntelligenceException(404, "dh_not_found", "资源不存在。"));
	}

	/** 生命周期门（写路径）：gate 缺行视为 active；冻结/清理中 → 403 {@code dh_account_unavailable}。 */
	public Mono<PersonalActor> requireActiveAccount(PersonalActor actor) {
		return lifecycle.find(actor.accountId()).map(java.util.Optional::of).defaultIfEmpty(java.util.Optional.empty())
				.flatMap(gate -> gate.isEmpty() || "active".equals(gate.get().state())
						? Mono.just(actor)
						: Mono.<PersonalActor>error(
								new IntelligenceException(403, "dh_account_unavailable", "账号已进入注销流程，操作不可用。")));
	}
}
