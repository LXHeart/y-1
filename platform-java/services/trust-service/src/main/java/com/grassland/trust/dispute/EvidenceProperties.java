package com.grassland.trust.dispute;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 争议证据配置（GL-P2-TRUST-001 T1）。
 *
 * <p>{@code pseudonymSecret} 用于按案件生成确定性当事人伪名。生产必须使用独立随机密钥，
 * 避免跨环境关联；空值仅允许本地/测试以固定开发值启动。
 */
@ConfigurationProperties(prefix = "trust.evidence")
public record EvidenceProperties(int retentionDays, String pseudonymSecret) {
    public EvidenceProperties {
        if (retentionDays <= 0) {
            retentionDays = 365;
        }
        if (pseudonymSecret == null || pseudonymSecret.isBlank()) {
            throw new IllegalStateException("trust.evidence.pseudonym-secret must be configured");
        }
    }
}
