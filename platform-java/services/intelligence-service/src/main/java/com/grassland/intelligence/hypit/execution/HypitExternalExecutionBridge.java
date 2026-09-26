package com.grassland.intelligence.hypit.execution;

import com.grassland.intelligence.hypit.execution.HypitExecutionRepository.AcceptedExecution;
import com.grassland.intelligence.hypit.execution.HypitExecutionRepository.ExecutionRow;
import com.grassland.intelligence.hypit.execution.HypitExecutionRepository.GrantRow;
import com.grassland.intelligence.security.IntelligenceException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;

/**
 * 外部执行授权桥（任务书 #107-1 C107-07 / K12.3/K12.6/K12.8）。
 *
 * <p>
 * prepare 在一个数据库短事务内完成「校验授权 → 锁定 grant 行 → 预算 CAS → 幂等落
 * hypit_execution」，返回短时执行许可；complete/fail/cancel 只能把既有 operation
 * 推到终态并按“只补不清”落回执——重复 callback 不产生第二次结算。 未授权/预算耗尽/授权过期一律拒绝且零副作用；提交超时等 unknown
 * 保留占用， 不假退款也不自动追加请求。
 */
@Service
public class HypitExternalExecutionBridge {

	/** 许可有效期：sidecar 必须在窗口内完成 submit，过期重新 prepare（幂等）。 */
	static final Duration PERMIT_TTL = Duration.ofMinutes(5);

	private final HypitExecutionRepository executions;
	private final TransactionalOperator transactions;

	public HypitExternalExecutionBridge(HypitExecutionRepository executions, TransactionalOperator transactions) {
		this.executions = executions;
		this.transactions = transactions;
	}

	public record PrepareRequest(UUID operationId, UUID projectId, UUID jobId, UUID buildId, String needId,
			String capability, String model, String endpointId, String requestHash, UUID grantId,
			BigDecimal estimatedCost) {
	}

	public record ExecutionPermit(UUID permitId, UUID operationId, Instant expiresAt, String credentialStore,
			String credentialKey) {
	}

	public record Settlement(boolean settled, ExecutionRow execution) {
	}

	/** sha256 十六进制（scope/canonical hash 的统一口径）。 */
	public static String sha256Hex(String canonical) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (Exception error) {
			throw new IllegalStateException("SHA-256 unavailable", error);
		}
	}

	/**
	 * 一次执行许可：事务内 FOR UPDATE 锁 grant → 校验（存在/未撤销/未过期/ project 归属/预算余量）→ 幂等 INSERT
	 * hypit_execution。 同 operationId 重放且 requestHash 一致返回新许可；requestHash 变化 409。
	 * 事务由 operator 托管：回调完成即提交、错误即回滚（预算 CAS 不可能两次通过）。
	 */
	public Mono<ExecutionPermit> prepare(PrepareRequest request) {
		return transactions.execute(tx -> executions.lockGrant(request.grantId())
				.switchIfEmpty(Mono.error(conflict("hypit_not_found", "执行授权不存在")))
				.flatMap(grant -> validateGrant(grant, request).then(executions.countActiveExecutions(grant.id()))
						.flatMap(used -> {
							if (used >= grant.variantCount()) {
								return Mono.<AcceptedExecution>error(conflict("hypit_capacity_exceeded",
										"授权变体额度已用尽（" + used + "/" + grant.variantCount() + "）"));
							}
							return executions.insertExecution(request.operationId(), request.jobId(), request.buildId(),
									grant.id(), request.needId(), request.endpointId(), request.capability(),
									request.model(), request.requestHash(), request.estimatedCost(), grant.currency());
						}))
				.flatMap(accepted -> {
					if (accepted.existing() && !accepted.row().requestHash().equals(request.requestHash())) {
						return Mono.<ExecutionPermit>error(
								conflict("hypit_idempotency_conflict", "operation 已按不同 requestHash 准备"));
					}
					return Mono.just(new ExecutionPermit(UUID.randomUUID(), accepted.row().operationId(),
							Instant.now().plus(PERMIT_TTL), "file", request.endpointId() + ".apiKey"));
				})).single();
	}

	private Mono<Void> validateGrant(GrantRow grant, PrepareRequest request) {
		if (grant.revokedAt() != null) {
			return Mono.error(conflict("hypit_state_conflict", "执行授权已撤销"));
		}
		if (grant.expiresAt().isBefore(Instant.now())) {
			return Mono.error(conflict("hypit_state_conflict", "执行授权已过期"));
		}
		if (request.projectId() != null && !grant.projectId().equals(request.projectId())) {
			return Mono.error(conflict("hypit_state_conflict", "执行授权不属于该工程"));
		}
		if (request.estimatedCost() != null && grant.maxCost() != null
				&& request.estimatedCost().compareTo(grant.maxCost()) > 0) {
			return Mono.error(conflict("hypit_capacity_exceeded", "单次请求估价超出授权上限"));
		}
		return Mono.empty();
	}

	/** 成功回执：只允许既有 operation 终结；重复回执幂等（不再改 actual_cost）。 */
	public Mono<Settlement> complete(UUID operationId, String receiptJson, BigDecimal actualCost) {
		return settle(operationId, "succeeded", receiptJson, actualCost, "hypit_not_found",
				"operation 不存在；complete 不能创建未 prepare 的执行");
	}

	public Mono<Settlement> fail(UUID operationId, String receiptJson, String detail) {
		return settle(operationId, "failed", receiptJson, null, "hypit_not_found",
				"operation 不存在；fail 不能创建未 prepare 的执行");
	}

	public Mono<Settlement> cancel(UUID operationId, String detail) {
		return settle(operationId, "cancelled", null, null, "hypit_not_found",
				"operation 不存在；cancel 不能创建未 prepare 的执行");
	}

	/** unknown：submit 失联时保留占用，等待明确处理（K12.8）。 */
	public Mono<Settlement> markUnknown(UUID operationId, String receiptJson) {
		return settle(operationId, "unknown", receiptJson, null, "hypit_not_found", "operation 不存在");
	}

	private Mono<Settlement> settle(UUID operationId, String state, String receiptJson, BigDecimal actualCost,
			String missingCode, String missingMessage) {
		return executions.findExecution(operationId).switchIfEmpty(Mono.error(conflict(missingCode, missingMessage)))
				.flatMap(existing -> {
					boolean terminal = java.util.Set.of("succeeded", "failed", "cancelled").contains(existing.state());
					if (terminal && !existing.state().equals(state)) {
						return Mono.error(conflict("hypit_state_conflict",
								"operation 已终态 " + existing.state() + "，拒绝改判 " + state));
					}
					if (existing.state().equals(state)) {
						// 幂等重放：不重复结算、不改已落字段。
						return Mono.just(new Settlement(false, existing));
					}
					return executions.settleExecution(operationId, state, receiptJson, actualCost)
							.then(executions.findExecution(operationId)).map(row -> new Settlement(true, row));
				});
	}

	private static IntelligenceException conflict(String code, String message) {
		return new IntelligenceException(HttpStatus.CONFLICT.value(), code, message);
	}
}
