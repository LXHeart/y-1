package com.grassland.messaging.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 默认值回退与钳制规则（原四服务 compact constructor 的单测盲区，随下沉补齐）。 */
class OutboxSettingsTest {

    @Test
    void rejectsBlankTopic() {
        assertThatThrownBy(() -> new OutboxSettings(" ", true, 0, 0, 0, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fallsBackToDefaultsOnNonPositiveValues() {
        var settings = new OutboxSettings("t", true, 0, 0, 0, 0, 0, 0, 0);
        assertThat(settings.pollIntervalMs()).isEqualTo(2_000);
        assertThat(settings.batchSize()).isEqualTo(1);
        assertThat(settings.maxConcurrency()).isEqualTo(1);
        assertThat(settings.ackTimeoutMs()).isEqualTo(10_000);
        assertThat(settings.claimLeaseMs()).isEqualTo(300_000);
        assertThat(settings.initialBackoffMs()).isEqualTo(1_000);
        assertThat(settings.maxBackoffMs()).isEqualTo(60_000);
    }

    @Test
    void clampsLeaseToAtLeastAckTimeout_andBackoffCeilingToAtLeastInitial() {
        var settings = new OutboxSettings("t", true, 100, 5, 2, 60_000, 1_000, 5_000, 10);
        // claim 租约不短于 ack 超时，否则 ack 期间租约过期会被并发 claim 重发。
        assertThat(settings.claimLeaseMs()).isEqualTo(60_000);
        assertThat(settings.maxBackoffMs()).isEqualTo(5_000);
        assertThat(settings.ackTimeout()).isEqualTo(java.time.Duration.ofMillis(60_000));
        assertThat(settings.claimLease()).isEqualTo(java.time.Duration.ofMillis(60_000));
    }
}
