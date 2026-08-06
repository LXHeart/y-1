package com.grassland.edge.internalassertion;

import com.grassland.identity.assertion.token.AccessTokenSigner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AccessTokenSigner bean 配置（GL-P3-IDENTITY-001）。
 *
 * <p>secret 绑 {@code EDGE_ACCESS_TOKEN_SECRET}（与 identity-service 的 {@code IDENTITY_ACCESS_TOKEN_SECRET} 同值）。
 * 未配 secret → bean 不装 → {@link AccessTokenIdentityResolver} 不装（@ConditionalOnBean）→
 * InternalAssertionFilter 的 bearer 分支自动跳过 → 移动端请求视匿名（fail-closed，Web cookie 路径不受影响）。
 */
@Configuration
public class AccessTokenConfig {

    @Bean
    @ConditionalOnProperty(name = "edge.access-token.secret")
    AccessTokenSigner accessTokenSigner(
            @Value("${edge.access-token.secret}") String secret,
            @Value("${edge.access-token.kid:access-token-v1}") String kid,
            @Value("${edge.access-token.leeway-seconds:5}") long leewaySeconds) {
        return new AccessTokenSigner(secret.getBytes(StandardCharsets.UTF_8), kid, Duration.ofSeconds(leewaySeconds));
    }
}
