package com.grassland.marketplace.event;

public enum TrustEventProcessingResult {
    PROCESSED,
    DUPLICATE,
    /** 已记录 inbox 但无可送达对象（如 engagementRef 解析不到对方账号）——不产生通知，也不阻塞分区。 */
    NO_RECIPIENT,
    IGNORED
}
