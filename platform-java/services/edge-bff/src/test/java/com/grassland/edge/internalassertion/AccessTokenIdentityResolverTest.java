package com.grassland.edge.internalassertion;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.token.AccessToken;
import com.grassland.identity.assertion.token.AccessTokenSigner;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequest;

/**
 * {@link AccessTokenIdentityResolver} 验签链路单测。
 * 用真实 AccessTokenSigner 签发 token 验证 verify 路径；DB 查询由 IT 覆盖（SessionIdentityResolverIT 同口径）。
 */
class AccessTokenIdentityResolverTest {

    private static final byte[] SECRET = "test-access-token-secret-32bytes!".getBytes(StandardCharsets.UTF_8);
    private static final String KID = "access-token-v1";
    private final AccessTokenSigner signer = new AccessTokenSigner(SECRET, KID, Duration.ofSeconds(5));

    @Test
    void signedTokenVerifiesSuccessfully() {
        AccessToken token = AccessToken.issue(
                "acct-1", "user@test", "user", "device-1", "rt-1",
                KID, java.time.Instant.now(), Duration.ofSeconds(900));
        String signed = signer.sign(token);

        assertThat(signer.verify(signed, null)).isPresent();
        assertThat(signer.verify(signed, null).get().accountId()).isEqualTo("acct-1");
    }

    @Test
    void expiredTokenReturnsEmpty() {
        AccessToken token = AccessToken.issue(
                "acct-1", "user@test", "user", "device-1", "rt-1",
                KID, java.time.Instant.now().minusSeconds(1000), Duration.ofSeconds(100));
        String signed = signer.sign(token);

        assertThat(signer.verify(signed, null)).isEmpty();
    }

    @Test
    void tamperedTokenReturnsEmpty() {
        AccessToken token = AccessToken.issue(
                "acct-1", "user@test", "user", "device-1", "rt-1",
                KID, java.time.Instant.now(), Duration.ofSeconds(900));
        String signed = signer.sign(token);
        String tampered = signed.substring(0, signed.length() - 5) + "XXXXX";

        assertThat(signer.verify(tampered, null)).isEmpty();
    }

    @Test
    void wrongSecretReturnsEmpty() {
        AccessToken token = AccessToken.issue(
                "acct-1", "user@test", "user", "device-1", "rt-1",
                KID, java.time.Instant.now(), Duration.ofSeconds(900));
        AccessTokenSigner wrongSigner = new AccessTokenSigner(
                "wrong-secret-32bytes!!!!!!!!!!!!!!!".getBytes(StandardCharsets.UTF_8), KID, Duration.ofSeconds(5));
        String signed = wrongSigner.sign(token);

        assertThat(signer.verify(signed, null)).isEmpty();
    }

    @Test
    void refreshTokenFormatNotMisverified() {
        // refresh_token 是 128B base64url 无点号——verify 会直接返回 empty（不误验为 access token）
        String refreshToken = "abcDEF1234567890abcDEF1234567890abcDEF1234567890";
        assertThat(signer.verify(refreshToken, null)).isEmpty();
    }

    @Test
    void resolverReturnsEmptyWithoutBearerHeader() {
        AccessTokenIdentityResolver resolver = new AccessTokenIdentityResolver(null, signer);
        ServerHttpRequest request = MockServerHttpRequest.get("/api/tasks").build();

        // 无 Authorization 头 → empty（不触 NPE，db=null 安全因为不走到 DB 查询）
        assertThat(resolver.resolve(request).block()).isNull();
    }

    @Test
    void resolverReturnsEmptyWithNonBearerAuthorization() {
        AccessTokenIdentityResolver resolver = new AccessTokenIdentityResolver(null, signer);
        ServerHttpRequest request = MockServerHttpRequest.get("/api/tasks")
                .header("Authorization", "Basic abc123").build();

        assertThat(resolver.resolve(request).block()).isNull();
    }

    @Test
    void resolverReturnsEmptyWithInvalidBearerToken() {
        AccessTokenIdentityResolver resolver = new AccessTokenIdentityResolver(null, signer);
        ServerHttpRequest request = MockServerHttpRequest.get("/api/tasks")
                .header("Authorization", "Bearer invalid-token-string").build();

        // verify 失败 → empty（不走到 DB 查询，db=null 安全）
        assertThat(resolver.resolve(request).block()).isNull();
    }
}
