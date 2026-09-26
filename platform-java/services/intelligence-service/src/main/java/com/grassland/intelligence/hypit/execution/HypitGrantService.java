package com.grassland.intelligence.hypit.execution;

import com.grassland.intelligence.hypit.execution.HypitExecutionRepository.GrantRow;
import com.grassland.intelligence.hypit.security.HypitAccessService;
import com.grassland.intelligence.hypit.project.HypitProjectRepository;
import com.grassland.intelligence.security.IntelligenceException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * 执行授权服务（任务书 #107-1 C107-07 / §6.2 POST execution-grants）。
 *
 * <p>
 * 授权绑定 owner/revision-or-即时 scope：scopeJson 按 canonical 顺序序列化后取
 * sha256（scope_hash），重复创建同 scope 返回新行（授权可以多次发放）；缺价格且未显式 allowUnknownCost
 * 的请求拒绝（K12.7：缺价不可默认无限）。
 */
@Service
public class HypitGrantService {

	private final HypitExecutionRepository executions;
	private final HypitProjectRepository projects;

	public HypitGrantService(HypitExecutionRepository executions, HypitProjectRepository projects) {
		this.executions = executions;
		this.projects = projects;
	}

	public record GrantRequest(UUID requestId, UUID projectId, UUID planId, UUID pricingId, Object scope,
			BigDecimal maxCost, String currency, boolean allowUnknownCost, int variantCount, Instant expiresAt) {
	}

	public Mono<GrantRow> createGrant(String accountId, GrantRequest request) {
		if (request.scope() == null) {
			return Mono.error(new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input",
					"scope 必填（授权范围不是可选装饰）"));
		}
		if (request.variantCount() < 1) {
			return Mono.error(new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input",
					"variantCount 必须为正整数"));
		}
		if (request.expiresAt() == null || request.expiresAt().isBefore(Instant.now())) {
			return Mono.error(new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input",
					"expiresAt 必须是将来的时间"));
		}
		if (request.maxCost() == null && !request.allowUnknownCost()) {
			return Mono.error(new IntelligenceException(HttpStatus.BAD_REQUEST.value(), "hypit_invalid_input",
					"缺 maxCost 的授权必须显式 allowUnknownCost=true"));
		}
		String currency = request.currency() == null || request.currency().isBlank() ? "USD" : request.currency();
		String scopeJson = canonicalJson(request.scope());
		String scopeHash = HypitExternalExecutionBridge.sha256Hex(scopeJson);
		return projects.findOwnerStatus(accountId, request.projectId())
				.switchIfEmpty(Mono.error(HypitAccessService.notFound()))
				.flatMap(status -> executions.insertGrant(UUID.randomUUID(), request.projectId(), accountId,
						request.planId(), request.pricingId(), scopeHash, scopeJson, request.maxCost(), currency,
						request.allowUnknownCost(), request.variantCount(), request.expiresAt()));
	}

	public Mono<Long> revoke(String accountId, UUID grantId) {
		return executions.findGrant(grantId).switchIfEmpty(Mono.error(HypitAccessService.notFound())).flatMap(grant -> {
			if (!grant.accountId().equals(accountId)) {
				return Mono.error(HypitAccessService.notFound());
			}
			return executions.revokeGrant(grantId);
		});
	}

	/** 稳定 scope 序列化（key 排序 + 紧凑分隔），同 scope 永远同 hash。 */
	static String canonicalJson(Object scope) {
		StringBuilder out = new StringBuilder();
		appendCanonical(scope, out);
		return out.toString();
	}

	@SuppressWarnings("unchecked")
	private static void appendCanonical(Object value, StringBuilder out) {
		if (value == null) {
			out.append("null");
		} else if (value instanceof String text) {
			out.append('"').append(text.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
		} else if (value instanceof Number || value instanceof Boolean) {
			out.append(value);
		} else if (value instanceof java.util.Map<?, ?> map) {
			out.append('{');
			java.util.List<String> keys = new java.util.ArrayList<>();
			for (Object key : map.keySet()) {
				keys.add(String.valueOf(key));
			}
			java.util.Collections.sort(keys);
			for (int index = 0; index < keys.size(); index++) {
				if (index > 0) {
					out.append(',');
				}
				appendCanonical(keys.get(index), out);
				out.append(':');
				appendCanonical(map.get(keys.get(index)), out);
			}
			out.append('}');
		} else if (value instanceof Iterable<?> items) {
			out.append('[');
			boolean first = true;
			for (Object item : items) {
				if (!first) {
					out.append(',');
				}
				first = false;
				appendCanonical(item, out);
			}
			out.append(']');
		} else {
			throw new IllegalArgumentException("scope 包含不可序列化的类型: " + value.getClass().getName());
		}
	}
}
