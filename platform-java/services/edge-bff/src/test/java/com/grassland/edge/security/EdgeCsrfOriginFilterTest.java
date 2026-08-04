package com.grassland.edge.security;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

class EdgeCsrfOriginFilterTest {

    @RestController
    static class EchoController {
        @GetMapping("/api/read")
        Mono<Map<String, Boolean>> read() {
            return Mono.just(Map.of("success", true));
        }

        @PostMapping("/api/write")
        Mono<Map<String, Boolean>> write() {
            return Mono.just(Map.of("success", true));
        }
    }

    @Test
    void allowsSameOriginWriteBehindTrustedIngress() {
        client(true, List.of())
                .post().uri("/api/write")
                .header("Host", "app.example.com")
                .header("X-Forwarded-Proto", "https")
                .header("Origin", "https://app.example.com")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void allowsConfiguredFrontendOrigin() {
        client(true, List.of("https://console.example.com"))
                .post().uri("/api/write")
                .header("Origin", "https://console.example.com")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void rejectsCrossOriginWrite() {
        client(true, List.of("https://app.example.com"))
                .post().uri("/api/write")
                .header("Host", "app.example.com")
                .header("X-Forwarded-Proto", "https")
                .header("Origin", "https://evil.example.com")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error").isEqualTo("请求来源不被允许。");
    }

    @Test
    void usesRefererWhenOriginIsAbsent() {
        client(true, List.of("https://app.example.com"))
                .post().uri("/api/write")
                .header("Referer", "https://app.example.com/workbench")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void rejectsOpaqueOrMalformedBrowserOrigins() {
        client(true, List.of())
                .post().uri("/api/write")
                .header("Origin", "null")
                .exchange()
                .expectStatus().isForbidden();

        client(true, List.of())
                .post().uri("/api/write")
                .header("Origin", "not-a-valid-origin")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void allowsNonBrowserCallsAndSafeMethods() {
        client(true, List.of())
                .post().uri("/api/write")
                .exchange()
                .expectStatus().isOk();

        client(true, List.of())
                .get().uri("/api/read")
                .header("Origin", "https://evil.example.com")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void canBeDisabledForEmergencyCompatibilityRollback() {
        client(false, List.of())
                .post().uri("/api/write")
                .header("Origin", "https://evil.example.com")
                .exchange()
                .expectStatus().isOk();
    }

    private static WebTestClient client(boolean enabled, List<String> allowedOrigins) {
        return WebTestClient.bindToController(new EchoController())
                .webFilter(new EdgeCsrfOriginFilter(enabled, allowedOrigins))
                .build();
    }
}
