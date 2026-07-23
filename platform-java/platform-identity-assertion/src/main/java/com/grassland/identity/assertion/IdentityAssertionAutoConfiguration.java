package com.grassland.identity.assertion;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 断言自动配置。消费者加 {@code implementation(project(":platform-identity-assertion"))} 并设
 * {@code identity-assertion.enabled=true} + {@code secret} 即获得 {@link IdentityAssertionSigner} Bean，无需改主类。
 *
 * <p>按服务 opt-in（仿 {@code S3ObjectStorageAutoConfiguration} / {@code R2dbcConnectionFactoryConfig}）：
 * 只引依赖但未启用的服务不会在启动时 fail。Signer 的 audience/leeway 取自 {@link IdentityAssertionProperties}，
 * BFF 与下游服务须配同一组值才能签/验互通。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "identity-assertion", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(IdentityAssertionProperties.class)
public class IdentityAssertionAutoConfiguration {

    @Bean
    IdentityAssertionSigner identityAssertionSigner(IdentityAssertionProperties props) {
        return new IdentityAssertionSigner(
                props.secret().getBytes(StandardCharsets.UTF_8),
                props.audience(),
                props.leeway());
    }
}
