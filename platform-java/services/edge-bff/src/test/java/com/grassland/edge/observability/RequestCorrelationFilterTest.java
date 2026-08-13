package com.grassland.edge.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

class RequestCorrelationFilterTest {
    @RestController
    static class EchoController {
        @GetMapping("/**")
        Mono<Map<String, String>> echo(ServerHttpRequest request) {
            return Mono.just(Map.of(
                    "requestId", request.getHeaders().getFirst(RequestCorrelationFilter.REQUEST_ID),
                    "traceId", request.getHeaders().getFirst(RequestCorrelationFilter.TRACE_ID),
                    "correlationId", request.getHeaders().getFirst(RequestCorrelationFilter.CORRELATION_ID)));
        }
    }

    @Test
    void missingHeadersAreGeneratedAndReturned() {
        WebTestClient client = client();
        var result = client.get().uri("/health").exchange().expectStatus().isOk();
        String requestId = result.returnResult(String.class).getResponseHeaders()
                .getFirst(RequestCorrelationFilter.REQUEST_ID);
        assertThat(requestId).matches("[0-9a-f-]{36}");
        assertThat(result.returnResult(String.class).getResponseHeaders()
                .getFirst(RequestCorrelationFilter.TRACE_ID)).matches("[0-9a-f]{32}");
    }

    @Test
    void validHeadersAreNormalizedAndPropagated() {
        String requestId = "550e8400-e29b-41d4-a716-446655440000";
        String traceId = "0123456789abcdef0123456789abcdef";
        var response = client().get().uri("/health")
                .header("X-Request-Id", requestId.toUpperCase())
                .header("X-Trace-Id", traceId.toUpperCase())
                .header("X-Correlation-Id", "checkout:42")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        assertThat(response.getResponseHeaders().getFirst("X-Request-Id")).isEqualTo(requestId);
        assertThat(response.getResponseHeaders().getFirst("X-Trace-Id")).isEqualTo(traceId);
        assertThat(response.getResponseHeaders().getFirst("X-Correlation-Id")).isEqualTo("checkout:42");
        assertThat(response.getResponseBody()).contains(requestId, traceId, "checkout:42");
    }

    @Test
    void malformedHeadersAreReplaced() {
        var response = client().get().uri("/health")
                .header("X-Request-Id", "attacker\nvalue")
                .header("X-Trace-Id", "not-a-trace")
                .header("X-Correlation-Id", "bad value with spaces")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        assertThat(response.getResponseHeaders().getFirst("X-Request-Id")).doesNotContain("attacker");
        assertThat(response.getResponseHeaders().getFirst("X-Trace-Id")).matches("[0-9a-f]{32}");
        assertThat(response.getResponseHeaders().getFirst("X-Correlation-Id")).matches("[0-9a-f-]{36}");
    }

    @Test
    void zeroTraceIdIsReplaced() {
        var response = client().get().uri("/health")
                .header("X-Trace-Id", "00000000000000000000000000000000")
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult();
        assertThat(response.getResponseHeaders().getFirst("X-Trace-Id"))
                .matches("(?!00000000000000000000000000000000)[0-9a-f]{32}");
    }

    private static WebTestClient client() {
        return WebTestClient.bindToController(new EchoController())
                .webFilter(new RequestCorrelationFilter()).configureClient()
                .baseUrl("http://localhost").build();
    }
}
