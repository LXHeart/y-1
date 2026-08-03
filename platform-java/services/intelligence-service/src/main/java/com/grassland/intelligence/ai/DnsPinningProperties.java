package com.grassland.intelligence.ai;

import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DNS Pinning 配置属性（GL-P3-AI-001 Phase 2）。
 *
 * <p>通过 {@code ai.dns-pinning.*} 前缀配置。
 */
@ConfigurationProperties("ai.dns-pinning")
@ConditionalOnProperty(prefix = "ai.dns-pinning", name = "enabled", havingValue = "true", matchIfMissing = false)
public record DnsPinningProperties(

        /** 是否启用 DNS Pinning（默认关闭，BYOK 场景建议开启） */
        @DefaultValue("false")
        boolean enabled,

        /** 受信任域名与 IP 映射（环境变量；格式：domain1=ip1,ip2;domain2=ip3） */
        @DefaultValue("")
        String trustedDomains

) {
    /** 空配置（禁用时使用）。 */
    public static DnsPinningProperties disabled() {
        return new DnsPinningProperties(false, "");
    }
}
