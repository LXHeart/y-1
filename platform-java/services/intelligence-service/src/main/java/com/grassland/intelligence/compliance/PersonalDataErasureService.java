package com.grassland.intelligence.compliance;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 分阶段清理编排（任务书 #103 C103-09 / §4.3 / §6.5）。
 *
 * <p>
 * plan：gate 复核后落 manifest（同 closureRequestId 幂等）——先登记对象 key 与全部资源步骤，不把正文复制进
 * manifest。 drain：依赖序逐步骤批次推进，批次 DELETE/脱敏与步骤计数同事务提交，批次重跑幂等（重复批次删 0 行）。verify：逐
 * kind 残留核对 + pendingObjects；仅全部清理确认后 completed（erased=true），对象未清 →
 * objects_pending，残留/失败 → needs_review。Identity 只认 verified completed；对象物删归
 * C103-10，本卡不提前完成。
 */
@Component
public class PersonalDataErasureService {

	private static final Logger log = LoggerFactory.getLogger(PersonalDataErasureService.class);

	/** 清理回执（§6.5 erase 响应数据；erased=true 仅 state=completed）。 */
	public record ErasureReceipt(String manifestId, String state, boolean erased, Map<String, Long> counts,
			long pendingObjects, long failedSteps, String verifiedAt, List<String> retained) {

		static final List<String> RETAINED = List.of("ai_cost_runs", "billing_compensations", "organization_content",
				"organization_byok", "shared_media_and_lease");
	}

	/** plan 前置失败：账号没有「已到保留期」的可控清理任务（§6.5 过渡期防任意清理）。 */
	static final class NoRetentionTaskException extends RuntimeException {
		NoRetentionTaskException(String message) {
			super(message);
		}
	}

	private final PersonalDataErasureRepository repository;
	private final IntelligenceAccountLifecycleRepository lifecycle;
	private final TransactionalOperator transactions;

	int batchSize = PersonalDataErasureRepository.DEFAULT_BATCH_SIZE;
	Duration claimLease = Duration.ofSeconds(60);
	int maxAttempts = 8;
	/** 单次调用批次数上限：需 > kind 数（41）+ 大账号多批次余量；同步有界（§7.2），大数据靠多次调用/worker 收敛。 */
	int batchesPerCall = 256;

	public PersonalDataErasureService(PersonalDataErasureRepository repository,
			IntelligenceAccountLifecycleRepository lifecycle, TransactionalOperator transactions) {
		this.repository = repository;
		this.lifecycle = lifecycle;
		this.transactions = transactions;
	}

	// ---------- plan ----------

	/**
	 * 幂等建册：已建 → 回读；否则 gate（frozen/erasing 同请求）复核后 erasing + manifest + 步骤 + 对象登记。
	 */
	public Mono<PersonalDataErasureRepository.Manifest> plan(String accountId, UUID closureRequestId) {
		return repository.findManifestByRequest(closureRequestId)
				.switchIfEmpty(Mono.defer(() -> lifecycle.find(accountId)
						.switchIfEmpty(Mono.error(new NoRetentionTaskException("没有已到保留期的注销清理任务"))).flatMap(gate -> {
							boolean knownRequest = closureRequestId.toString().equals(gate.closureRequestId());
							if (!knownRequest || !(gate.state().equals("frozen") || gate.state().equals("erasing"))) {
								return Mono.error(new NoRetentionTaskException("没有已到保留期的注销清理任务"));
							}
							return lifecycle.markErasing(accountId, closureRequestId)
									.then(repository.insertManifest(UUID.randomUUID(), closureRequestId, accountId))
									.flatMap(manifest -> repository.insertSteps(manifest.id(), batchSize)
											.then(repository.registerObjects(manifest.id(), accountId))
											.thenReturn(manifest));
						})));
	}

	// ---------- 批次 ----------

	/**
	 * 依赖序推进一个批次：领步骤租约 → 批次语句与步骤计数同事务。 返回 true=仍有后续工作（未全部完成）。
	 */
	public Mono<Boolean> eraseNextBatch(UUID manifestId) {
		return repository.findManifestById(manifestId)
				.switchIfEmpty(Mono.error(new IllegalArgumentException("manifest 不存在: " + manifestId)))
				.flatMap(manifest -> repository.findSteps(manifestId).collectList().flatMap(steps -> {
					var open = steps.stream().filter((s) -> !s.state().equals("succeeded")).findFirst();
					if (open.isEmpty()) {
						return Mono.just(false);
					}
					var step = open.get();
					if (step.state().equals("needs_review")) {
						return Mono.just(false);
					}
					UUID claim = UUID.randomUUID();
					return repository.claimStep(manifestId, step.resourceKind(), claim, claimLease, maxAttempts)
							.flatMap(claimed -> claimed.state().equals("needs_review")
									? Mono.just(true)
									: runClaimedBatch(manifest, claimed, claim))
							.onErrorResume(error -> failClaimedStep(manifestId, step.resourceKind(), claim, error)
									.then(Mono.just(true)))
							.defaultIfEmpty(true);
				})).defaultIfEmpty(false);
	}

	private Mono<Boolean> runClaimedBatch(PersonalDataErasureRepository.Manifest manifest,
			PersonalDataErasureRepository.Step step, UUID claim) {
		UUID manifestId = manifest.id();
		int limit = Math.max(1, Math.min(batchSize, PersonalDataErasureRepository.MAX_BATCH_SIZE));
		Mono<Boolean> batch = repository.runBatch(step.resourceKind(), manifest.accountId(), limit).flatMap(
				affected -> repository.advanceStep(manifestId, step.resourceKind(), claim, affected, affected < limit))
				.then(repository.findSteps(manifestId).collectList()
						.map(left -> left.stream().anyMatch((s) -> !s.state().equals("succeeded"))));
		return transactions.transactional(batch);
	}

	private Mono<Void> failClaimedStep(UUID manifestId, String resourceKind, UUID claim, Throwable error) {
		String code = error.getClass().getSimpleName();
		log.warn("erasure batch failed: manifest={} kind={} error={}", manifestId, resourceKind, code);
		return repository.failStep(manifestId, resourceKind, claim, code, Duration.ofSeconds(60));
	}

	/** 有界驱动：单次调用最多 batchesPerCall 个批次（同步尝试≤5s 的持久化等价物；worker/端点共用）。 */
	public Mono<Boolean> drain(UUID manifestId) {
		return drain(manifestId, batchesPerCall);
	}

	private Mono<Boolean> drain(UUID manifestId, int remaining) {
		if (remaining <= 0) {
			return Mono.just(true);
		}
		return eraseNextBatch(manifestId).flatMap(more -> more ? drain(manifestId, remaining - 1) : Mono.just(false));
	}

	// ---------- verify ----------

	public Mono<ErasureReceipt> verify(UUID manifestId) {
		return repository.findManifestById(manifestId)
				.switchIfEmpty(Mono.error(new IllegalArgumentException("manifest 不存在: " + manifestId)))
				.flatMap(manifest -> repository.findSteps(manifestId).collectList().flatMap(steps -> {
					boolean allSucceeded = steps.stream().allMatch((s) -> s.state().equals("succeeded"));
					if (!allSucceeded) {
						return receiptOf(manifest, manifest.state(), false, 0L, 0L);
					}
					return repository.residueByKind(manifest.accountId()).flatMap(residue -> {
						var left = residue.entrySet().stream().filter((e) -> e.getValue() > 0).findFirst().orElse(null);
						if (left != null) {
							log.warn("erasure manifest {} residue in {}: {}", manifestId, left.getKey(),
									left.getValue());
							return repository.setManifestState(manifestId, "needs_review")
									.then(receiptOf(manifest, "needs_review", false, 0L, 0L));
						}
						return repository.countFailedSteps(manifestId).flatMap(failedSteps -> {
							if (failedSteps > 0) {
								return repository.setManifestState(manifestId, "needs_review")
										.then(receiptOf(manifest, "needs_review", false, 0L, failedSteps));
							}
							return repository.countObjects(manifestId, "pending", "failed").flatMap(pendingObjects -> {
								if (pendingObjects == 0) {
									return repository.setManifestState(manifestId, "completed")
											.then(lifecycle.markErased(manifest.accountId(),
													manifest.closureRequestId()))
											.then(repository.findManifestById(manifestId))
											.flatMap(done -> receiptOf(done, "completed", true, 0L, 0L));
								}
								return repository.setManifestState(manifestId, "objects_pending")
										.then(receiptOf(manifest, "objects_pending", false, pendingObjects, 0L));
							});
						});
					});
				}));
	}

	private Mono<ErasureReceipt> receiptOf(PersonalDataErasureRepository.Manifest manifest, String state,
			boolean erased, long pendingObjects, long failedSteps) {
		return repository.findSteps(manifest.id())
				.collectMap(PersonalDataErasureRepository.Step::resourceKind,
						PersonalDataErasureRepository.Step::deletedCount)
				.map(counts -> new ErasureReceipt(manifest.id().toString(), state, erased, new LinkedHashMap<>(counts),
						pendingObjects, failedSteps,
						manifest.verifiedAt() == null ? null : manifest.verifiedAt().toString(),
						ErasureReceipt.RETAINED));
	}

	// ---------- worker 入口 ----------

	/** worker：planned/db_cleaning 清单逐 manifest 有界推进 + verify。 */
	public Flux<ErasureReceipt> processPending(int limit) {
		return repository.findActiveManifests(limit)
				.concatMap(manifest -> drain(manifest.id()).then(verify(manifest.id())));
	}

	void configure(int batchSize, Duration claimLease, int maxAttempts, int batchesPerCall) {
		this.batchSize = Math.max(1, Math.min(batchSize, PersonalDataErasureRepository.MAX_BATCH_SIZE));
		this.claimLease = claimLease;
		this.maxAttempts = Math.max(1, maxAttempts);
		this.batchesPerCall = Math.max(1, batchesPerCall);
	}
}
