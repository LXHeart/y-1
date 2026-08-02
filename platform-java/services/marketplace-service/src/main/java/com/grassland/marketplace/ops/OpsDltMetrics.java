package com.grassland.marketplace.ops;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * ops_dlt_message 积压指标（GL-P3-PLATFORM-001，承接 GL-P1-OPS-001 Stage 2 的运营死信）。
 *
 * <p>Stage 2 把死信落了库、给了运营处置入口，但<b>没人盯着它涨</b>：死信只在消费失败时进表，没有发布循环，
 * 表里积了多少 pending 全靠运营主动刷页面。本类把 pending/discarded/total 暴露成 Prometheus gauge，
 * 配 {@code rules/marketplace-ops-dlt.yml} 让 pending>0 时告警——不再「躺在 topic 里没人知道」。
 *
 * <p>镜像 identity {@code MailOutboxPublisher} 的 backlog gauge 思路，但 DLT 没有自己的轮询循环，故独立
 * 定时刷新（默认 15s）。<b>不与 {@code OpsDltConsumer} 的 enabled 绑定</b>：consumer 关闭时表里仍可能有
 * 历史死信或人工改库的结果，指标要反映 DB 真相而非 consumer 是否在跑（同 MailOutboxPublisher「停发也刷新」）。
 *
 * <p>刷新失败只记日志：指标是观测手段，不能因 DB 抖动拖垮调度线程。
 */
@Component
public class OpsDltMetrics {

    private static final Logger log = LoggerFactory.getLogger(OpsDltMetrics.class);

    private final OpsDltMessageRepository repository;
    private final AtomicLong pendingGauge = new AtomicLong();
    private final AtomicLong discardedGauge = new AtomicLong();
    private final AtomicLong totalGauge = new AtomicLong();

    public OpsDltMetrics(OpsDltMessageRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;
        Gauge.builder("grassland.ops.dlt.pending", pendingGauge, AtomicLong::get)
                .description("ops_dlt_message rows in pending state awaiting operator action")
                .register(meterRegistry);
        Gauge.builder("grassland.ops.dlt.discarded", discardedGauge, AtomicLong::get)
                .description("ops_dlt_message rows discarded by operators (audit trend)")
                .register(meterRegistry);
        Gauge.builder("grassland.ops.dlt.total", totalGauge, AtomicLong::get)
                .description("total ops_dlt_message rows (use to confirm retention cleanup is draining history)")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${marketplace.ops.dlt-metrics.refresh-ms:15000}")
    public void refresh() {
        Mono.when(
                        repository.pendingCount()
                                .doOnNext(pendingGauge::set)
                                .onErrorResume(e -> {
                                    log.warn("Failed to refresh ops dlt pending metric", e);
                                    return Mono.empty();
                                }),
                        repository.discardedCount()
                                .doOnNext(discardedGauge::set)
                                .onErrorResume(e -> {
                                    log.warn("Failed to refresh ops dlt discarded metric", e);
                                    return Mono.empty();
                                }),
                        repository.totalCount()
                                .doOnNext(totalGauge::set)
                                .onErrorResume(e -> {
                                    log.warn("Failed to refresh ops dlt total metric", e);
                                    return Mono.empty();
                                }))
                .subscribe();
    }
}
