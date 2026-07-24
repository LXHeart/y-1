package com.grassland.edge.internalassertion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.edge.proxy.UpstreamResolver;
import com.grassland.identity.assertion.IdentityAssertionProperties;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** InternalAssertionFilter slice 测试：剥离伪造头、内部上游签发、legacy/匿名跳过。 */
class InternalAssertionFilterTest {

    private static final String SECRET = "filter-test-secret-32-chars!!";

    private final IdentityAssertionSigner signer =
            new IdentityAssertionSigner(SECRET.getBytes(), "grassland-internal", Duration.ofSeconds(5));
    private final IdentityAssertionProperties props =
            new IdentityAssertionProperties(true, SECRET, 60, "grassland-internal", "X-Grassland-Identity", 5, null);

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
    void legacyUpstream_noAssertionAttached() {
        SessionIdentityResolver resolver = resolverReturning(identity());
        UpstreamResolver upstream = mock(UpstreamResolver.class);
        when(upstream.isInternalUpstream(anyString(), anyString())).thenReturn(false);
        var filter = new InternalAssertionFilter(resolver, signer, props, upstream);

        String token = readToken(client(filter)
                .get().uri("/legacy/anything")
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
        return upstream;
    }

    private static ResolvedIdentity identity() {
        return new ResolvedIdentity(
                "11111111-1111-1111-1111-111111111111", "user", "active", "merchant", "sid-1",
                "org-from-bff", "basic_publish");
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
