package com.grassland.identity.assertion;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 断言自动配置（GL-P0-ASSERT-001 keyring 模式）。
 *
 * <p>消费者加 {@code implementation(project(":platform-identity-assertion"))} 并设
 * {@code identity-assertion.enabled=true} + keyring keys（或 legacy secret）即可获得
 * {@link IdentityAssertionSigner} Bean，无需改主类。
 *
 * <h3>Bean 装配规则</h3>
 * <ul>
 *   <li>keyring 模式：构造 {@link PropertiesKeyring} + {@link InMemoryAssertionReplayGuard}，
 *       装配 keyring 构造器的 {@link IdentityAssertionSigner}。</li>
 *   <li>legacy 模式：装配 legacy 构造器的 {@link IdentityAssertionSigner}（仅测试兼容）。</li>
 *   <li>未启用：不装配 signer Bean（服务正常启动）。</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "identity-assertion", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(IdentityAssertionProperties.class)
public class IdentityAssertionAutoConfiguration {

    /**
     * 装配 keyring Bean。
     *
     * <p>keyring 模式：含 signing-keys/verify-keys。legacy 模式：空 keyring（无钥，signer 走 legacy 构造器不读它）。
     * 始终装配以保证 {@code identityAssertionSigner} 的 keyring 入参可注入（legacy 配置不设 issuer 也能启动）。
     */
    @Bean
    IdentityAssertionKeyring identityAssertionKeyring(IdentityAssertionProperties props) {
        return PropertiesKeyring.from(props);
    }

    /**
     * 装配 replay guard Bean（可选，默认 NO_OP）。
     *
     * <p>keyring 模式下，若 {@code replay-protection.enabled=true} 则装配
     * {@link InMemoryAssertionReplayGuard}；否则装配 {@link AssertionReplayGuard#NO_OP}。
     */
    @Bean
    AssertionReplayGuard identityAssertionReplayGuard(IdentityAssertionProperties props) {
        if (!props.isLegacyMode() && props.replayProtection().enabled()) {
            return new InMemoryAssertionReplayGuard(true);
        }
        return AssertionReplayGuard.NO_OP;
    }

    /**
     * 装配 signer Bean（keyring 或 legacy 模式）。
     *
     * <p>keyring 模式：需 keyring Bean + issuer。
     * <p>legacy 模式：需 secret + audience。
     */
    @Bean
    IdentityAssertionSigner identityAssertionSigner(IdentityAssertionProperties props,
                                                     IdentityAssertionKeyring keyring,
                                                     AssertionReplayGuard replayGuard) {
        if (props.isLegacyMode()) {
            // legacy 模式：单 secret 构造器
            return new IdentityAssertionSigner(
                    props.secret().getBytes(StandardCharsets.UTF_8),
                    props.audience(),
                    props.leeway());
        }
        // keyring 模式：keyring 构造器
        return new IdentityAssertionSigner(keyring, props.issuer(), replayGuard, props.leeway());
    }
}
