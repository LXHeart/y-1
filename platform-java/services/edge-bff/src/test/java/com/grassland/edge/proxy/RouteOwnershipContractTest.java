package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/** 绑定真实 application.yml，使用不同 base path 锁定每个公开路由的实际上游归属。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RouteOwnershipContractTest {

    private static DisposableServer upstream;

    @LocalServerPort
    private int serverPort;

    @BeforeAll
    static void startUpstream() {
        upstream = HttpServer.create().port(0).handle((request, response) -> {
            String path = request.uri();
            String marker = path.substring(1, path.indexOf('/', 1));
            response.header("Content-Type", "application/json");
            return response.sendString(Mono.just("{\"upstream\":\"" + marker + "\"}")).then();
        }).bindNow(Duration.ofSeconds(30));
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.disposeNow();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        for (String name : new String[] {"identity", "marketplace", "finance", "trust", "intelligence"}) {
            registry.add("edge.upstreams." + name,
                    () -> "http://localhost:" + upstream.port() + "/" + name);
        }
        registry.add("edge.default-upstream", () -> EdgeRoutingProperties.FAIL_CLOSED);
        registry.add("management.server.port", () -> "0");
        registry.add("PUBLIC_BACKEND_ORIGIN", () -> "http://localhost:" + upstream.port());
        registry.add("BILIBILI_PROXY_TOKEN_SECRET", () -> "x".repeat(32));
        registry.add("DOUYIN_PROXY_TOKEN_SECRET", () -> "x".repeat(32));
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("routes")
    void routesToExpectedUpstream(HttpMethod method, String path, String expectedUpstream) {
        String body = WebTestClient.bindToServer()
                .baseUrl("http://localhost:" + serverPort)
                .build()
                .method(method).uri(path)
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).isEqualTo("{\"upstream\":\"" + expectedUpstream + "\"}");
    }

    private static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of(HttpMethod.POST, "/api/auth/login", "identity"),
                Arguments.of(HttpMethod.POST, "/api/auth/refresh", "identity"),
                Arguments.of(HttpMethod.POST, "/api/auth/revoke", "identity"),
                Arguments.of(HttpMethod.GET, "/api/organizations/org/stores", "identity"),
                Arguments.of(HttpMethod.GET, "/api/tasks/feed", "marketplace"),
                Arguments.of(HttpMethod.GET, "/api/finance/wallets/me", "finance"),
                Arguments.of(HttpMethod.GET, "/api/trust/disputes/dispute", "trust"),
                Arguments.of(HttpMethod.GET, "/api/media/media", "intelligence"),
                Arguments.of(HttpMethod.GET, "/api/settings/analysis", "intelligence"),
                Arguments.of(HttpMethod.GET, "/api/homepage/hot-items", "intelligence"),
                Arguments.of(HttpMethod.GET, "/api/bilibili/analysis-media/id", "intelligence"),
                Arguments.of(HttpMethod.GET, "/api/douyin/audio/token", "intelligence"),
                Arguments.of(HttpMethod.GET, "/api/douyin/session", "intelligence"),
                Arguments.of(HttpMethod.POST, "/api/video-recreation/adapt-content", "intelligence"),
                Arguments.of(HttpMethod.GET, "/api/admin/users", "identity"),
                Arguments.of(HttpMethod.GET, "/api/admin/reputation-config", "marketplace"),
                Arguments.of(HttpMethod.GET, "/api/admin/trust/judges", "trust"),
                Arguments.of(HttpMethod.GET, "/api/admin/trust/evidence-access-audits", "trust"),
                Arguments.of(HttpMethod.GET, "/api/douyin/hot-items", "intelligence"));
    }
}
