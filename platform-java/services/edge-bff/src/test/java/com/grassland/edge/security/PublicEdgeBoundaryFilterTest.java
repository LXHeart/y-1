package com.grassland.edge.security;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

class PublicEdgeBoundaryFilterTest {

    @RestController
    static class EchoController {
        @RequestMapping("/**")
        Mono<Map<String, String>> echo(ServerHttpRequest request) {
            Map<String, String> headers = new LinkedHashMap<>();
            request.getHeaders().forEach((name, values) -> {
                if (name.toLowerCase().startsWith("x-grassland-")
                        || "authorization".equalsIgnoreCase(name)) {
                    headers.put(name, values.getFirst());
                }
            });
            return Mono.just(headers);
        }
    }

    @Test
    void stripsAllClientSuppliedInternalIdentityHeaders() {
        client().get().uri("/api/example")
                .header("X-Grassland-Identity", "forged")
                .header("X-Grassland-Account-Id", "forged-account")
                .header("X-Grassland-Active-Identity", "merchant")
                .header("X-Grassland-Session-Token", "forged-session")
                .exchange()
                .expectStatus().isOk()
                .expectBody().json("{}");
    }

    @Test
    void preservesAuthorizationForTheDedicatedAccessTokenFilter() {
        client().get().uri("/api/tasks")
                .header("Authorization", "Bearer access-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.Authorization").isEqualTo("Bearer access-token");

        client().post().uri("/api/auth/refresh")
                .header("Authorization", "Bearer refresh-token")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.Authorization").isEqualTo("Bearer refresh-token");
    }

    @Test
    void rejectsEveryPublicSpellingOfInternalPaths() {
        for (String path : new String[] {
                "/internal", "/internal/credits", "/api/internal", "/api/internal/credits"
        }) {
            client().get().uri(path).exchange().expectStatus().isNotFound();
        }
    }

    private static WebTestClient client() {
        return WebTestClient.bindToController(new EchoController())
                .webFilter(new PublicEdgeBoundaryFilter())
                .build();
    }
}
