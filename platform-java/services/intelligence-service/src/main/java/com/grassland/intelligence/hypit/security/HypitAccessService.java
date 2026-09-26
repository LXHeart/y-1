package com.grassland.intelligence.hypit.security;

import com.grassland.intelligence.hypit.config.HypitProperties;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Hypit 域统一归属/权限判定（任务书 #107-1 C107-03 / K09.2；C107-04 接入工程归属）。
 *
 * <p>
 * controller 进入 {@link IntelligenceCallerResolver#resolve} 一次，把已验证 Caller 显式传入
 * service；本服务只做 operator 判定与共享错误构造，不再二次 resolve（避免重复消费 jti）。
 */
@Service
public class HypitAccessService {

	private final HypitProperties properties;
	private final HypitProjectRepository projects;

	public HypitAccessService(HypitProperties properties, HypitProjectRepository projects) {
		this.properties = properties;
		this.projects = projects;
	}

	/** 部署 operator 名单（HYPIT_OPERATOR_ACCOUNT_IDS）；「已登录」不替代 operator。 */
	public boolean isOperator(IntelligenceCallerResolver.Caller caller) {
		return caller != null && caller.accountId() != null && properties.operators().contains(caller.accountId());
	}

	public Mono<IntelligenceCallerResolver.Caller> requireOperator(IntelligenceCallerResolver.Caller caller) {
		if (isOperator(caller)) {
			return Mono.just(caller);
		}
		return Mono.error(
				new IntelligenceException(HttpStatus.FORBIDDEN.value(), "hypit_operator_required", "该操作需要部署管理账号。"));
	}

	/**
	 * 资源归属校验（C107-04 接入 hypit_project）：非本人/不存在一律 404 不泄漏存在性； 已删除工程同样 404。
	 */
	public Mono<Void> requireProjectOwner(IntelligenceCallerResolver.Caller caller, String projectId) {
		if (caller == null || caller.accountId() == null) {
			return Mono.error(unauthenticated());
		}
		UUID parsed;
		try {
			parsed = UUID.fromString(projectId);
		} catch (IllegalArgumentException error) {
			return Mono.error(notFound());
		}
		// 命中有效状态时保持值流动，否则 empty 会被 switchIfEmpty 误判为不存在。
		return projects.findOwnerStatus(caller.accountId(), parsed)
				.flatMap(status -> "deleted".equals(status) ? Mono.<String>error(notFound()) : Mono.just(status))
				.switchIfEmpty(Mono.error(notFound())).then();
	}

	public static IntelligenceException notFound() {
		return new IntelligenceException(HttpStatus.NOT_FOUND.value(), "hypit_not_found", "资源不存在。");
	}

	public static IntelligenceException unauthenticated() {
		return new IntelligenceException(HttpStatus.UNAUTHORIZED.value(), "hypit_unauthenticated", "请先登录。");
	}

	public static IntelligenceException disabled() {
		return new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(), "hypit_disabled", "Hypit 引擎未启用。");
	}

	public static IntelligenceException unavailable(String what) {
		return new IntelligenceException(HttpStatus.SERVICE_UNAVAILABLE.value(), "hypit_backend_unavailable",
				what + "尚未就绪。");
	}
}
