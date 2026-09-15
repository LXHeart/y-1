package com.grassland.marketplace.taskcatalog;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 退出资金恢复看门狗（任务书 #103 C103-03，照 EngagementExitExpireDispatcher 范式）：
 * 有界批量（默认 50）扫描 pending/到点 retry_wait 的退出操作并推进；单条失败/未知不阻断整批
 * （service 内部已分类退避或 needs_review）。租约由数据库行守卫保证多副本不重复执行；
 * 停机接管依赖租约过期（默认 60s）后下一个 tick 自然领取。
 */
@Component
@ConditionalOnProperty(name = "marketplace.engagement.exit-funds.worker-enabled", havingValue = "true",
		matchIfMissing = true)
public class EngagementExitRecoveryWorker {

	private static final Logger log = LoggerFactory.getLogger(EngagementExitRecoveryWorker.class);

	private final EngagementExitOperationRepository operations;
	private final EngagementExitFundsService funds;
	private final Clock clock;
	private final int batchSize;

	public EngagementExitRecoveryWorker(EngagementExitOperationRepository operations,
			EngagementExitFundsService funds,
			@Value("${marketplace.engagement.exit-funds.batch-size:50}") int batchSize) {
		this(operations, funds, Clock.systemUTC(), batchSize);
	}

	EngagementExitRecoveryWorker(EngagementExitOperationRepository operations, EngagementExitFundsService funds,
			Clock clock, int batchSize) {
		this.operations = operations;
		this.funds = funds;
		this.clock = clock;
		this.batchSize = Math.max(1, Math.min(batchSize, 200));
	}

	@Scheduled(fixedDelayString = "${marketplace.engagement.exit-funds.poll-ms:5000}")
	public void tick() {
		operations.findRecoverable(clock.instant(), batchSize)
				.concatMap(op -> funds.advance(op.id(), workerId()).onErrorResume(error -> {
					log.warn("exit funds advance failed op={} err={}", op.id(), error.getMessage());
					return Mono.empty();
				}))
				.collectList()
				.block()
				.forEach(op -> log.debug("exit funds advanced op={} state={}", op.id(), op.state()));
	}

	private String workerId() {
		return "exit-funds-" + ProcessHandle.current().pid();
	}
}
