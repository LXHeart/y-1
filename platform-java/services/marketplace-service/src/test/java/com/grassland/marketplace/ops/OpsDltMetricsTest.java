package com.grassland.marketplace.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * {@link OpsDltMetrics} 指标刷新（GL-P3-PLATFORM-001）。
 *
 * <p>锁的是「gauge 真的反映 DB 计数」与「刷新失败不抛（观测手段不能拖垮调度）」。
 * ops_dlt_pending 是告警源，这条 gauge 读不对，{@code MarketplaceOpsDltPending} 规则就形同虚设。
 */
class OpsDltMetricsTest {

    private final OpsDltMessageRepository repository = org.mockito.Mockito.mock(OpsDltMessageRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OpsDltMetrics metrics = new OpsDltMetrics(repository, registry);

    @Test
    void refreshUpdatesGaugesFromRepositoryCounts() {
        when(repository.pendingCount()).thenReturn(Mono.just(3L));
        when(repository.discardedCount()).thenReturn(Mono.just(7L));
        when(repository.totalCount()).thenReturn(Mono.just(12L));

        metrics.refresh();

        assertEquals(3.0, gauge("grassland.ops.dlt.pending"));
        assertEquals(7.0, gauge("grassland.ops.dlt.discarded"));
        assertEquals(12.0, gauge("grassland.ops.dlt.total"));
        verify(repository, atLeastOnce()).pendingCount();
    }

    @Test
    void refreshSurvivesRepositoryErrorSoSchedulerStaysAlive() {
        when(repository.pendingCount()).thenReturn(Mono.error(new RuntimeException("db unavailable")));
        when(repository.discardedCount()).thenReturn(Mono.just(0L));
        when(repository.totalCount()).thenReturn(Mono.just(0L));

        // 不抛即可：某一项失败不应拖垮整轮刷新，也不应影响其它项。
        metrics.refresh();

        assertEquals(0.0, gauge("grassland.ops.dlt.discarded"));
    }

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }
}
