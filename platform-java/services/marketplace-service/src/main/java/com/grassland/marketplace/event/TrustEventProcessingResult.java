package com.grassland.marketplace.event;

public enum TrustEventProcessingResult {
    PROCESSED,
    DUPLICATE,
    /** 已记录 inbox 但无可送达对象（如 engagementRef 解析不到对方账号）——不产生通知，也不阻塞分区。 */
    NO_RECIPIENT,
    /**
     * 已记录 inbox 但按契约刻意不派生下游事件（如 merchant_rejection 争议的通知已由 marketplace 自己发出）。
     * 与 {@link #NO_RECIPIENT} 分开计量——后者是「本该通知却解析不到人」的异常信号，本项是正常路径。
     */
    SUPPRESSED,
    IGNORED
}
