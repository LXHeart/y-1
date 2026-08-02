package com.grassland.marketplace.ops;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * {@link OpsDltCleanup} retention 清理（GL-P3-PLATFORM-001）。
 *
 * <p>锁两件事：① cutoff 按 retention 正确回算（{@code now - retention}）；② 只把 cutoff 交给仓库的
 * 终态删除——<b>pending 绝不会被这个 cutoff 误伤</b>是仓库 SQL 的职责（{@code status IN ('replayed','discarded')}），
 * 这里只确保调用契约传对参数。
 */
class OpsDltCleanupTest {

    private final OpsDltMessageRepository repository = mock(OpsDltMessageRepository.class);

    @Test
    void cutoffIsNowMinusRetention() {
        when(repository.deleteTerminalOlderThan(any())).thenReturn(Mono.just(5L));
        OpsDltCleanup cleanup = new OpsDltCleanup(repository, 30L);

        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        long deleted = cleanup.cleanup(now).block();

        assertEquals(5L, deleted);
        // 30 天前的同一时刻：cutoff 必须精确等于 now - 30d，差一秒都会误删或漏删。
        Instant expectedCutoff = now.minus(30, ChronoUnit.DAYS);
        verify(repository).deleteTerminalOlderThan(expectedCutoff);
    }

    @Test
    void retentionClampedToOneDayMinimum() {
        when(repository.deleteTerminalOlderThan(any())).thenReturn(Mono.just(0L));
        // 误配 0 不应让 retention 塌成「立刻删」——至少 1 天，给运营复核终态死信的窗口。
        OpsDltCleanup cleanup = new OpsDltCleanup(repository, 0L);

        Instant now = Instant.parse("2026-08-02T00:00:00Z");
        cleanup.cleanup(now).block();

        verify(repository).deleteTerminalOlderThan(now.minus(1, ChronoUnit.DAYS));
    }
}
