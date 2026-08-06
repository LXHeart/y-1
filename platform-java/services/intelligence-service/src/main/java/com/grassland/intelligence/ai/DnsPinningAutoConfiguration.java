package com.grassland.intelligence.ai;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DNS Pinning 自动配置（GL-P3-AI-001 Phase 2）。
 *
 * <p>解析器始终装配：严格 BYOK 校验不能被配置关闭。配置项仅用于预载运维固定地址。
 */
@Configuration
@EnableConfigurationProperties(DnsPinningProperties.class)
public class DnsPinningAutoConfiguration {

    @Bean
    public DnsPinningResolver dnsPinningResolver(DnsPinningProperties properties) {
        return DnsPinningResolver.fromEnv(properties.trustedDomains());
    }
}
