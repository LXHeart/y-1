package com.grassland.edge.internalassertion;

import com.grassland.identity.assertion.token.AccessTokenSigner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AccessTokenSigner bean 配置（GL-P3-IDENTITY-001）。
 *
 * <p>当前 secret 绑 {@code EDGE_ACCESS_TOKEN_SECRET}（与 identity-service 当前签发 secret 同值），
 * 轮换窗口通过 {@code EDGE_ACCESS_TOKEN_PREVIOUS_KEYS} 追加旧/预发布验签 key。
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
            @Value("${edge.access-token.previous-keys:}") String previousKeys,
            @Value("${edge.access-token.leeway-seconds:5}") long leewaySeconds) {
        return new AccessTokenSigner(secret.getBytes(StandardCharsets.UTF_8), kid,
                parsePreviousKeys(previousKeys), Duration.ofSeconds(leewaySeconds));
    }

    /** previous-keys 格式：kid=secret,kid2=secret2。secret 不得含逗号或等号。 */
    static Map<String, byte[]> parsePreviousKeys(String value) {
        Map<String, byte[]> keys = new LinkedHashMap<>();
        if (value == null || value.isBlank()) {
            return keys;
        }
        for (String entry : value.split(",")) {
            int separator = entry.indexOf('=');
            if (separator <= 0 || separator == entry.length() - 1) {
                throw new IllegalArgumentException("edge.access-token.previous-keys must use kid=secret entries");
            }
            String keyId = entry.substring(0, separator).trim();
            String keySecret = entry.substring(separator + 1).trim();
            if (keyId.isEmpty() || keySecret.isEmpty() || keys.putIfAbsent(keyId,
                    keySecret.getBytes(StandardCharsets.UTF_8)) != null) {
                throw new IllegalArgumentException("edge.access-token.previous-keys contains invalid/duplicate kid");
            }
        }
        return keys;
    }
}
