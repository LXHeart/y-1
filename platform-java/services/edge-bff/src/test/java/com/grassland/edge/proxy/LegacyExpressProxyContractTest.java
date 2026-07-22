package com.grassland.edge.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LegacyExpressProxyContractTest {
    private static final Map<String, AtomicReference<CapturedRequest>> CAPTURED = new ConcurrentHashMap<>();
    private static DisposableServer UPSTREAM;

    @LocalServerPort
    private int serverPort;

    private WebTestClient client;

    @BeforeAll
    static void startUpstream() {
        HttpServer server = HttpServer.create().port(0).handle(LegacyExpressProxyContractTest::route);
        UPSTREAM = server.bindNow(Duration.ofSeconds(30));
    }

    @AfterAll
    static void stopUpstream() {
        if (UPSTREAM != null) {
            UPSTREAM.disposeNow();
        }
    }

    @DynamicPropertySource
    static void configureUpstream(DynamicPropertyRegistry registry) {
        registry.add("edge.upstreams.legacy", () -> "http://localhost:" + UPSTREAM.port());
        registry.add("edge.upstreams.identity", () -> "http://localhost:" + UPSTREAM.port());
        registry.add("edge.default-upstream", () -> "legacy");
        registry.add("management.server.port", () -> "0");
    }

    @org.junit.jupiter.api.BeforeEach
    void setUpClient() {
        client = WebTestClient.bindToServer()
            .baseUrl("http://localhost:" + serverPort)
            .responseTimeout(Duration.ofSeconds(30))
            .build();
    }

    @Test
    void preservesJsonStatusQueryCookiesAndRateLimitHeaders() {
        client.get()
            .uri("/api/example?tag=a&tag=b&empty=")
            .header(HttpHeaders.COOKIE, "y1.sid=synthetic")
            .exchange()
            .expectStatus().isCreated()
            .expectHeader().contentType(MediaType.APPLICATION_JSON)
            .expectHeader().values(HttpHeaders.SET_COOKIE, values -> assertThat(values).containsExactly(
                "a=1; Path=/",
                "b=2; Path=/"
            ))
            .expectHeader().valueEquals("RateLimit-Limit", "30")
            .expectBody(String.class).isEqualTo("{\"success\":true,\"data\":{\"id\":\"synthetic\"}}");

        CapturedRequest captured = CAPTURED.get("GET:/api/example").get();
        assertThat(captured.uri()).isEqualTo("/api/example?tag=a&tag=b&empty=");
        assertThat(captured.header(HttpHeaders.COOKIE)).isEqualTo("y1.sid=synthetic");
    }

    @Test
    void preservesMultipartBytesAndBoundary() {
        byte[] body = (
            "--synthetic-boundary\r\n" +
            "Content-Disposition: form-data; name=\"images\"; filename=\"sample.bin\"\r\n" +
            "Content-Type: application/octet-stream\r\n\r\n" +
            "binary-content \r\n" +
            "--synthetic-boundary--\r\n"
        ).getBytes(StandardCharsets.UTF_8);

        client.post()
            .uri("/api/image-analysis/analyze")
            .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data; boundary=synthetic-boundary")
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk();

        CapturedRequest captured = CAPTURED.get("POST:/api/image-analysis/analyze").get();
        assertThat(captured.header(HttpHeaders.CONTENT_TYPE))
            .isEqualTo("multipart/form-data; boundary=synthetic-boundary");
        assertThat(captured.body()).isEqualTo(body);
    }

    @Test
    void preservesRangeStatusAndDownloadHeaders() {
        byte[] body = "0123456789".getBytes(StandardCharsets.UTF_8);
        client.get()
            .uri("/api/douyin/proxy/synthetic")
            .header(HttpHeaders.RANGE, "bytes=10-19")
            .exchange()
            .expectStatus().isEqualTo(206)
            .expectHeader().valueEquals(HttpHeaders.CONTENT_RANGE, "bytes 10-19/100")
            .expectHeader().valueEquals(HttpHeaders.ACCEPT_RANGES, "bytes")
            .expectHeader().valueEquals(HttpHeaders.CONTENT_LENGTH, "10")
            .expectHeader().valueEquals(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''sample.mp4")
            .expectBody(byte[].class).isEqualTo(body);
    }

    @Test
    void streamsSseWithoutRewritingFrames() {
        client.post()
            .uri("/api/article-generation/outline")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("{\"topic\":\"synthetic\"}")
            .exchange()
            .expectStatus().isOk()
            .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
            .expectHeader().valueEquals("X-Accel-Buffering", "no")
            .expectBody(String.class)
            .isEqualTo("data: {\"content\":\"first\"}\n\ndata: [DONE]\n\n");
    }

    @Test
    void doesNotProxyUnknownPaths() {
        client.get().uri("/not-proxied").exchange().expectStatus().isNotFound();
        assertThat(CAPTURED).doesNotContainKey("GET:/not-proxied");
    }

    private static Mono<Void> route(HttpServerRequest request, HttpServerResponse response) {
        String key = request.method().name() + ":" + pathOnly(request);
        if ("/api/example".equals(pathOnly(request))) {
            return captureAndRespond(request, response, key, r -> {
                r.status(201);
                r.responseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                r.responseHeaders().add(HttpHeaders.SET_COOKIE, "a=1; Path=/");
                r.responseHeaders().add(HttpHeaders.SET_COOKIE, "b=2; Path=/");
                r.responseHeaders().add("RateLimit-Limit", "30");
                return r.sendString(Mono.just("{\"success\":true,\"data\":{\"id\":\"synthetic\"}}")).then();
            });
        }
        if ("/api/image-analysis/analyze".equals(pathOnly(request))) {
            return captureAndRespond(request, response, key, r -> {
                r.responseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                return r.sendString(Mono.just("{\"success\":true}")).then();
            });
        }
        if ("/api/douyin/proxy/synthetic".equals(pathOnly(request))) {
            return captureAndRespond(request, response, key, r -> {
                r.status(206);
                r.responseHeaders().add(HttpHeaders.CONTENT_TYPE, "video/mp4");
                r.responseHeaders().add(HttpHeaders.ACCEPT_RANGES, "bytes");
                r.responseHeaders().add(HttpHeaders.CONTENT_RANGE, "bytes 10-19/100");
                r.responseHeaders().add(HttpHeaders.CONTENT_LENGTH, "10");
                r.responseHeaders().add(HttpHeaders.CONTENT_DISPOSITION, "inline; filename*=UTF-8''sample.mp4");
                return r.sendString(Mono.just("0123456789")).then();
            });
        }
        if ("/api/article-generation/outline".equals(pathOnly(request))) {
            return captureAndRespond(request, response, key, r -> {
                r.responseHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);
                r.responseHeaders().add(HttpHeaders.CACHE_CONTROL, "no-cache");
                r.responseHeaders().add("X-Accel-Buffering", "no");
                return r.sendString(Mono.just("data: {\"content\":\"first\"}\n\ndata: [DONE]\n\n")).then();
            });
        }
        return response.status(404).send();
    }

    private static Mono<Void> captureAndRespond(
        HttpServerRequest request,
        HttpServerResponse response,
        String key,
        Function<HttpServerResponse, Mono<Void>> responder
    ) {
        HttpHeaders headers = new HttpHeaders();
        request.requestHeaders().forEach(e -> headers.add(e.getKey(), e.getValue()));
        return request.receive()
            .aggregate()
            .map(buf -> {
                byte[] body = new byte[buf.readableBytes()];
                buf.readBytes(body);
                buf.release();
                return body;
            })
            .defaultIfEmpty(new byte[0])
            .doOnNext(body -> CAPTURED.put(key,
                new AtomicReference<>(new CapturedRequest(request.uri(), headers, body))))
            .then(responder.apply(response));
    }

    private static String pathOnly(HttpServerRequest request) {
        String uri = request.uri();
        int query = uri.indexOf('?');
        return query < 0 ? uri : uri.substring(0, query);
    }

    private record CapturedRequest(String uri, HttpHeaders headers, byte[] body) {
        String header(String name) {
            return headers.getFirst(name);
        }
    }
}
