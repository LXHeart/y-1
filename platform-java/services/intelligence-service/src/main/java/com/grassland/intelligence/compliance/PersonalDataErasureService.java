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
				"organization_byok", "shared_media_and_lease",
				// #105B C105B-05：dh_invocation 经济事实脱敏保留（未决调用阻塞 verify，不伪 completed）。
				"dh_invocation_economic_facts",
				// #105G C105G-02：组织/他人素材下的 dh_asset_attachment 附件保留（K13.5 Scope）。
				"dh_asset_attachment_shared_assets");
	}

	/** plan 前置失败：账号没有「已到保留期」的可控清理任务（§6.5 过渡期防任意清理）。 */
	static final class NoRetentionTaskException extends RuntimeException {
		NoRetentionTaskException(String message) {
			super(message);
		}
	}

	private final PersonalDataErasureRepository repository;
	private final IntelligenceAccountLifecycleRepository lifecycle;
	private final PersonalDataObjectCleanup objectCleanup;
	private final TransactionalOperator transactions;
	private final com.grassland.intelligence.digitalhuman.DigitalHumanCleanupWorker dhCleanupWorker;

	int batchSize = PersonalDataErasureRepository.DEFAULT_BATCH_SIZE;
	Duration claimLease = Duration.ofSeconds(60);
	int maxAttempts = 8;
	/** 单次调用批次数上限：需 > kind 数（41）+ 大账号多批次余量；同步有界（§7.2），大数据靠多次调用/worker 收敛。 */
	int batchesPerCall = 256;

	public PersonalDataErasureService(PersonalDataErasureRepository repository,
			IntelligenceAccountLifecycleRepository lifecycle, PersonalDataObjectCleanup objectCleanup,
			TransactionalOperator transactions,
			com.grassland.intelligence.digitalhuman.DigitalHumanCleanupWorker dhCleanupWorker) {
		this.repository = repository;
		this.lifecycle = lifecycle;
		this.objectCleanup = objectCleanup;
		this.transactions = transactions;
		this.dhCleanupWorker = dhCleanupWorker;
	}

	// ---------- plan ----------

	/**
	 * 幂等建册：已建 → 回读；否则 gate（frozen/erasing 同请求）复核后 erasing + manifest + 步骤 + 对象登记。
	 */
	public Mono<PersonalDataErasureRepository.Manifest> plan(String accountId, UUID closureRequestId) {
		return transactions.transactional(lifecycle.findForUpdate(accountId)
				.switchIfEmpty(Mono.error(new NoRetentionTaskException("没有已到保留期的注销清理任务"))).flatMap(gate -> {
					if (!closureRequestId.toString().equals(gate.closureRequestId())
							|| !List.of("frozen", "erasing", "erased").contains(gate.state())) {
						return Mono.error(new NoRetentionTaskException("没有已到保留期的注销清理任务"));
					}
					return repository.findManifestByRequest(closureRequestId)
							.filter(manifest -> accountId.equals(manifest.accountId())).switchIfEmpty(Mono.defer(() -> {
								if ("erased".equals(gate.state())) {
									return Mono.error(new NoRetentionTaskException("已清理账号缺少原始清单"));
								}
								return lifecycle.markErasing(accountId, closureRequestId)
										.then(repository.insertManifest(UUID.randomUUID(), closureRequestId, accountId))
										.flatMap(manifest -> repository.insertSteps(manifest.id(), batchSize)
												.then(repository.registerObjects(manifest.id(), accountId))
												.thenReturn(manifest));
							}));
				}));
	}

	// ---------- 批次 ----------

	/**
	 * 归属冲突前置门闸（#106 D01）：任何破坏性步骤（批次 DELETE/UPDATE、对象物删、配额释放）之前核对
	 * {@link PersonalDataErasureRepository#conflictsByKind}。发现冲突则
	 * manifest=needs_review（erased=false、 verifiedAt 不新增、账号保持 erasing），返回
	 * false=无可继续推进——调用方不得把 false 当成允许物删。
	 */
	private Mono<Boolean> ownershipConflictGate(UUID manifestId, String accountId) {
		return repository.conflictsByKind(accountId).flatMap(conflicts -> {
			if (conflicts.isEmpty()) {
				return Mono.just(true);
			}
			log.warn("erasure manifest {} ownership conflicts by kind: {}", manifestId, conflicts);
			return repository.setManifestState(manifestId, "needs_review").thenReturn(false);
		});
	}

	/**
	 * 依赖序推进一个批次：领步骤租约 → 批次语句与步骤计数同事务。 返回 true=仍有后续工作（未全部完成）。 冲突未消除时返回 false
	 * 且不动任何步骤（保留幂等续跑依据；#106 D01）。
	 *
	 * <p>
	 * C105G-02：{@code dh_remote_cleanup} 步骤例外——远端/本地派生句柄推进含网络调用，K04 禁止持 DB 事务等待
	 * HTTP/provider：领取与计数回写各占独立事务，网络推进在两事务之间执行。
	 */
	public Mono<Boolean> eraseNextBatch(UUID manifestId) {
		return repository.findManifestById(manifestId)
				.switchIfEmpty(Mono.error(new IllegalArgumentException("manifest 不存在: " + manifestId))).flatMap(
						manifest -> transactions
								.transactional(lifecycle
										.findForUpdate(manifest.accountId())
										.switchIfEmpty(Mono.error(new IllegalStateException("manifest 缺少账号生命周期屏障")))
										.flatMap(gate -> ownershipConflictGate(manifestId, manifest.accountId())))
								.flatMap(clear -> clear
										? repository.findSteps(manifestId).collectList().flatMap(steps -> {
											var open = steps.stream().filter((s) -> !s.state().equals("succeeded"))
													.findFirst();
											if (open.isEmpty()) {
												return Mono.just(new ClaimOutcome(false, null, null));
											}
											var step = open.get();
											if (step.state().equals("needs_review")) {
												return Mono.just(new ClaimOutcome(false, null, null));
											}
											UUID claim = UUID.randomUUID();
											return repository
													.claimStep(manifestId, step.resourceKind(), claim, claimLease,
															maxAttempts)
													.<ClaimOutcome>flatMap(
															claimed -> claimed.state().equals("needs_review")
																	? Mono.just(new ClaimOutcome(true, null, null))
																	: PersonalDataErasureRepository.DH_REMOTE_CLEANUP_KIND
																			.equals(step.resourceKind())
																					// 网络推进延后到事务提交之后（不持锁等网络）
																					? Mono.just(new ClaimOutcome(true,
																							claimed, claim))
																					: runClaimedBatch(manifest, claimed,
																							claim)
																							.map(done -> new ClaimOutcome(
																									done, null, null)))
													.onErrorResume(error -> failClaimedStep(manifestId,
															step.resourceKind(), claim, error)
															.then(Mono.just(new ClaimOutcome(true, null, null))))
													.defaultIfEmpty(new ClaimOutcome(true, null, null));
										})
										: Mono.just(new ClaimOutcome(false, null, null)))
								.flatMap(outcome -> outcome.remoteStep() != null
										? runDhRemoteCleanupStep(manifest, outcome.remoteStep(), outcome.claim())
										: Mono.just(outcome.moreWork())));
	}

	/** 领取结果：remoteStep 非空=远端清理步骤已领、网络推进待事务外执行。 */
	private record ClaimOutcome(boolean moreWork, PersonalDataErasureRepository.Step remoteStep, UUID claim) {
	}

	/**
	 * 远端清理步骤（C105G-02）：worker 推进在锁外网络执行（清理期不新增登记——重试账本由本步骤承担； 远端/本地派生句柄 + Redis
	 * 易失键均在 dh 行删除之前）；收尾在独立事务内重核冲突后回写计数—— worker 残留非 0
	 * 或外部句柄未获远端确认时步骤失败重试，<b>远端未确认不得报 complete</b>。
	 */
	private Mono<Boolean> runDhRemoteCleanupStep(PersonalDataErasureRepository.Manifest manifest,
			PersonalDataErasureRepository.Step step, UUID claim) {
		return dhCleanupWorker.advanceForErasure(manifest.accountId())
				.flatMap(
						advance -> dhCleanupWorker.eraseVolatileKeys(manifest.accountId())
								.then(dhCleanupWorker.residue(manifest.accountId()))
								.flatMap(residue -> transactions.transactional(lifecycle
										.findForUpdate(manifest.accountId())
										.switchIfEmpty(Mono.error(new IllegalStateException("manifest 缺少账号生命周期屏障")))
										.flatMap(gate -> ownershipConflictGate(manifest.id(),
												manifest.accountId()))
										.flatMap(clear -> clear
												? (residue.clean() && advance.unconfirmedExternal() == 0
														? repository.advanceStep(manifest.id(), step.resourceKind(),
																claim, 0L, true)
														: repository.failStep(manifest.id(), step.resourceKind(), claim,
																"dh_remote_unconfirmed_" + residue.total() + "_"
																		+ advance.unconfirmedExternal(),
																Duration.ofSeconds(60)))
														.then(repository.findSteps(manifest.id()).collectList()
																.map(left -> left.stream().anyMatch(
																		(s) -> !s.state().equals("succeeded"))))
												: Mono.just(false)))));
	}

	private Mono<Boolean> runClaimedBatch(PersonalDataErasureRepository.Manifest manifest,
			PersonalDataErasureRepository.Step step, UUID claim) {
		UUID manifestId = manifest.id();
		int limit = Math.max(1, Math.min(batchSize, PersonalDataErasureRepository.MAX_BATCH_SIZE));
		Mono<Boolean> batch = repository.runBatch(step.resourceKind(), manifest.accountId(), limit).flatMap(
				affected -> repository.advanceStep(manifestId, step.resourceKind(), claim, affected, affected < limit))
				.then(repository.findSteps(manifestId).collectList()
						.map(left -> left.stream().anyMatch((s) -> !s.state().equals("succeeded"))));
		// 租约领取后重新核对；账号锁、冲突读取、业务批次和计数必须处于同一个事务。
		return transactions.transactional(lifecycle.findForUpdate(manifest.accountId())
				.switchIfEmpty(Mono.error(new IllegalStateException("manifest 缺少账号生命周期屏障")))
				.flatMap(gate -> ownershipConflictGate(manifestId, manifest.accountId()))
				.flatMap(clear -> clear ? batch : Mono.just(false)));
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
		return transactions.transactional(repository.findManifestById(manifestId)
				.switchIfEmpty(Mono.error(new IllegalArgumentException("manifest 不存在: " + manifestId)))
				.flatMap(manifest -> lifecycle.findForUpdate(manifest.accountId())
						.switchIfEmpty(Mono.error(new IllegalStateException("manifest 缺少账号生命周期屏障")))
						.then(verifyInTransaction(manifestId))));
	}

	private Mono<ErasureReceipt> verifyInTransaction(UUID manifestId) {
		return repository.findManifestById(manifestId)
				.switchIfEmpty(Mono.error(new IllegalArgumentException("manifest 不存在: " + manifestId)))
				.flatMap(manifest -> {
					// needs_review 保留不可被 allSucceeded/residue=0 覆盖（#106 D01）：先复核冲突是否仍存在；
					// 已消除则继续常规核对，交由显式重试沿原步骤幂等推进。
					if (!"needs_review".equals(manifest.state())) {
						return verifyInventory(manifest, manifestId);
					}
					return repository.conflictsByKind(manifest.accountId())
							.flatMap(conflicts -> conflicts.isEmpty()
									? verifyInventory(manifest, manifestId)
									: receiptOf(manifest, "needs_review", false, 0L, 0L));
				});
	}

	private Mono<ErasureReceipt> verifyInventory(PersonalDataErasureRepository.Manifest manifest, UUID manifestId) {
		return repository.findSteps(manifestId).collectList().flatMap(steps -> {
			boolean completeInventory = steps.size() == PersonalDataErasureRepository.KINDS.size() && steps.stream()
					.map(PersonalDataErasureRepository.Step::resourceKind).collect(java.util.stream.Collectors.toSet())
					.containsAll(PersonalDataErasureRepository.KINDS.stream()
							.map(PersonalDataErasureRepository.EraseKind::kind).toList());
			if (!completeInventory) {
				return repository.setManifestState(manifestId, "needs_review")
						.then(receiptOf(manifest, "needs_review", false, 0L, 1L));
			}
			boolean allSucceeded = steps.stream().allMatch((s) -> s.state().equals("succeeded"));
			if (!allSucceeded) {
				long failed = steps.stream().filter(s -> List.of("retry_wait", "needs_review").contains(s.state()))
						.count();
				String state = steps.stream().anyMatch(s -> "needs_review".equals(s.state()))
						? "needs_review"
						: "db_cleaning";
				return repository.setManifestState(manifestId, state)
						.then(repository.countObjects(manifestId, "pending", "failed")
								.flatMap(pending -> receiptOf(manifest, state, false, pending, failed)));
			}
			return repository.residueByKind(manifest.accountId()).flatMap(residue -> {
				var left = residue.entrySet().stream().filter((e) -> e.getValue() > 0).findFirst().orElse(null);
				if (left != null) {
					log.warn("erasure manifest {} residue in {}: {}", manifestId, left.getKey(), left.getValue());
					return repository.setManifestState(manifestId, "needs_review")
							.then(receiptOf(manifest, "needs_review", false, 0L, 0L));
				}
				// 归属冲突（多父链个人账号不一致 / 未知 studio_apply kind）：行保留且不得 verified（#104 D01）。
				return repository.conflictsByKind(manifest.accountId()).flatMap(conflicts -> {
					if (!conflicts.isEmpty()) {
						log.warn("erasure manifest {} ownership conflicts by kind: {}", manifestId, conflicts);
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
										.then(lifecycle.markErased(manifest.accountId(), manifest.closureRequestId()))
										.then(repository.findManifestById(manifestId))
										.flatMap(done -> receiptOf(done, "completed", true, 0L, 0L));
							}
							return repository.setManifestState(manifestId, "objects_pending")
									.then(receiptOf(manifest, "objects_pending", false, pendingObjects, 0L));
						});
					});
				});
			});
		});
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

	/**
	 * 对象推进统一经冲突门闸（#106 D01）：drain 后 manifest=needs_review（批次门闸拦截）时不物删、
	 * 直接核对回执；否则物删一轮 + verify。
	 */
	private Mono<ErasureReceipt> advanceObjectsThenVerify(UUID manifestId) {
		return repository.findManifestById(manifestId)
				.switchIfEmpty(Mono.error(new IllegalArgumentException("manifest 不存在: " + manifestId)))
				.flatMap(manifest -> "needs_review".equals(manifest.state())
						? verify(manifestId)
						: objectCleanup.advance(manifestId).then(verify(manifestId)));
	}

	/**
	 * 端点同步路径（§7.2 同步有界）：DB 批次 + 一轮对象物删 + verify；仍有 pending 对象（存储未装配/故障/超批量） 时如实回执
	 * objects_pending，由 worker 轮询或下次调用收敛。
	 */
	public Mono<ErasureReceipt> process(UUID manifestId) {
		return drain(manifestId).then(advanceObjectsThenVerify(manifestId))
				.flatMap(receipt -> "objects_pending".equals(receipt.state())
						? objectCleanup.advance(manifestId).then(verify(manifestId))
						: Mono.just(receipt));
	}

	/**
	 * worker：planned/db_cleaning/objects_pending 清单逐 manifest 推进（DB 批次 + 对象物删）+
	 * verify。
	 */
	public Flux<ErasureReceipt> processPending(int limit) {
		return repository.findActiveManifests(limit)
				.concatMap(manifest -> drain(manifest.id()).then(advanceObjectsThenVerify(manifest.id())));
	}

	void configure(int batchSize, Duration claimLease, int maxAttempts, int batchesPerCall) {
		this.batchSize = Math.max(1, Math.min(batchSize, PersonalDataErasureRepository.MAX_BATCH_SIZE));
		this.claimLease = claimLease;
		this.maxAttempts = Math.max(1, maxAttempts);
		this.batchesPerCall = Math.max(1, batchesPerCall);
	}
}
