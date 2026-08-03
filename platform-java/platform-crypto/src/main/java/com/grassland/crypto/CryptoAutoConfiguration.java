package com.grassland.crypto;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * {@code platform-crypto} 自动配置（GL-P3-AI-001 Phase 1）。
 *
 * <p>消费者服务只需：
 * <pre>
 * dependencies {
 *     implementation(project(":platform-crypto"))
 * }
 * </pre>
 *
 * <p>并配置 {@code crypto.kek.encoded} 即可启用 {@link EnvelopeEncryption} bean。
 */
@AutoConfiguration
@EnableConfigurationProperties(CryptoProperties.class)
@ConditionalOnProperty("crypto.kek.encoded")
public class CryptoAutoConfiguration {
}
