package com.grassland.intelligence.ai.controlplane;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 任务书 #103 C103-20：受信端点白名单跨副本有界刷新。
 *
 * <p>
 * 每 {@code refresh-interval-ms}（默认 5s，最小 1s）定时全量小表刷新——多副本共享 PG 时， A 副本撤销
 * origin、B 副本无本地写事件，也能在 ≤maxAge（默认 30s）内收紧。写后事件
 * （TrustedOriginsChangedEvent）仍即时刷新。刷新在 boundedElastic 上执行，不在 WebFlux 事件线程
 * block；SmartLifecycle 停止时释放定时器（isRunning 翻 false 后 @Scheduled 容器停摆，
 * 进程关闭不再占用）。配置非法（间隔 <1s 或 maxAge < 刷新间隔）启动即失败——不把 maxAge 放成无限。
 */
@Component
@ConditionalOnProperty(name = "intelligence.trusted-origin.refresh-worker-enabled", havingValue = "true", matchIfMissing = true)
public class TrustedOriginRefreshWorker implements SmartLifecycle {

	private static final Logger logger = LoggerFactory.getLogger(TrustedOriginRefreshWorker.class);

	private final TrustedOriginService trustedOrigins;
	private final long refreshIntervalMs;
	private final long maxAgeSeconds;
	private volatile boolean running;

	public TrustedOriginRefreshWorker(TrustedOriginService trustedOrigins,
			@Value("${intelligence.trusted-origin.refresh-interval-ms:5000}") long refreshIntervalMs,
			@Value("${intelligence.trusted-origin.max-age-seconds:30}") long maxAgeSeconds) {
		if (refreshIntervalMs < 1000) {
			throw new IllegalStateException("intelligence.trusted-origin.refresh-interval-ms 最小 1000ms");
		}
		if (maxAgeSeconds * 1000 < refreshIntervalMs) {
			throw new IllegalStateException("max-age-seconds 不得小于刷新间隔（否则快照永远不可用）");
		}
		this.trustedOrigins = trustedOrigins;
		this.refreshIntervalMs = refreshIntervalMs;
		this.maxAgeSeconds = maxAgeSeconds;
	}

	/** 定时刷新（fixedDelayString 支持配置占位）；失败保留尚有效快照但绝不无限续期（maxAge 兜底）。 */
	@Scheduled(fixedDelayString = "${intelligence.trusted-origin.refresh-interval-ms:5000}", initialDelayString = "${intelligence.trusted-origin.refresh-initial-delay-ms:1000}")
	public void scheduledRefresh() {
		if (!running) {
			return;
		}
		trustedOrigins.refresh().subscribe(null,
				error -> logger.warn("scheduled trusted origin refresh failed: {}", error.getMessage()));
	}

	@Override
	public void start() {
		running = true;
	}

	@Override
	public void stop() {
		running = false;
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	long refreshIntervalMs() {
		return refreshIntervalMs;
	}

	long maxAgeSeconds() {
		return maxAgeSeconds;
	}

	Duration maxAge() {
		return Duration.ofSeconds(maxAgeSeconds);
	}
}
