package com.grassland.marketplace.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * {@link OpsDltMessageRepository} 计数与 retention 清理 SQL（GL-P3-PLATFORM-001）。
 *
 * <p>重点锁的是 {@code deleteTerminalOlderThan} 的 SQL 谓词：<b>pending 即便超期也不能被删</b>
 * ——pending 是待运营处置的死信，retention 清理若误伤它就丢了证据与处置入口。终态（replayed/discarded）
 * 才清，且只清超期者。
 */
class OpsDltMessageRepositoryIT extends MarketplaceItSupport {

    private static final String DLT_TOPIC = "grassland.trust.events.DLT";
    private static final String ORIGINAL_TOPIC = "grassland.trust.events";

    @Autowired
    private OpsDltMessageRepository messages;

    @Autowired
    private DatabaseClient db;

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM ops_dlt_message").fetch().rowsUpdated().block();
    }

    @Test
    @DisplayName("计数按状态拆分：pending / discarded / total")
    void countsByStatus() {
        insertPending(1L);
        insertPending(2L);
        messages.markDiscarded(insertPending(3L).id()).block();
        // replayed 计入 total 但不计入 pending/discarded
        messages.markReplayed(insertPending(4L).id()).block();

        assertThat(messages.pendingCount().block()).isEqualTo(2L);
        assertThat(messages.discardedCount().block()).isEqualTo(1L);
        assertThat(messages.totalCount().block()).isEqualTo(4L);
    }

    @Test
    @DisplayName("retention 清理：删终态超期行，但 pending 即便超期也保留")
    void deleteTerminalOlderThanKeepsPendingEvenWhenOverdue() {
        // 三条终态 + 旧 created_at：应被清（2 条 discarded + 1 条 replayed）
        backdate(messages.markReplayed(insertPending(10L).id()).block().id(), fortyDaysAgo());
        backdate(messages.markDiscarded(insertPending(11L).id()).block().id(), fortyDaysAgo());
        backdate(messages.markDiscarded(insertPending(12L).id()).block().id(), fortyDaysAgo());
        // 一条终态但近期：保留
        messages.markDiscarded(insertPending(13L).id()).block();
        // 一条 pending 且超期：必须保留（运营还没处置）
        backdate(insertPending(14L).id(), fortyDaysAgo());

        Instant cutoff = Instant.now().minus(30, ChronoUnit.DAYS);
        Long deleted = messages.deleteTerminalOlderThan(cutoff).block();

        assertThat(deleted).isEqualTo(3L);
        assertThat(messages.totalCount().block()).isEqualTo(2L); // 近期 discarded + 超期 pending
        assertThat(messages.pendingCount().block()).isEqualTo(1L); // 超期 pending 仍在
    }

    /** insertIfAbsent 已 block，返回的就是 OpsDltMessage，调用方用 {@link OpsDltMessage#id()} 取 id。 */
    private OpsDltMessage insertPending(long offset) {
        return messages.insertIfAbsent(
                        DLT_TOPIC, 0, offset, ORIGINAL_TOPIC, "key-" + offset,
                        "{\"eventType\":\"DisputeFinalized\"}", "NullPointerException")
                .block();
    }

    private void backdate(String id, Instant ts) {
        db.sql("UPDATE ops_dlt_message SET created_at = :ts WHERE id = CAST(:id AS uuid)")
                .bind("ts", OffsetDateTime.ofInstant(ts, ZoneOffset.UTC))
                .bind("id", id)
                .fetch().rowsUpdated().block();
    }

    private static Instant fortyDaysAgo() {
        return Instant.now().minus(40, ChronoUnit.DAYS);
    }
}
