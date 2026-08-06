package com.grassland.intelligence.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DNS Pinning 配置属性（GL-P3-AI-001 Phase 2）。
 *
 * <p>通过 {@code ai.dns-pinning.*} 前缀配置。
 */
@ConfigurationProperties("ai.dns-pinning")
public record DnsPinningProperties(

        /** 兼容旧配置；严格 BYOK DNS 校验始终启用，不能通过此开关关闭。 */
        @DefaultValue("false")
        boolean enabled,

        /** 受信任域名与 IP 映射（环境变量；格式：domain1=ip1,ip2;domain2=ip3） */
        @DefaultValue("")
        String trustedDomains

) {
    /** 空的预载配置；严格 BYOK DNS 校验仍保持启用。 */
    public static DnsPinningProperties disabled() {
        return new DnsPinningProperties(false, "");
    }
}
