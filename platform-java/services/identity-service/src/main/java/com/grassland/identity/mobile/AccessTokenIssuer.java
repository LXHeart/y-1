package com.grassland.identity.mobile;

import com.grassland.identity.assertion.token.AccessToken;
import com.grassland.identity.assertion.token.AccessTokenSigner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 移动端 access token 签发方（GL-P3-IDENTITY-001）。包装共享库 {@link AccessTokenSigner}：
 * identity-service 签发、edge-bff 验签，两端经 {@code IDENTITY_ACCESS_TOKEN_SECRET} 同值。
 *
 * <p>未配置（secret 空）→ {@link #isConfigured()} false，调用方先 gate（移动端点 503，Web 不受影响）。
 */
@Component
public class AccessTokenIssuer {

    private final AccessTokenSigner signer;
    private final Duration ttl;

    public AccessTokenIssuer(
            @Value("${identity.mobile.access-token.secret:}") String secret,
            @Value("${identity.mobile.access-token.kid:access-token-v1}") String kid,
            @Value("${identity.mobile.access-token.ttl-seconds:900}") long ttlSeconds,
            @Value("${identity.mobile.access-token.leeway-seconds:5}") long leewaySeconds) {
        byte[] secretBytes = (secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);
        this.signer = new AccessTokenSigner(secretBytes, kid, Duration.ofSeconds(leewaySeconds));
        this.ttl = Duration.ofSeconds(ttlSeconds);
    }

    public boolean isConfigured() {
        return signer.isConfigured();
    }

    public Duration ttl() {
        return ttl;
    }

    public long ttlSeconds() {
        return ttl.toSeconds();
    }

    /**
     * 签发一枚 access token（{@code sessionToken} = refresh_token 行 id）。
     *
     * @throws IllegalStateException 未配置 secret（调用方应先 {@link #isConfigured()} gate）
     */
    public String issue(String accountId, String email, String role, String deviceId, String refreshTokenId) {
        return signer.sign(AccessToken.issue(accountId, email, role, deviceId, refreshTokenId,
                signer.kid(), Instant.now(), ttl));
    }

    /** 仅测试/诊断用：暴露内部 signer（如 IT 验签）。 */
    AccessTokenSigner signer() {
        return signer;
    }
}
