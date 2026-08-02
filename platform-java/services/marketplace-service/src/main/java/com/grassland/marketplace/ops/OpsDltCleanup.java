package com.grassland.marketplace.ops;

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
 * ops_dlt_message retention 清理（GL-P3-PLATFORM-001，承接 GL-P1-OPS-001 Stage 2）。
 *
 * <p>Stage 2 的处置语义是「弃置只改状态、不删行也不删 payload」——死信是审计对象，「谁在何时放弃了哪条消息」
 * 必须留痕。但这意味着 ops_dlt_message 只增不减，长期会无限膨胀。本类按 retention 删除<b>已进入终态</b>
 * （replayed/discarded）且超期的历史行，让表收敛；<b>绝不删 pending</b>（pending 是待运营处置的死信，删了
 * 就丢证据与处置入口）。镜像 intelligence {@code MediaCleanup} 的单飞 + claim 模式。
 *
 * <p>默认关闭（{@code marketplace.ops.dlt-cleanup.enabled}），compose 在 java-edge profile 下开启——
 * 本地/测试无 Kafka、无死信，开了也是空跑，但避免无谓的定时 DB 查询。
 */
@Component
@ConditionalOnProperty(prefix = "marketplace.ops.dlt-cleanup", name = "enabled", havingValue = "true")
public class OpsDltCleanup {

    private static final Logger log = LoggerFactory.getLogger(OpsDltCleanup.class);

    private final OpsDltMessageRepository repository;
    private final Duration retention;
    private final AtomicBoolean running = new AtomicBoolean();

    public OpsDltCleanup(
            OpsDltMessageRepository repository,
            @Value("${marketplace.ops.dlt-cleanup.retention-days:30}") long retentionDays) {
        this.repository = repository;
        // 至少 1 天：retention 太短会在运营还没复核完终态死信前就清掉，违背「审计对象」初衷。
        this.retention = Duration.ofDays(Math.max(retentionDays, 1L));
    }

    @Scheduled(fixedDelayString = "${marketplace.ops.dlt-cleanup.interval-ms:3600000}")
    public void cleanupOld() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        cleanup(Instant.now())
                .doFinally(signal -> running.set(false))
                .subscribe(
                        deleted -> log.debug("ops dlt cleanup removed {} terminal rows older than {}",
                                deleted, retention),
                        error -> log.warn("ops dlt cleanup round failed", error));
    }

    /**
     * 删除终态且超期的行。{@code now} 注入便于测试；生产取 {@link Instant#now()}。
     *
     * @return 删除行数
     */
    Mono<Long> cleanup(Instant now) {
        Instant cutoff = now.minus(retention);
        return repository.deleteTerminalOlderThan(cutoff);
    }
}
