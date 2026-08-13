package com.grassland.marketplace.workflow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.identity.assertion.TestAssertionHelper;
import com.grassland.marketplace.security.ServiceAssertionIssuer;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * {@link IntelligenceVerificationClient}（草场 Slice 11 Verification Stage 4）：经服务断言中转调
 * intelligence 的履约 AI 视觉核验端点。
 *
 * <p>用 WireMock（intelligence 客户端测试的 house style）验证状态码映射：
 * 200→解析 {@code {success,data}} 信封的 {@link IntelligenceVerificationClient.VerificationAnalysis}；
 * 其余（含 5xx）→{@link IntelligenceVerificationException}；并断言每请求 POST 携带现签的
 * {@code X-Grassland-Identity} 服务断言 + mediaIds 进 body。
 */
class IntelligenceVerificationClientTest {

    private static final String AUDIENCE = "grassland-intelligence";
    private static final String ORG = "11111111-1111-1111-1111-111111111111";

    private WireMockServer wireMock;
    private IntelligenceVerificationClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        IdentityAssertionSigner signer = TestAssertionHelper.serviceSigner("marketplace", AUDIENCE);
        ServiceAssertionIssuer issuer = new ServiceAssertionIssuer(signer, AUDIENCE);
        client = new IntelligenceVerificationClient(issuer, wireMock.baseUrl(), "X-Grassland-Identity");
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    @DisplayName("analyze 200 → 解析 VerificationAnalysis{status,results}；请求带服务断言头 + mediaIds 进 body")
    void analyzeSuccess() {
        UUID m1 = UUID.randomUUID();
        UUID m2 = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/api/verification/analyze"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"data\":{"
                                + "\"status\":\"failed\","
                                + "\"results\":["
                                + "{\"mediaId\":\"" + m1 + "\",\"status\":\"passed\",\"detail\":\"真实\"},"
                                + "{\"mediaId\":\"" + m2 + "\",\"status\":\"failed\",\"detail\":\"张冠李戴\"}"
                                + "]}}")));

        StepVerifier.create(client.analyze(ORG, List.of(m1, m2), "任务", "要求", "douyin"))
                .assertNext(a -> {
                    assertThat(a.status()).isEqualTo("failed");
                    assertThat(a.results()).hasSize(2);
                    assertThat(a.results().get(0).mediaId()).isEqualTo(m1);
                    assertThat(a.results().get(0).status()).isEqualTo("passed");
                    assertThat(a.results().get(1).detail()).isEqualTo("张冠李戴");
                })
                .verifyComplete();

        wireMock.verify(postRequestedFor(urlEqualTo("/api/verification/analyze"))
                .withHeader("X-Grassland-Identity", matching(".+"))
                .withRequestBody(containing(m1.toString()))
                .withRequestBody(containing(m2.toString()))
                .withRequestBody(containing("\"taskTitle\":\"任务\"")));
    }

    @Test
    @DisplayName("analyze 空 taskDescription/platform → 不下发可选字段（避免 intelligence optionalString 对 null 报 400）")
    void analyzeOmitsBlankOptionals() {
        UUID m1 = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/api/verification/analyze"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"data\":{\"status\":\"passed\",\"results\":[]}}")));

        StepVerifier.create(client.analyze(ORG, List.of(m1), "任务", null, "  "))
                .assertNext(a -> assertThat(a.status()).isEqualTo("passed"))
                .verifyComplete();

        wireMock.verify(postRequestedFor(urlEqualTo("/api/verification/analyze"))
                .withRequestBody(containing("\"taskTitle\":\"任务\""))
                .withRequestBody(containing(m1.toString())));
    }

    @Test
    @DisplayName("analyze 5xx → IntelligenceVerificationException（编排降级为 ai_visual inconclusive，不映射给用户）")
    void analyzeServerErrorThrows() {
        UUID m1 = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/api/verification/analyze"))
                .willReturn(aResponse().withStatus(503).withBody("intelligence down")));

        StepVerifier.create(client.analyze(ORG, List.of(m1), "任务", null, null))
                .verifyError(IntelligenceVerificationException.class);
    }

    @Test
    @DisplayName("analyze 4xx（如 intelligence 校验失败）→ IntelligenceVerificationException")
    void analyzeClientErrorThrows() {
        UUID m1 = UUID.randomUUID();
        wireMock.stubFor(post(urlEqualTo("/api/verification/analyze"))
                .willReturn(aResponse().withStatus(400).withBody("请求参数无效")));

        StepVerifier.create(client.analyze(ORG, List.of(m1), "任务", null, null))
                .verifyError(IntelligenceVerificationException.class);
    }
}
