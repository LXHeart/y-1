package com.grassland.intelligence.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static com.grassland.identity.assertion.TestAssertionHelper.registerServiceKeyring;
import static com.grassland.identity.assertion.TestAssertionHelper.serviceSigner;
import static com.grassland.identity.assertion.TestAssertionHelper.userSigner;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.TextCompletionCommand;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import reactor.core.publisher.Mono;

/**
 * {@link VerificationController} 集成测试（草场 Slice 11 Verification Stage 3）。
 *
 * <p>镜像 {@code MediaControllerIT}：testcontainers PostgreSQL + MinIO（{@code object-storage.enabled=true}
 * → {@link VerificationController}/{@link VerificationAnalysisService} 装配），真三步上传 active 附件；
 * {@code @MockitoBean AiCapabilityAdapter} 控制视觉判决输出，验证 service gate / per-media 归一 / 聚合 /
 * 不符附件与存储缺失→inconclusive / AI 失败→inconclusive 且硬编码文案不泄露。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VerificationControllerIT {

    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    private static final GenericContainer<?> MINIO = new GenericContainer<>("minio/minio:latest")
            .withCommand("server", "/data")
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", "minioadmin")
            .withEnv("MINIO_ROOT_PASSWORD", "minioadmin")
            .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));

    private static final byte[] PNG =
            new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 1, 2, 3, 4};

    static {
        POSTGRES.start();
        MINIO.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        String dbUrl = "postgresql://" + POSTGRES.getUsername() + ":" + POSTGRES.getPassword()
                + "@" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/" + POSTGRES.getDatabaseName();
        String minioUrl = "http://" + MINIO.getHost() + ":" + MINIO.getMappedPort(9000);
        r.add("intelligence.datasource.from-database-url", () -> "true");
        r.add("DATABASE_URL", () -> dbUrl);
        r.add("management.server.port", () -> "0");
        r.add("identity-assertion.enabled", () -> "true");
        registerServiceKeyring(r, "intelligence");
        r.add("intelligence.outbox.enabled", () -> "false");
        r.add("ai.qwen.base-url", () -> "https://example.com");
        r.add("ai.qwen.api-key", () -> "sk-synthetic-intelligence-test-key");
        r.add("ai.qwen.model", () -> "qwen-plus");
        r.add("object-storage.enabled", () -> "true");
        r.add("object-storage.endpoint", () -> minioUrl);
        r.add("object-storage.public-base-url", () -> minioUrl);
        r.add("object-storage.access-key", () -> "minioadmin");
        r.add("object-storage.secret-key", () -> "minioadmin");
        r.add("object-storage.bucket", () -> "grassland-verification-it");
        r.add("object-storage.auto-create-bucket", () -> "true");
        r.add("media.cleanup-interval-ms", () -> "3600000");
        r.add("media.max-objects-per-owner", () -> "10");
        r.add("media.max-total-bytes-per-owner", () -> "1024");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private IdentityAssertionSigner signer;

    @Autowired
    private MediaReferenceRepository mediaRefs;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private AiCapabilityAdapter ai;

    @Test
    @DisplayName("analyze 缺断言 → 401")
    void analyzeRequiresAuth() {
        client().post().uri("/api/verification/analyze")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(analyzeBody(List.of(UUID.randomUUID().toString())))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("analyze 拒终端用户与已受信的非 marketplace 服务断言 → 403")
    void analyzeRejectsUserAndNonMarketplacePrincipal() {
        String org = "org-" + UUID.randomUUID();
        UUID mediaId = createActiveAttachment("acct-" + UUID.randomUUID(), org);

        client().post().uri("/api/verification/analyze")
                .header("X-Grassland-Identity", sign("acct-" + UUID.randomUUID(), org, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(analyzeBody(List.of(mediaId.toString())))
                .exchange().expectStatus().isEqualTo(403);

        client().post().uri("/api/verification/analyze")
                .header("X-Grassland-Identity", signService(org, "identity"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(analyzeBody(List.of(mediaId.toString())))
                .exchange().expectStatus().isEqualTo(403);
    }

    @Test
    @DisplayName("active 附件 + AI passed → 聚合 passed，保留 per-media 明细")
    void analyzeReturnsPassedForActiveAttachment() {
        String org = "org-" + UUID.randomUUID();
        UUID mediaId = createActiveAttachment("acct-" + UUID.randomUUID(), org);
        when(ai.completeText(any(TextCompletionCommand.class)))
                .thenReturn(Mono.just("{\"status\":\"passed\",\"detail\":\"真实发布截图\"}"));

        client().post().uri("/api/verification/analyze")
                .header("X-Grassland-Identity", signService(org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(analyzeBody(List.of(mediaId.toString())))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.status").isEqualTo("passed")
                .jsonPath("$.data.results[0].mediaId").isEqualTo(mediaId.toString())
                .jsonPath("$.data.results[0].status").isEqualTo("passed")
                .jsonPath("$.data.results[0].detail").isEqualTo("真实发布截图");
    }

    @Test
    @DisplayName("聚合 failed > passed：多附件任一 failed → 整体 failed")
    void aggregateFailedWinsOverPassed() {
        String org = "org-" + UUID.randomUUID();
        UUID failed = createActiveAttachment("acct-a-" + UUID.randomUUID(), org);
        UUID passed = createActiveAttachment("acct-b-" + UUID.randomUUID(), org);
        when(ai.completeText(any(TextCompletionCommand.class)))
                .thenReturn(Mono.just("{\"status\":\"failed\",\"detail\":\"造假\"}"))
                .thenReturn(Mono.just("{\"status\":\"passed\",\"detail\":\"真实\"}"));

        client().post().uri("/api/verification/analyze")
                .header("X-Grassland-Identity", signService(org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(analyzeBody(List.of(failed.toString(), passed.toString())))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("failed")
                .jsonPath("$.data.results[0].status").isEqualTo("failed")
                .jsonPath("$.data.results[1].status").isEqualTo("passed");
    }

    @Test
    @DisplayName("聚合 inconclusive > passed：存储缺失附件降级 inconclusive 且不拖垮已 passed 附件")
    void aggregateInconclusiveWhenStorageMissingAmongPassed() {
        String org = "org-" + UUID.randomUUID();
        UUID live = createActiveAttachment("acct-a-" + UUID.randomUUID(), org);
        UUID missingObject = insertAttachment("acct-b-" + UUID.randomUUID(), org,
                MediaStatus.ACTIVE, Instant.now().plusSeconds(3600));
        when(ai.completeText(any(TextCompletionCommand.class)))
                .thenReturn(Mono.just("{\"status\":\"passed\",\"detail\":\"真实\"}"));

        client().post().uri("/api/verification/analyze")
                .header("X-Grassland-Identity", signService(org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(analyzeBody(List.of(live.toString(), missingObject.toString())))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("inconclusive")
                .jsonPath("$.data.results[0].status").isEqualTo("passed")
                .jsonPath("$.data.results[1].status").isEqualTo("inconclusive")
                .jsonPath("$.data.results[1].detail").isEqualTo("附件不可用");
    }

    @Test
    @DisplayName("purpose 非 engagement_attachment / 已过期 / 不存在 → inconclusive 且不调 AI")
    void purposeExpiryAndMissingBecomeInconclusiveWithoutCallingAi() {
        String org = "org-" + UUID.randomUUID();
        UUID wrongPurpose = insertMedia("acct-a-" + UUID.randomUUID(), org,
                "user_upload", MediaStatus.ACTIVE, Instant.now().plusSeconds(3600));
        UUID expired = insertAttachment("acct-b-" + UUID.randomUUID(), org,
                MediaStatus.ACTIVE, Instant.now().minusSeconds(60));
        UUID absent = UUID.randomUUID();

        client().post().uri("/api/verification/analyze")
                .header("X-Grassland-Identity", signService(org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(analyzeBody(List.of(wrongPurpose.toString(), expired.toString(), absent.toString())))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("inconclusive")
                .jsonPath("$.data.results[0].detail").isEqualTo("附件不可用")
                .jsonPath("$.data.results[1].detail").isEqualTo("附件不可用")
                .jsonPath("$.data.results[2].detail").isEqualTo("附件不可用");

        verify(ai, never()).completeText(any(TextCompletionCommand.class));
    }

    @Test
    @DisplayName("AI 调用失败（上游超时）→ 该附件 inconclusive，硬编码「视频内容改编超时」文案不泄露")
    void aiFailureBecomesInconclusiveAndHardcodedTextDoesNotLeak() {
        String org = "org-" + UUID.randomUUID();
        UUID mediaId = createActiveAttachment("acct-" + UUID.randomUUID(), org);
        when(ai.completeText(any(TextCompletionCommand.class)))
                .thenReturn(Mono.error(new IntelligenceException(504, "视频内容改编超时，请稍后重试")));

        String body = client().post().uri("/api/verification/analyze")
                .header("X-Grassland-Identity", signService(org))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(analyzeBody(List.of(mediaId.toString())))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).contains("\"status\":\"inconclusive\"");
        assertThat(body).contains("AI 核验暂不可用");
        assertThat(body).doesNotContain("视频内容改编");
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private Map<String, Object> analyzeBody(List<String> mediaIds) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mediaIds", mediaIds);
        body.put("taskTitle", "任务标题");
        body.put("taskDescription", "任务要求描述");
        body.put("platform", "douyin");
        return body;
    }

    /** 三步上传一个 active 的 engagement_attachment 附件，返回 media id。 */
    @SuppressWarnings("unchecked")
    private UUID createActiveAttachment(String owner, String org) {
        Map<String, Object> envelope = client().post().uri("/api/media/upload-tickets")
                .header("X-Grassland-Identity", sign(owner, org, "recommender"))
                .bodyValue(Map.of(
                        "contentType", "image/png",
                        "purpose", "engagement_attachment",
                        "domainType", "application",
                        "domainId", "app-1",
                        "sizeBytes", PNG.length,
                        "ttlSeconds", 3600))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> ticket = (Map<String, Object>) envelope.get("data");
        UUID id = UUID.fromString((String) ticket.get("id"));
        put(URI.create((String) ticket.get("uploadUrl")), PNG);
        client().post().uri("/api/media/{id}/confirm", id)
                .header("X-Grassland-Identity", sign(owner, org, "recommender"))
                .exchange().expectStatus().isOk();
        return id;
    }

    /** 直接落一条 engagement_attachment media_reference 行（绕过上传/配额），用于构造存储缺失/已过期等边缘态。 */
    private UUID insertAttachment(String owner, String org, MediaStatus status, Instant expiresAt) {
        return insertMedia(owner, org, "engagement_attachment", status, expiresAt);
    }

    private UUID insertMedia(String owner, String org, String purpose, MediaStatus status, Instant expiresAt) {
        UUID id = UUID.randomUUID();
        MediaReference ref = new MediaReference(
                id, owner, org, purpose, "application", "app-1",
                "media/" + purpose + "/" + id, null, "image/png", PNG.length,
                MediaChecksums.sha256(PNG), "upload", status,
                Instant.now().minusSeconds(120), expiresAt, null);
        mediaRefs.insert(ref).block();
        return id;
    }

    private void put(URI uploadUrl, byte[] content) {
        try {
            HttpResponse<Void> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(uploadUrl)
                            .header("Content-Type", "image/png")
                            .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                            .build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(200);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private String sign(String accountId, String organizationId, String activeIdentityType) {
        Instant now = Instant.now();
        return userSigner("edge-bff", "grassland-intelligence").sign(new IdentityAssertion(
                accountId, activeIdentityType, "sid-" + accountId, organizationId, null,
                "cookie-session", "level1", null, "request", "trace",
                "grassland-intelligence", now, now.plusSeconds(60), null, null));
    }

    /** marketplace 服务断言（callerKind=service + principal=marketplace），镜像 ServiceAssertionIssuer.issueForOrg。 */
    private String signService(String organizationId, String principal) {
        Instant now = Instant.now();
        return serviceSigner(principal, "grassland-intelligence").sign(new IdentityAssertion(
                "service:" + principal, null, null, organizationId, null,
                "service", "internal", null, "request", "trace",
                "grassland-intelligence", now, now.plusSeconds(30),
                "service", principal));
    }

    private String signService(String organizationId) {
        return signService(organizationId, "marketplace");
    }
}
