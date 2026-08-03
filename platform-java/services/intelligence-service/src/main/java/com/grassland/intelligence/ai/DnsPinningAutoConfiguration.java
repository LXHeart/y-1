package com.grassland.intelligence.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DNS Pinning 自动配置（GL-P3-AI-001 Phase 2）。
 *
 * <p>当 {@code ai.dns-pinning.enabled=true} 时装配 {@link DnsPinningResolver} bean。
 */
@Configuration
@ConditionalOnProperty(prefix = "ai.dns-pinning", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(DnsPinningProperties.class)
public class DnsPinningAutoConfiguration {

    @Bean
    public DnsPinningResolver dnsPinningResolver(DnsPinningProperties properties) {
        return DnsPinningResolver.fromEnv(properties.trustedDomains());
    }
}
