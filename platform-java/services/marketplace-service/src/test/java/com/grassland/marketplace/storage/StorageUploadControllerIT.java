package com.grassland.marketplace.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.storage.UploadTicket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 用 testcontainers MinIO 端到端验证 marketplace 三步上传 HTTP 流。 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "spring.temporal.test-server.enabled=true")
@Testcontainers
class StorageUploadControllerIT {

    @Container
    static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:latest")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    @DynamicPropertySource
    static void storageProps(DynamicPropertyRegistry registry) {
        String url = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        registry.add("object-storage.endpoint", () -> url);
        registry.add("object-storage.public-base-url", () -> url);
    }

    @LocalServerPort
    private int port;

    private WebTestClient webClient;

    @BeforeEach
    void setUp() {
        webClient = WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void applyDirectUploadConfirm_roundTrip() throws Exception {
        String scope = "marketplace/tasks";

        UploadTicket ticket = webClient.post().uri("/storage/uploads")
                .bodyValue(Map.of("contentType", "text/plain", "scope", scope))
                .exchange()
                .expectStatus().isOk()
                .expectBody(UploadTicket.class)
                .returnResult().getResponseBody();
        assertThat(ticket).isNotNull();
        assertThat(ticket.method()).isEqualTo("PUT");
        assertThat(ticket.objectKey()).startsWith(scope + "/");

        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<Void> put = http.send(
                HttpRequest.newBuilder(ticket.uploadUrl())
                        .header("Content-Type", "text/plain")
                        .PUT(HttpRequest.BodyPublishers.ofString("hello-storage"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);

        webClient.post().uri("/storage/uploads/confirm")
                .bodyValue(Map.of("objectKey", ticket.objectKey()))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.contentLength").isEqualTo(13)
                .jsonPath("$.contentType").isEqualTo("text/plain");

        webClient.post().uri("/storage/uploads/confirm")
                .bodyValue(Map.of("objectKey", "missing/" + UUID.randomUUID()))
                .exchange()
                .expectStatus().isNotFound();
    }
}
