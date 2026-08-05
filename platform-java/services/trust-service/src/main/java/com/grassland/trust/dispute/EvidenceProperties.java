package com.grassland.trust.dispute;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 争议证据配置（GL-P2-TRUST-001 T1）。
 *
 * <p>{@code retentionDays}：D-10 证据保留期，<b>provisional</b> 默认 365 天（证据保留 6–12 月，取上界）。
 * <b>TODO 法务/财务定稿</b>后按 D-10 终审值覆盖；过期清理任务（脱敏/删除）另项，本轮只建模 retention_until。
 */
@ConfigurationProperties(prefix = "trust.evidence")
public record EvidenceProperties(int retentionDays) {
    public EvidenceProperties {
        if (retentionDays <= 0) {
            retentionDays = 365;
        }
    }
}
