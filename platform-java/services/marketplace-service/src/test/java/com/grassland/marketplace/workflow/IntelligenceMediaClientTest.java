package com.grassland.marketplace.workflow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.marketplace.security.ServiceAssertionIssuer;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * {@link IntelligenceMediaClient}（草场 Slice 11 Stage 2）：经服务断言中转调 intelligence 两个 service-only 端点。
 *
 * <p>用 WireMock（intelligence 客户端测试的 house style）验证状态码映射：
 * 200→解析 {@code {success,data}} 信封；404（media 不存在/非 engagement_attachment/非活跃/过期）→empty；
 * 其余→{@link IntelligenceMediaException}；并断言每请求带现签的 {@code X-Grassland-Identity} 服务断言。
 */
class IntelligenceMediaClientTest {

    private static final String SECRET = "test-secret-32-chars-min!!!";
    private static final String AUDIENCE = "grassland-internal";
    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private WireMockServer wireMock;
    private IntelligenceMediaClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        IdentityAssertionSigner signer =
                new IdentityAssertionSigner(SECRET.getBytes(), AUDIENCE, Duration.ofSeconds(5));
        ServiceAssertionIssuer issuer = new ServiceAssertionIssuer(signer, AUDIENCE);
        client = new IntelligenceMediaClient(issuer, wireMock.baseUrl(), "X-Grassland-Identity");
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("metadata 200 → 解析 MediaMetadata；请求带服务断言头")
    void metadataSuccess() {
        UUID mediaId = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/api/media/" + mediaId + "/metadata"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"data\":{"
                                + "\"id\":\"" + mediaId + "\","
                                + "\"ownerAccountId\":\"" + owner + "\","
                                + "\"purpose\":\"engagement_attachment\","
                                + "\"status\":\"active\","
                                + "\"mimeType\":\"image/png\","
                                + "\"sizeBytes\":1234,"
                                + "\"expiresAt\":\"2026-12-31T00:00:00Z\"}}")));

        StepVerifier.create(client.metadata(ORG, mediaId))
                .assertNext(m -> {
                    assertThat(m.id()).isEqualTo(mediaId);
                    assertThat(m.ownerAccountId()).isEqualTo(owner.toString());
                    assertThat(m.purpose()).isEqualTo("engagement_attachment");
                    assertThat(m.status()).isEqualTo("active");
                    assertThat(m.mimeType()).isEqualTo("image/png");
                    assertThat(m.sizeBytes()).isEqualTo(1234L);
                })
                .verifyComplete();

        wireMock.verify(getRequestedFor(urlEqualTo("/api/media/" + mediaId + "/metadata"))
                .withHeader("X-Grassland-Identity", matching(".+")));
    }

    @Test
    @DisplayName("metadata 404 → Mono.empty()（media 不可用，调用方据此返回 404）")
    void metadataNotFoundIsEmpty() {
        UUID mediaId = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/api/media/" + mediaId + "/metadata"))
                .willReturn(aResponse().withStatus(404)));

        StepVerifier.create(client.metadata(ORG, mediaId)).verifyComplete();
    }

    @Test
    @DisplayName("metadata 5xx → IntelligenceMediaException（非 404，由 controller 映射 5xx）")
    void metadataServerErrorThrows() {
        UUID mediaId = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/api/media/" + mediaId + "/metadata"))
                .willReturn(aResponse().withStatus(503).withBody("down")));

        StepVerifier.create(client.metadata(ORG, mediaId))
                .verifyError(IntelligenceMediaException.class);
    }

    @Test
    @DisplayName("download-url 200 → MediaDownload（downloadUrl/expiresAt）")
    void downloadUrlSuccess() {
        UUID mediaId = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/api/media/" + mediaId + "/download-url"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"data\":{"
                                + "\"downloadUrl\":\"https://minio.local/media/x?signature=abc\","
                                + "\"expiresAt\":\"2026-12-31T00:00:00Z\"}}")));

        StepVerifier.create(client.downloadUrl(ORG, mediaId))
                .assertNext(dl -> assertThat(dl.downloadUrl().toString())
                        .isEqualTo("https://minio.local/media/x?signature=abc"))
                .verifyComplete();
    }

    @Test
    @DisplayName("download-url 404 → Mono.empty()（media 不可用）")
    void downloadUrlNotFoundIsEmpty() {
        UUID mediaId = UUID.randomUUID();
        wireMock.stubFor(get(urlEqualTo("/api/media/" + mediaId + "/download-url"))
                .willReturn(aResponse().withStatus(404)));

        StepVerifier.create(client.downloadUrl(ORG, mediaId)).verifyComplete();
    }
}
