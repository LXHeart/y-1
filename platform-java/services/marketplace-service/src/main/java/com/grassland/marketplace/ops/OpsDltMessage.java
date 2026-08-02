package com.grassland.marketplace.ops;

import java.time.Instant;

/** 一条已登记的死信消息（GL-P1-OPS-001 Stage 2）。位点 {@code topic:partition:offset} 唯一。 */
public record OpsDltMessage(
        String id,
        String topic,
        int partition,
        long offset,
        String originalTopic,
        String messageKey,
        String payload,
        String errorSummary,
        String status,
        Instant replayedAt,
        Instant discardedAt,
        Instant createdAt) {

    public boolean isPending() {
        return "pending".equals(status);
    }

    /** case 的 sourceRef 用位点，重启重读不会开出第二张单。 */
    public String position() {
        return topic + ":" + partition + ":" + offset;
    }
}
