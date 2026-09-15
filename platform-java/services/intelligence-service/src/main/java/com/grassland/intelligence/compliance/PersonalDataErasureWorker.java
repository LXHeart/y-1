package com.grassland.intelligence.compliance;

import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 分阶段清理 worker（任务书 #103 C103-09）：推进 planned/db_cleaning manifest 的 DB 批次并
 * verify。 objects_pending 的对象物删归 C103-10，本 worker 不触碰；needs_review 只诊断不自动重试。
 */
@Component
public class PersonalDataErasureWorker {

	private static final Logger log = LoggerFactory.getLogger(PersonalDataErasureWorker.class);

	private final PersonalDataErasureService service;
	private final boolean enabled;
	private final int batchSize;
	private final AtomicBoolean running = new AtomicBoolean();

	public PersonalDataErasureWorker(PersonalDataErasureService service,
			@Value("${intelligence.erasure.enabled:true}") boolean enabled,
			@Value("${intelligence.erasure.batch-size:200}") int batchSize,
			@Value("${intelligence.erasure.claim-lease-seconds:60}") long claimLeaseSeconds,
			@Value("${intelligence.erasure.max-attempts:8}") int maxAttempts,
			// 默认须 > kind 数（40）：单次驱动才能覆盖全部资源类，大数据靠多次调用/worker 轮询收敛。
			@Value("${intelligence.erasure.batches-per-call:256}") int batchesPerCall) {
		this.service = service;
		this.enabled = enabled;
		this.batchSize = batchSize;
		service.configure(batchSize, java.time.Duration.ofSeconds(Math.max(1, claimLeaseSeconds)), maxAttempts,
				batchesPerCall);
	}

	@Scheduled(fixedDelayString = "${intelligence.erasure.poll-interval-ms:5000}")
	public void runScheduled() {
		if (!enabled || !running.compareAndSet(false, true)) {
			return;
		}
		runOnce().doOnError(error -> log.error("Personal data erasure worker failed", error))
				.onErrorResume(error -> Mono.empty()).doFinally(signal -> running.set(false)).subscribe();
	}

	Mono<Void> runOnce() {
		return service.processPending(batchSize).then();
	}
}
