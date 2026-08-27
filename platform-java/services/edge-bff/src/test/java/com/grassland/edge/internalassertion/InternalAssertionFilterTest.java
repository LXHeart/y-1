package com.grassland.edge.internalassertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.edge.proxy.UpstreamResolver;
import com.grassland.identity.assertion.IdentityAssertionProperties;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.identity.assertion.TestAssertionHelper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** InternalAssertionFilter slice 测试：剥离伪造头、内部上游签发、fail-closed/匿名跳过。 */
class InternalAssertionFilterTest {

    private static final String SECRET = "filter-test-secret-32-chars!!";

    private final IdentityAssertionSigner signer = TestAssertionHelper.signer(
            "edge-bff", "user", "grassland-identity", SECRET, Duration.ofSeconds(5));
    private final IdentityAssertionProperties props =
            new IdentityAssertionProperties(
                    true, // enabled
                    60, // ttlSeconds
                    "grassland-identity", // audience
                    "X-Grassland-Identity", // headerName
                    5, // leewaySeconds
                    List.of("X-Grassland-Identity", "X-Grassland-Account-Id",
                            "X-Grassland-Active-Identity", "X-Grassland-Session-Token"), // internalHeaderDenylist
                    "edge-bff",
                    List.of(new IdentityAssertionProperties.KeyEntry(
                            "edge-user-test-v1", "edge-bff", "user", "grassland-identity", SECRET)),
                    List.of(),
                    new IdentityAssertionProperties.ReplayProtectionConfig(false)); // replayProtection

    /** 回显 filter 附加后的 X-Grassland-Identity 头（无则 "none"）。 */
    @RestController
    static class EchoController {
        @GetMapping("/**")
        Mono<Map<String, String>> echo(ServerHttpRequest req) {
            String value = req.getHeaders().getFirst("X-Grassland-Identity");
            return Mono.just(Map.of("assertion", value == null ? "none" : value));
        }
    }

    @Test
    void internalUpstream_attachesVerifiableAssertion() {
        SessionIdentityResolver resolver = resolverReturning(identity());
        UpstreamResolver upstream = upstreamInternal("/internal");
        var filter = new InternalAssertionFilter(resolver, signer, props, upstream);

        String body = client(filter)
                .get().uri("/internal/me")
                .cookie("y1.sid", "anything")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        String token = readToken(body);
        var decoded = signer.verify(token, null).orElseThrow();
        assertThat(decoded.organizationId()).isEqualTo("org-from-bff");
        assertThat(decoded.permissionTier()).isEqualTo("basic_publish");
    }

    @Test
    void reauthenticatedSession_assertionCarriesMfaProof() {
        // 回归防护：此前 buildAssertion 硬编码 authStrength=level1 / reauthenticatedAt=null，
        // 导致 trust 客服终审的 MFA 近期性校验恒失败（403）。现须从 identity_session 透传。
        java.time.Instant reauthAt = java.time.Instant.now().minusSeconds(30);
        SessionIdentityResolver resolver = resolverReturning(reauthenticatedIdentity(reauthAt));
        var filter = new InternalAssertionFilter(resolver, signer, props, upstreamInternal("/internal"));

        String token = readToken(client(filter)
                .get().uri("/internal/me").cookie("y1.sid", "anything")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());

        var decoded = signer.verify(token, null).orElseThrow();
        assertThat(decoded.authStrength()).isEqualTo("level2");
        assertThat(decoded.reauthenticatedAt()).isEqualTo(reauthAt);
    }

    @Test
    void plainLoginSession_hasNoMfaProof() {
        SessionIdentityResolver resolver = resolverReturning(identity());
        var filter = new InternalAssertionFilter(resolver, signer, props, upstreamInternal("/internal"));

        String token = readToken(client(filter)
                .get().uri("/internal/me").cookie("y1.sid", "anything")
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());

        var decoded = signer.verify(token, null).orElseThrow();
        assertThat(decoded.authStrength()).isEqualTo("level1");
        assertThat(decoded.reauthenticatedAt()).isNull();  // 未重认证 → 敏感操作应被拒
    }

    @Test
    void clientForgedHeader_isStrippedAndReplaced() {
        SessionIdentityResolver resolver = resolverReturning(identity());
        UpstreamResolver upstream = upstreamInternal("/internal");
        var filter = new InternalAssertionFilter(resolver, signer, props, upstream);

        String token = readToken(client(filter)
                .get().uri("/internal/me")
                .header("X-Grassland-Identity", "FORGED-BY-CLIENT")
                .cookie("y1.sid", "anything")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());

        assertThat(token).isNotEqualTo("FORGED-BY-CLIENT");
        assertThat(signer.verify(token, null)).isPresent();
    }

    @Test
    void failClosedRoute_noAssertionAttached() {
        SessionIdentityResolver resolver = resolverReturning(identity());
        UpstreamResolver upstream = mock(UpstreamResolver.class);
        when(upstream.isInternalUpstream(anyString(), anyString())).thenReturn(false);
        var filter = new InternalAssertionFilter(resolver, signer, props, upstream);

        String token = readToken(client(filter)
                .get().uri("/unknown/anything")
                .cookie("y1.sid", "anything")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());

        assertThat(token).isEqualTo("none");
    }

    @Test
    void anonymous_noAssertionAttached() {
        SessionIdentityResolver resolver = mock(SessionIdentityResolver.class);
        when(resolver.resolve(any())).thenReturn(Mono.empty());
        UpstreamResolver upstream = upstreamInternal("/internal");
        var filter = new InternalAssertionFilter(resolver, signer, props, upstream);

        String token = readToken(client(filter)
                .get().uri("/internal/me")
                .cookie("y1.sid", "anything")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());

        assertThat(token).isEqualTo("none");
    }

    // ---------- 任务书 #48：首登强制改密硬闸 ----------

    @Test
    void flaggedAccount_businessPath_is428WithoutAssertion() {
        SessionIdentityResolver resolver = resolverReturning(flaggedIdentity());
        var filter = new InternalAssertionFilter(resolver, signer, props, upstreamInternal("/internal"));

        client(filter)
                .get().uri("/internal/api/tasks")
                .cookie("y1.sid", "anything")
                .exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.PRECONDITION_REQUIRED)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error").isEqualTo("首次登录请先修改密码");
    }

    @Test
    void flaggedAccount_authEndpoints_areExemptFromGate() {
        SessionIdentityResolver resolver = resolverReturning(flaggedIdentity());
        var filter = new InternalAssertionFilter(resolver, signer, props, upstreamInternal("/api/auth"));

        String token = readToken(client(filter)
                .get().uri("/api/auth/me")
                .cookie("y1.sid", "anything")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());

        assertThat(signer.verify(token, null)).isPresent();
    }

    private static ResolvedIdentity flaggedIdentity() {
        // 管理员代建后未改密的首登态（mustChangePassword=true）
        return new ResolvedIdentity(
                "11111111-1111-1111-1111-111111111111", "user", "active", "merchant", "sid-flagged",
                "org-from-bff", "basic_publish", null, "level1", true);
    }

    private WebTestClient client(InternalAssertionFilter filter) {
        return WebTestClient.bindToController(new EchoController())
                .webFilter(filter)
                .configureClient()
                .baseUrl("http://localhost")
                .build();
    }

    private static SessionIdentityResolver resolverReturning(ResolvedIdentity identity) {
        SessionIdentityResolver resolver = mock(SessionIdentityResolver.class);
        when(resolver.resolve(any())).thenReturn(Mono.just(identity));
        return resolver;
    }

    private static UpstreamResolver upstreamInternal(String pathPrefix) {
        UpstreamResolver upstream = mock(UpstreamResolver.class);
        when(upstream.isInternalUpstream(anyString(), org.mockito.ArgumentMatchers.startsWith(pathPrefix))).thenReturn(true);
        // GL-P0-ASSERT-001：filter 现按 upstream 名解析目标 audience（identity→grassland-identity）选钥。
        when(upstream.resolveUpstreamName(anyString(), org.mockito.ArgumentMatchers.startsWith(pathPrefix))).thenReturn("identity");
        return upstream;
    }

    private static ResolvedIdentity identity() {
        // 普通登录：未重认证（reauthenticatedAt=null，authStrength=level1）
        return new ResolvedIdentity(
                "11111111-1111-1111-1111-111111111111", "user", "active", "merchant", "sid-1",
                "org-from-bff", "basic_publish", null, "level1", false);
    }

    /** 已 MFA 重认证的 session（V7）：断言须带上时刻与 level2，否则 trust 客服终审恒 403。 */
    private static ResolvedIdentity reauthenticatedIdentity(java.time.Instant reauthAt) {
        return new ResolvedIdentity(
                "11111111-1111-1111-1111-111111111111", "user", "active", "customer_service", "sid-1",
                null, null, reauthAt, "level2", false);
    }

    /** 控制器返回 JSON {@code {"assertion":"<token>"}}；解析出 token（或 "none"）。 */
    private static String readToken(String body) {
        if (body == null) {
            return "none";
        }
        int idx = body.indexOf("\"assertion\":\"");
        if (idx < 0) {
            return body.contains("\"none\"") ? "none" : body;
        }
        int start = idx + "\"assertion\":\"".length();
        int end = body.indexOf("\"", start);
        return end < 0 ? body : body.substring(start, end);
    }

}
