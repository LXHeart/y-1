package com.grassland.intelligence.compliance;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class IntelligenceComplianceController {

	private final IntelligenceCallerResolver callers;
	private final IntelligenceJobInventory inventory;
	private final IntelligenceAccountLifecycleRepository lifecycle;
	private final PersonalDataErasureService erasure;

	public IntelligenceComplianceController(IntelligenceCallerResolver callers, IntelligenceJobInventory inventory,
			IntelligenceAccountLifecycleRepository lifecycle, PersonalDataErasureService erasure) {
		this.callers = callers;
		this.inventory = inventory;
		this.lifecycle = lifecycle;
		this.erasure = erasure;
	}

	/**
	 * 活动任务检查（任务书 #103 C103-08 / §6.5）：按 kind 输出 blockers（排队/未知/费用未结全计入， 不能只数
	 * running）；兼容保留 RUNNING_AI_JOB 汇总条目。只读——不自动取消任务。
	 */
	@GetMapping("/internal/compliance/accounts/{accountId}/closure-check")
	public Mono<ResponseEntity<Map<String, Object>>> closureCheck(@PathVariable String accountId,
			ServerHttpRequest request) {
		return callers.requireServicePrincipal(request, IntelligenceCallerResolver.IDENTITY_SERVICE)
				.then(inventory.countByKind(accountId)).map(counts -> {
					List<Map<String, Object>> blockers = new ArrayList<>();
					counts.forEach((kind, count) -> blockers.add(blocker(
							"ACTIVE_JOB_" + kind.toUpperCase(java.util.Locale.ROOT), "存在未完成或未核销的创作任务：" + kind, count)));
					long total = counts.values().stream().mapToLong(Long::longValue).sum();
					if (total > 0) {
						blockers.add(blocker("RUNNING_AI_JOB", "仍有执行中或待补偿的 AI 任务", total));
					}
					return ResponseEntity.ok(success(Map.of("blockers", blockers)));
				});
	}

	/**
	 * 注销准备屏障（§6.5 prepare）：gate 行锁内复查活动任务后冻结；有活动任务 → 409 blockers（不冻结）； 同请求幂等回同
	 * revision；不同仍活动请求 → 409。V85 触发器自此拒绝该账号受保护表的新建。
	 */
	@PostMapping("/internal/compliance/accounts/{accountId}/prepare")
	public Mono<ResponseEntity<Map<String, Object>>> prepare(@PathVariable String accountId,
			@RequestBody PrepareRequest body, ServerHttpRequest request) {
		if (body == null || body.closureRequestId() == null || body.closureRequestId().isBlank()) {
			return Mono
					.just(ResponseEntity.badRequest().body(Map.of("success", false, "error", "closureRequestId 必填")));
		}
		java.util.UUID requestId;
		try {
			requestId = java.util.UUID.fromString(body.closureRequestId().trim());
		} catch (IllegalArgumentException e) {
			return Mono
					.just(ResponseEntity.badRequest().body(Map.of("success", false, "error", "closureRequestId 非法")));
		}
		return callers.requireServicePrincipal(request, IntelligenceCallerResolver.IDENTITY_SERVICE)
				.then(lifecycle.prepare(accountId, requestId))
				.map(frozen -> ResponseEntity.ok(success(Map.of("prepared", true, "closureRequestId",
						body.closureRequestId(), "revision", frozen.revision()))))
				.onErrorResume(IllegalStateException.class, error -> {
					java.util.Optional<String> kinds = IntelligenceAccountLifecycleRepository.activeKindError(error);
					if (kinds.isPresent()) {
						return Mono.just(ResponseEntity.status(409).body(
								Map.of("success", false, "data", Map.of("prepared", false, "blockers", kinds.get()))));
					}
					return Mono.error(error);
				}).switchIfEmpty(Mono.just(ResponseEntity.status(409)
						.body(Map.of("success", false, "data", Map.of("prepared", false, "blockers", "另一注销请求进行中")))));
	}

	/** 取消尚未进入清理态的同请求冻结（§6.5 release；Identity 证明未软删后调用）。 */
	@PostMapping("/internal/compliance/accounts/{accountId}/release")
	public Mono<ResponseEntity<Map<String, Object>>> release(@PathVariable String accountId,
			@RequestBody PrepareRequest body, ServerHttpRequest request) {
		if (body == null || body.closureRequestId() == null || body.closureRequestId().isBlank()) {
			return Mono
					.just(ResponseEntity.badRequest().body(Map.of("success", false, "error", "closureRequestId 必填")));
		}
		return callers.requireServicePrincipal(request, IntelligenceCallerResolver.IDENTITY_SERVICE)
				.then(lifecycle.release(accountId, java.util.UUID.fromString(body.closureRequestId().trim())))
				.map(released -> ResponseEntity.ok(success(Map.of("released", released))));
	}

	record PrepareRequest(String closureRequestId) {
	}

	/**
	 * 分阶段清理（任务书 #103 C103-09 / §6.5）：请求增可选 {closureRequestId}（缺省时从受控 gate 行推导，
	 * 不允许任意立即清理未到保留期账号）。响应兼容 erased/counts/retained 并新增
	 * state/manifestId/pendingObjects/failedSteps/verifiedAt；erased=true 仅
	 * state=completed—— 处理中回 erased=false，Identity 必须保持 processing/retry、不写
	 * pii_erased。
	 */
	@PostMapping("/internal/compliance/accounts/{accountId}/erase")
	public Mono<ResponseEntity<Map<String, Object>>> erase(@PathVariable String accountId,
			@RequestBody(required = false) EraseRequest body, ServerHttpRequest request) {
		return callers.requireServicePrincipal(request, IntelligenceCallerResolver.IDENTITY_SERVICE)
				.then(Mono.defer(() -> resolveClosureRequestId(accountId, body)))
				.flatMap(closureRequestId -> erasure.plan(accountId, closureRequestId)
						.flatMap(manifest -> erasure.drain(manifest.id()).then(erasure.verify(manifest.id()))))
				.map(receipt -> ResponseEntity.ok(success(Map.of("erased", receipt.erased(), "counts", receipt.counts(),
						"retained", receipt.retained(), "state", receipt.state(), "manifestId", receipt.manifestId(),
						"pendingObjects", receipt.pendingObjects(), "failedSteps", receipt.failedSteps(), "verifiedAt",
						receipt.verifiedAt() == null ? "" : receipt.verifiedAt()))))
				.onErrorResume(PersonalDataErasureService.NoRetentionTaskException.class, error -> Mono
						.just(ResponseEntity.status(409).body(Map.of("success", false, "error", error.getMessage()))));
	}

	/** 请求未带 closureRequestId 时从 gate 行推导（过渡期旧客户端；无受控任务 → 拒绝）。 */
	private Mono<java.util.UUID> resolveClosureRequestId(String accountId, EraseRequest body) {
		if (body != null && body.closureRequestId() != null && !body.closureRequestId().isBlank()) {
			return Mono.just(java.util.UUID.fromString(body.closureRequestId().trim()));
		}
		return lifecycle.find(accountId)
				.filter(gate -> gate.closureRequestId() != null
						&& (gate.state().equals("frozen") || gate.state().equals("erasing")))
				.map(gate -> java.util.UUID.fromString(gate.closureRequestId()))
				.switchIfEmpty(Mono.error(new PersonalDataErasureService.NoRetentionTaskException("没有已到保留期的注销清理任务")));
	}

	record EraseRequest(String requester, String closureRequestId) {
	}

	private static Map<String, Object> blocker(String code, String message, long count) {
		Map<String, Object> blocker = new LinkedHashMap<>();
		blocker.put("domain", "intelligence");
		blocker.put("code", code);
		blocker.put("message", message);
		blocker.put("count", count);
		return blocker;
	}

	private static Map<String, Object> success(Map<String, Object> data) {
		return Map.of("success", true, "data", data);
	}
}
