package com.grassland.identity.assertion;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.URLDecoder;
import java.time.Duration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * 断言自动配置（GL-P0-ASSERT-001 keyring 模式）。
 *
 * <p>消费者加 {@code implementation(project(":platform-identity-assertion"))} 并设
 * {@code identity-assertion.enabled=true} + keyring keys 即可获得
 * {@link IdentityAssertionSigner} Bean，无需改主类。
 *
 * <h3>Bean 装配规则</h3>
 * <ul>
 *   <li>构造 {@link PropertiesKeyring} + {@link InMemoryAssertionReplayGuard}，
 *       装配 keyring 构造器的 {@link IdentityAssertionSigner}。</li>
 *   <li>未启用：不装配 signer Bean（服务正常启动）。</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "identity-assertion", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(IdentityAssertionProperties.class)
public class IdentityAssertionAutoConfiguration {

    @Bean(name = "identityAssertionReplayConnectionFactory", destroyMethod = "destroy")
    @Primary
    @ConditionalOnExpression("${identity-assertion.replay-protection.enabled:false}"
            + " && '${identity-assertion.replay-protection.storage:redis}' == 'redis'")
    LettuceConnectionFactory identityAssertionReplayConnectionFactory(IdentityAssertionProperties props) {
        URI uri = URI.create(props.replayProtection().redisUrl());
        if (!"redis".equalsIgnoreCase(uri.getScheme()) && !"rediss".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("replay redis-url must use redis:// or rediss:// (scheme="
                    + uri.getScheme() + ")");
        }
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(
                uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 6379);
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            server.setDatabase(Integer.parseInt(path.substring(1)));
        }
        if (uri.getUserInfo() != null) {
            String[] credentials = uri.getUserInfo().split(":", 2);
            if (!credentials[0].isBlank()) {
                server.setUsername(URLDecoder.decode(credentials[0], StandardCharsets.UTF_8));
            }
            if (credentials.length == 2 && !credentials[1].isBlank()) {
                server.setPassword(RedisPassword.of(URLDecoder.decode(credentials[1], StandardCharsets.UTF_8)));
            }
        }
        LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(2));
        if ("rediss".equalsIgnoreCase(uri.getScheme())) {
            client.useSsl();
        }
        return new LettuceConnectionFactory(server, client.build());
    }

    /**
     * 装配 keyring Bean。
     *
     * <p>启用断言时 properties 已强制至少配置一把 signing/verify key；
     * signer 仅支持 keyring 模式。
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
    AssertionReplayGuard identityAssertionReplayGuard(
            IdentityAssertionProperties props,
            @Qualifier("identityAssertionReplayConnectionFactory")
            ObjectProvider<LettuceConnectionFactory> replayConnectionFactory) {
        if (props.replayProtection().usesRedis()) {
            LettuceConnectionFactory factory = replayConnectionFactory.getIfAvailable();
            if (factory == null) {
                throw new IllegalStateException("Redis replay protection enabled without connection factory");
            }
            return new RedisAssertionReplayGuard(
                    new ReactiveStringRedisTemplate(factory), props.replayProtection().keyPrefix());
        }
        if (props.replayProtection().usesMemory()) {
            return new InMemoryAssertionReplayGuard(true);
        }
        return AssertionReplayGuard.NO_OP;
    }

    /** 装配 keyring signer Bean。 */
    @Bean
    IdentityAssertionSigner identityAssertionSigner(IdentityAssertionProperties props,
                                                     IdentityAssertionKeyring keyring,
                                                     AssertionReplayGuard replayGuard) {
        return new IdentityAssertionSigner(keyring, props.issuer(), replayGuard, props.leeway());
    }
}
