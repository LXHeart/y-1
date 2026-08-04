package com.grassland.identity.mobile;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * refresh_token retention 清理（GL-P3-IDENTITY-001 Phase 4）。
 *
 * <p>撤销是软删（置 {@code revoked_at}），行只增不减；本类按 retention 硬删<b>已过期</b>与<b>已撤销</b>
 * 且超期的历史行，让表收敛。<b>绝不删活跃行</b>（未过期未撤销 = 有效登录凭据）。保留 retention 宽限
 * （默认 7 天）的意义：近期过期的行还能给出清晰的 401（区别于「未知 token」）并留取证窗口。
 * 镜像 marketplace {@code OpsDltCleanup} / intelligence {@code MediaCleanup} 的单飞模式。
 *
 * <p>默认关闭（{@code identity.mobile.refresh-token.cleanup.enabled}），compose java-edge 下开启。
 */
@Component
@ConditionalOnProperty(prefix = "identity.mobile.refresh-token.cleanup", name = "enabled", havingValue = "true")
public class RefreshTokenCleanup {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);

    private final RefreshTokenRepository repository;
    private final Duration retention;
    private final AtomicBoolean running = new AtomicBoolean();

    public RefreshTokenCleanup(
            RefreshTokenRepository repository,
            @Value("${identity.mobile.refresh-token.cleanup.retention-days:7}") long retentionDays) {
        this.repository = repository;
        this.retention = Duration.ofDays(Math.max(retentionDays, 0L));
    }

    @Scheduled(fixedDelayString = "${identity.mobile.refresh-token.cleanup.interval-ms:3600000}")
    public void cleanupOld() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        cleanup(Instant.now())
                .doFinally(signal -> running.set(false))
                .subscribe(
                        deleted -> log.debug("refresh token cleanup removed {} rows older than retention {}",
                                deleted, retention),
                        error -> log.warn("refresh token cleanup round failed", error));
    }

    /**
     * 删除过期/撤销且超期的行。{@code now} 注入便于测试；生产取 {@link Instant#now()}。
     *
     * @return 删除行数（过期 + 撤销两类之和）
     */
    Mono<Long> cleanup(Instant now) {
        Instant cutoff = now.minus(retention);
        return repository.deleteExpiredBefore(cutoff)
                .zipWith(repository.deleteRevokedBefore(cutoff))
                .map(tuple -> tuple.getT1() + tuple.getT2());
    }
}
