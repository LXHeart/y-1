package com.grassland.intelligence.hypit.variant;

import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitBuildService;
import com.grassland.intelligence.hypit.build.HypitResultService;
import com.grassland.intelligence.hypit.variant.HypitVariantRepository.VariantRow;
import java.time.Duration;
import java.util.List;
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

	@Scheduled(fixedDelayString = "${hypit.variant-worker.poll-ms:5000}")
	public void runScheduled() {
		if (!running.compareAndSet(false, true)) {
			return;
		}
		runOnce().doOnError(error -> logger.warn("hypit variant worker cycle failed", error))
				.onErrorResume(error -> Flux.empty()).doFinally(signal -> running.set(false)).subscribe();
	}

	public Flux<VariantRow> runOnce() {
		return variantsPending().flatMap(this::converge, 4);
	}

	private Flux<VariantRow> variantsPending() {
		// 直接扫 running/queued 且已绑 Build 的变体行（数量有界：每轮最多 50 行）。
		return variants.listActive(50);
	}

	/** Build 终态 → 变体终态；succeeded 三条件齐才落（§6.12 显式映射）。 */
	private Mono<VariantRow> converge(VariantRow row) {
		if (row.buildId() == null) {
			return Mono.just(row);
		}
		return buildRepo.findById(row.buildId()).flatMap(build -> builds.converge(build))
				.flatMap(build -> switch (build.lifecycle()) {
					// finished + complete + 结果就绪 → succeeded；否则按真实终态映射
					// （outcome=cancelled → cancelled，其余失败态 → failed）。
					case "finished" -> {
						if ("cancelled".equals(build.outcome())) {
							yield variants.markTerminal(row.id(), "cancelled");
						}
						yield resultReady(build).flatMap(
								ready -> ready ? variants.markTerminal(row.id(), "succeeded") : Mono.just(row));
					}
					// 提交不完整：outcome=cancelled → cancelled（就地取消路径），否则 failed。
					case "submission_incomplete" ->
						variants.markTerminal(row.id(), "cancelled".equals(build.outcome()) ? "cancelled" : "failed");
					// submitting/active/execution_decided/result_pending：中间态继续观察。
					default -> Mono.just(row);
				}).timeout(BUILD_TIMEOUT).onErrorResume(error -> Mono.just(row));
	}

	/** 结果就绪 = outcome=complete 且结果归档非空（syncOutputs 落地 outputs）。 */
	private Mono<Boolean> resultReady(HypitBuildRepository.BuildRow build) {
		if (!"complete".equals(build.outcome())) {
			return Mono.just(false);
		}
		return results.syncOutputs(build).map(outputs -> outputs != null && !outputs.isEmpty()).onErrorReturn(false);
	}
}
