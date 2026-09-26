package com.grassland.intelligence.hypit.variant;

import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitBuildService;
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
 * 变体收敛 worker（任务书 #107-3 C107-19 / 步骤 6/7/8）：把 running/queued 变体 的 Build 观察收敛到
 * hypit_variant 终态（部分失败保留成功项——不重跑、不级联 取消）。租约语义沿用 K06.1：认领即置
 * running，崩溃恢复由下次轮询统一收敛。
 */
@Component
public class HypitVariantWorker {

	private static final Logger logger = LoggerFactory.getLogger(HypitVariantWorker.class);
	private static final Duration BUILD_TIMEOUT = Duration.ofSeconds(30);

	private final HypitVariantRepository variants;
	private final HypitBuildService builds;
	private final HypitBuildRepository buildRepo;
	private final AtomicBoolean running = new AtomicBoolean();

	public HypitVariantWorker(HypitVariantRepository variants, HypitBuildService builds,
			HypitBuildRepository buildRepo) {
		this.variants = variants;
		this.builds = builds;
		this.buildRepo = buildRepo;
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

	/** Build 终态 → 变体终态（succeeded/failed/cancelled）；非终态保持观察。 */
	private Mono<VariantRow> converge(VariantRow row) {
		if (row.buildId() == null) {
			return Mono.just(row);
		}
		return buildRepo.findById(row.buildId()).flatMap(build -> builds.converge(build))
				.flatMap(build -> switch (build.lifecycle()) {
					case "succeeded" -> variants.markTerminal(row.id(), "succeeded");
					case "failed" -> variants.markTerminal(row.id(), "failed");
					case "cancelled" -> variants.markTerminal(row.id(), "cancelled");
					default -> Mono.just(row);
				}).timeout(BUILD_TIMEOUT).onErrorResume(error -> Mono.just(row));
	}
}
