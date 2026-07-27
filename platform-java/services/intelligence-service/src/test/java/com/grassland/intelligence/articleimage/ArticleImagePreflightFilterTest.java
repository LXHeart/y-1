package com.grassland.intelligence.articleimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.security.IntelligenceCallerResolver;
import com.grassland.intelligence.security.IntelligenceCallerResolver.Caller;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

class ArticleImagePreflightFilterTest {

    @Test
    @DisplayName("anonymous multipart is rejected before the filter chain can read the body")
    void rejectsAnonymousBeforeBody() {
        IntelligenceCallerResolver callers = mock(IntelligenceCallerResolver.class);
        when(callers.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.error(new com.grassland.intelligence.security.IntelligenceException(401, "未登录")));
        ArticleImagePreflightFilter filter = new ArticleImagePreflightFilter(callers,
                Clock.fixed(Instant.parse("2026-07-28T10:00:00Z"), ZoneOffset.UTC));
        WebFilterChain chain = mock(WebFilterChain.class);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/article-generation/generate-image")
                        .contentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
                        .body("large-body"));

        filter.filter(exchange, chain).block(Duration.ofSeconds(1));

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(chain);
    }

    @Test
    @DisplayName("three image endpoints share one per-account 10 per minute bucket")
    void limitsAllImagePostsTogether() {
        IntelligenceCallerResolver callers = mock(IntelligenceCallerResolver.class);
        when(callers.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(new Caller("account-1", null, "sid", null, null)));
        ArticleImagePreflightFilter filter = new ArticleImagePreflightFilter(callers,
                Clock.fixed(Instant.parse("2026-07-28T10:00:00Z"), ZoneOffset.UTC));
        WebFilterChain chain = exchange -> Mono.empty();

        for (int index = 0; index < 10; index++) {
            MockServerWebExchange exchange = exchange(index % 2 == 0
                    ? "/api/article-generation/image-recommendations"
                    : "/api/article-generation/search-images");
            filter.filter(exchange, chain).block(Duration.ofSeconds(1));
            assertThat(exchange.getResponse().getStatusCode()).isNull();
            assertThat(exchange.getResponse().getHeaders().getFirst("RateLimit-Limit")).isEqualTo("10");
        }
        MockServerWebExchange eleventh = exchange("/api/article-generation/generate-image");
        filter.filter(eleventh, chain).block(Duration.ofSeconds(1));

        assertThat(eleventh.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(eleventh.getResponse().getHeaders().getFirst("RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    @DisplayName("authenticated downstream IntelligenceException is not rewritten as 401")
    void propagatesDownstreamFailures() {
        IntelligenceCallerResolver callers = mock(IntelligenceCallerResolver.class);
        when(callers.resolve(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(new Caller("account-1", null, "sid", null, null)));
        ArticleImagePreflightFilter filter = new ArticleImagePreflightFilter(callers, Clock.systemUTC());
        WebFilterChain chain = exchange -> Mono.error(
                new com.grassland.intelligence.security.IntelligenceException(502, "provider down"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        filter.filter(exchange("/api/article-generation/search-images"), chain)
                                .block(Duration.ofSeconds(1)))
                .isInstanceOfSatisfying(
                        com.grassland.intelligence.security.IntelligenceException.class,
                        error -> assertThat(error.status()).isEqualTo(502));
    }

    @Test
    @DisplayName("public generated-image GET bypasses auth and rate limiting")
    void generatedImageGetBypassesFilter() {
        IntelligenceCallerResolver callers = mock(IntelligenceCallerResolver.class);
        ArticleImagePreflightFilter filter = new ArticleImagePreflightFilter(callers, Clock.systemUTC());
        WebFilterChain chain = exchange -> Mono.empty();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/article-generation/generated-images/id").build());

        filter.filter(exchange, chain).block(Duration.ofSeconds(1));

        verifyNoInteractions(callers);
    }

    private static MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).body("{}"));
    }
}
