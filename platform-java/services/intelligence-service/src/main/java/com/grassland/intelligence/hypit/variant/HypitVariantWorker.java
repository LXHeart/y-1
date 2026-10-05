package com.grassland.intelligence.hypit.variant;

import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitBuildService;
import com.grassland.intelligence.hypit.build.HypitResultService;
import com.grassland.intelligence.hypit.variant.HypitVariantRepository.VariantRow;
import java.time.Duration;
import java.util.UUID;
import com.grassland.intelligence.hypit.variant.HypitVariantRepository.ObservationClaim;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 变体收敛 worker（任务书 #107-3 C107-19 / 步骤 6/7/8；107-fix-2 C107F2-26 收紧终态映射）： 把
 * running/queued 变体的 Build 观察收敛到 hypit_variant 终态（部分失败保留成功项——不重跑、 不级联取消）。租约语义沿用
 * K06.1：认领即置 running，崩溃恢复由下次轮询统一收敛。
 *
 * <p>
 * C26-E01（RULE-12）：只有 Build finished（lifecycle=succeeded）+ outcome=complete +
 * 结果就绪（syncOutputs 归档出非空 outputs）才 variant=succeeded；仅 execution_decided
 * 等中间态继续观察，绝不提前成功。failed/cancelled 按真实终态映射。
 */
@Component
public class HypitVariantWorker {

	private static final Logger logger = LoggerFactory.getLogger(HypitVariantWorker.class);
	private static final Duration BUILD_TIMEOUT = Duration.ofSeconds(30);

	private final HypitVariantRepository variants;
	private final HypitBuildService builds;
	private final HypitBuildRepository buildRepo;
	private final HypitResultService results;
	private final AtomicBoolean running = new AtomicBoolean();

	public HypitVariantWorker(HypitVariantRepository variants, HypitBuildService builds, HypitBuildRepository buildRepo,
			HypitResultService results) {
		this.variants = variants;
		this.builds = builds;
		this.buildRepo = buildRepo;
		this.results = results;
	}

	@Scheduled(fixedDelayString = "${hypit.variant-worker.poll-ms:5000}", initialDelayString = "${hypit.variant-worker.poll-ms:5000}")
	public void runScheduled() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		runOnce().doOnError(error -> logger.warn("hypit variant worker cycle failed", error))
				.onErrorResume(error -> Flux.empty()).doFinally(signal -> running.set(false)).subscribe();
	}

	public Flux<VariantRow> runOnce() {
		// Claim each row only when ready to process it; queued work cannot outlive its
		// lease.
		return Flux.range(0, 50)
				.concatMap(ignored -> variants.claimObservation(UUID.randomUUID()).flatMap(this::converge));
	}

	private Mono<VariantRow> converge(ObservationClaim claim) {
		var row = claim.variant();
		return buildRepo.findById(row.buildId())
				.switchIfEmpty(Mono.error(new IllegalStateException("variant_build_missing"))).flatMap(builds::converge)
				.flatMap(build -> {
					if ("finished".equals(build.lifecycle())) {
						if ("cancelled".equals(build.outcome()) || "failed".equals(build.outcome())) {
							return Mono.just(build.outcome());
						}
						if (!"complete".equals(build.outcome())) {
							return Mono.error(new IllegalStateException("variant_build_outcome_unknown"));
						}
						return results.syncOutputs(build).map(outputs -> outputs.isEmpty() ? "" : "succeeded")
								.defaultIfEmpty("");
					}
					if ("submission_incomplete".equals(build.lifecycle())) {
						return Mono.just("cancelled".equals(build.outcome()) ? "cancelled" : "failed");
					}
					return Mono.just("");
				}).timeout(BUILD_TIMEOUT)
				.flatMap(state -> variants.finishObservation(claim, state.isEmpty() ? null : state, null))
				.onErrorResume(error -> {
					logger.warn("hypit variant observation deferred variant={} build={} reason={}", row.id(),
							row.buildId(), error.getClass().getSimpleName());
					return variants.finishObservation(claim, null, error.getClass().getSimpleName());
				});
	}
}
