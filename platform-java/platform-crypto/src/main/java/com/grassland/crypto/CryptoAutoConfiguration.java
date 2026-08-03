package com.grassland.crypto;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

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
 * <p>并配置 {@code crypto.kek.encoded} 即可启用 {@link EnvelopeEncryption} bean；
 * 未配置时该 bean 不装配，依赖方（如 BYOK 端点）按 fail-closed 门控不启用能力。
 */
@AutoConfiguration
@EnableConfigurationProperties(CryptoProperties.class)
public class CryptoAutoConfiguration {

    /** KEK 已配置（{@code crypto.kek.encoded} 非空白）才装配信封加密实现。 */
    @Bean
    @Conditional(CryptoKekConfiguredCondition.class)
    public EnvelopeEncryption envelopeEncryption(CryptoProperties properties) {
        return new BouncyCastleEnvelopeEncryption(properties);
    }
}
