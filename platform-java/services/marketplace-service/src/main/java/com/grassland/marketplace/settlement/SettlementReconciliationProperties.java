package com.grassland.marketplace.settlement;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 结算对账派发配置（Slice 7B）。{@code dispatcher-enabled} 默认开（生产），测试基座 {@code MarketplaceItSupport}
 * 关闭以避免 IT 意外启动真实 workflow。
 */
@ConfigurationProperties("marketplace.reconciliation")
public record SettlementReconciliationProperties(
        boolean dispatcherEnabled,
        long pollMs,
        int batchSize,
        long redispatchSeconds,
        long startFailureBackoffSeconds) {

    public SettlementReconciliationProperties() {
        this(true, 2000L, 16, 30L, 10L);
    }

    public SettlementReconciliationProperties(
            Boolean dispatcherEnabled, Long pollMs, Integer batchSize,
            Long redispatchSeconds, Long startFailureBackoffSeconds) {
        this(
                dispatcherEnabled == null ? true : dispatcherEnabled,
                pollMs == null ? 2000L : pollMs,
                batchSize == null ? 16 : batchSize,
                redispatchSeconds == null ? 30L : redispatchSeconds,
                startFailureBackoffSeconds == null ? 10L : startFailureBackoffSeconds);
    }
}
