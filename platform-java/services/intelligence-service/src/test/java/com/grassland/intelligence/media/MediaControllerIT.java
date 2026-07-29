package com.grassland.intelligence.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.identity.assertion.IdentityAssertionSigner;
import com.grassland.storage.ObjectStorageAdapter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** testcontainers PostgreSQL + MinIO 端到端验证 media-reference 三步上传、签名读、归属与删除审计。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MediaControllerIT {

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
        r.add("identity-assertion.secret", () -> "test-secret-32-chars-min!!!");
        r.add("identity-assertion.audience", () -> "grassland-internal");
        r.add("intelligence.outbox.enabled", () -> "false");
        r.add("ai.qwen.base-url", () -> "https://example.com");
        r.add("ai.qwen.api-key", () -> "sk-test");
        r.add("object-storage.enabled", () -> "true");
        r.add("object-storage.endpoint", () -> minioUrl);
        r.add("object-storage.public-base-url", () -> minioUrl);
        r.add("object-storage.access-key", () -> "minioadmin");
        r.add("object-storage.secret-key", () -> "minioadmin");
        r.add("object-storage.bucket", () -> "grassland-media-it");
        r.add("object-storage.auto-create-bucket", () -> "true");
        r.add("media.cleanup-interval-ms", () -> "3600000");
        r.add("media.max-objects-per-owner", () -> "1");
        r.add("media.max-total-bytes-per-owner", () -> "16");
        r.add("article-images.generated.cleanup-interval-ms", () -> "3600000");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private IdentityAssertionSigner signer;

    @Autowired
    private MediaReferenceRepository mediaRefs;

    @Autowired
    private ObjectStorageAdapter storage;

    @Autowired
    private DatabaseClient db;

    @Test
    @SuppressWarnings("unchecked")
    void uploadConfirmSignedReadDeleteRoundTrip() throws Exception {
        String owner = "acct-" + UUID.randomUUID();
        String org = "org-" + UUID.randomUUID();
        WebTestClient client = client();

        Map<String, Object> ticketEnvelope = client.post().uri("/api/media/upload-tickets")
                .header("X-Grassland-Identity", sign(owner, org))
                .bodyValue(Map.of(
                        "contentType", "image/png",
                        "purpose", "engagement_attachment",
                        "domainType", "application",
                        "domainId", "app-123",
                        "sizeBytes", PNG.length,
                        "ttlSeconds", 3600))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(ticketEnvelope).isNotNull();
        Map<String, Object> ticket = (Map<String, Object>) ticketEnvelope.get("data");
        UUID mediaId = UUID.fromString((String) ticket.get("id"));
        String uploadKey = (String) ticket.get("objectKey");
        URI uploadUrl = URI.create((String) ticket.get("uploadUrl"));
        assertThat(uploadKey).startsWith("media-pending/");

        HttpResponse<Void> put = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uploadUrl)
                        .header("Content-Type", "image/png")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(PNG))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);

        client.post().uri("/api/media/{id}/confirm", mediaId)
                .header("X-Grassland-Identity", sign(owner, org))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.status").isEqualTo("active")
                .jsonPath("$.data.ownerAccountId").isEqualTo(owner)
                .jsonPath("$.data.organizationId").isEqualTo(org)
                .jsonPath("$.data.domainType").isEqualTo("application")
                .jsonPath("$.data.domainId").isEqualTo("app-123")
                .jsonPath("$.data.sizeBytes").isEqualTo(PNG.length)
                .jsonPath("$.data.checksum").isEqualTo(MediaChecksums.sha256(PNG));

        MediaReference active = mediaRefs.findById(mediaId).block();
        assertThat(active).isNotNull();
        assertThat(active.objectKey()).startsWith("media/engagement_attachment/");
        assertThat(active.objectKey()).isNotEqualTo(uploadKey);
        assertThat(active.uploadKey()).isEqualTo(uploadKey);

        // 原 presigned PUT 仍在有效期内，但只能覆盖临时 key；最终资产从未暴露 PUT 凭据。
        byte[] replacement = new byte[PNG.length];
        HttpResponse<Void> reusedPut = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uploadUrl)
                        .header("Content-Type", "image/png")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(replacement))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(reusedPut.statusCode()).isEqualTo(200);

        Map<String, Object> readEnvelope = client.get().uri("/api/media/{id}", mediaId)
                .header("X-Grassland-Identity", sign(owner, org))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(readEnvelope).isNotNull();
        Map<String, Object> read = (Map<String, Object>) readEnvelope.get("data");
        URI downloadUrl = URI.create((String) read.get("downloadUrl"));
        // 图片类型：签名读 URL 不注入 response-content-disposition，浏览器内联渲染。
        assertThat(downloadUrl.toString()).doesNotContain("response-content-disposition");
        HttpResponse<byte[]> download = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(downloadUrl).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(PNG);

        client.get().uri("/api/media/{id}", mediaId)
                .header("X-Grassland-Identity", sign("other-" + UUID.randomUUID(), null))
                .exchange().expectStatus().isNotFound();

        client.delete().uri("/api/media/{id}", mediaId)
                .header("X-Grassland-Identity", sign(owner, org))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.deleted").isEqualTo(true);

        MediaReference deleted = mediaRefs.findById(mediaId).block();
        assertThat(deleted).isNotNull();
        assertThat(deleted.status()).isEqualTo(MediaStatus.DELETED);
        assertThat(deleted.deletedAt()).isNotNull();
        Long deletedRows = db.sql("""
                        SELECT COUNT(*)::bigint AS c FROM media_reference
                        WHERE id=CAST(:id AS uuid) AND status='deleted' AND upload_key IS NULL
                        """)
                .bind("id", mediaId.toString()).map(row -> row.get("c", Long.class)).one().block();
        assertThat(deletedRows).isEqualTo(1L);
        client.get().uri("/api/media/{id}", mediaId)
                .header("X-Grassland-Identity", sign(owner, org))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @SuppressWarnings("unchecked")
    void signedReadInjectsContentDispositionForNonImageTypes() throws Exception {
        String owner = "acct-" + UUID.randomUUID();
        // 8 字节 "%PDF-1.5"；media 路径仅校验 size + MIME 字符串（无 magic-byte），任意同长度字节即可。
        byte[] pdf = new byte[] {0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x35};
        WebTestClient client = client();

        Map<String, Object> ticketEnvelope = client.post().uri("/api/media/upload-tickets")
                .header("X-Grassland-Identity", sign(owner, null))
                .bodyValue(Map.of(
                        "contentType", "application/pdf",
                        "purpose", "user_upload",
                        "sizeBytes", pdf.length,
                        "ttlSeconds", 3600))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(ticketEnvelope).isNotNull();
        Map<String, Object> ticket = (Map<String, Object>) ticketEnvelope.get("data");
        UUID mediaId = UUID.fromString((String) ticket.get("id"));
        URI uploadUrl = URI.create((String) ticket.get("uploadUrl"));

        HttpResponse<Void> put = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uploadUrl)
                        .header("Content-Type", "application/pdf")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(pdf))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isEqualTo(200);

        client.post().uri("/api/media/{id}/confirm", mediaId)
                .header("X-Grassland-Identity", sign(owner, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("active");

        Map<String, Object> readEnvelope = client.get().uri("/api/media/{id}", mediaId)
                .header("X-Grassland-Identity", sign(owner, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(readEnvelope).isNotNull();
        Map<String, Object> read = (Map<String, Object>) readEnvelope.get("data");
        URI downloadUrl = URI.create((String) read.get("downloadUrl"));

        // 非图片类型：签名读 URL 注入 attachment; filename=<id>.pdf，强制浏览器下载而非内联渲染。
        assertThat(downloadUrl.getRawQuery()).contains("response-content-disposition");
        assertThat(downloadUrl.getRawQuery()).contains("attachment");
        assertThat(downloadUrl.getRawQuery()).contains(mediaId + ".pdf");

        HttpResponse<byte[]> download = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(downloadUrl).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(pdf);
        assertThat(download.headers().firstValue("Content-Disposition").orElse(""))
                .isEqualTo("attachment; filename=\"" + mediaId + ".pdf\"");
    }

    @Test
    void endpointsRequireAuthAndRejectUnsafePurposeOrMime() {
        client().post().uri("/api/media/upload-tickets")
                .bodyValue(Map.of("contentType", "image/png", "purpose", "user_upload", "sizeBytes", 1))
                .exchange().expectStatus().isUnauthorized();

        client().post().uri("/api/media/upload-tickets")
                .header("X-Grassland-Identity", sign("acct-" + UUID.randomUUID(), null))
                .bodyValue(Map.of("contentType", "image/svg+xml", "purpose", "user_upload", "sizeBytes", 1))
                .exchange().expectStatus().isBadRequest();

        client().post().uri("/api/media/upload-tickets")
                .header("X-Grassland-Identity", sign("acct-" + UUID.randomUUID(), null))
                .bodyValue(Map.of("contentType", "image/png", "purpose", "article_generated", "sizeBytes", 1))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmMissingObjectReturnsNotFoundAndKeepsPendingAuditRow() {
        String owner = "acct-" + UUID.randomUUID();
        Map<String, Object> envelope = client().post().uri("/api/media/upload-tickets")
                .header("X-Grassland-Identity", sign(owner, null))
                .bodyValue(Map.of("contentType", "application/pdf", "purpose", "user_upload", "sizeBytes", 10))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        UUID id = UUID.fromString((String) data.get("id"));

        client().post().uri("/api/media/{id}/confirm", id)
                .header("X-Grassland-Identity", sign(owner, null))
                .exchange().expectStatus().isNotFound();

        MediaReference pending = mediaRefs.findById(id).block();
        assertThat(pending).isNotNull();
        assertThat(pending.status()).isEqualTo(MediaStatus.PENDING);
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentConfirmHasSingleWinnerAndBothCallsConverge() throws Exception {
        String owner = "acct-" + UUID.randomUUID();
        Map<String, Object> ticket = createTicket(owner, PNG.length);
        UUID id = UUID.fromString((String) ticket.get("id"));
        URI uploadUrl = URI.create((String) ticket.get("uploadUrl"));
        put(uploadUrl, PNG);

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> confirmStatus(owner, id, start));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> confirmStatus(owner, id, start));
        start.countDown();

        assertThat(List.of(first.get(), second.get())).allMatch(status -> status == 200 || status == 409);
        client().post().uri("/api/media/{id}/confirm", id)
                .header("X-Grassland-Identity", sign(owner, null))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("active");
        assertThat(mediaRefs.findById(id).block().status()).isEqualTo(MediaStatus.ACTIVE);
    }

    @Test
    void quotaReservationIsAtomicForConcurrentTickets() throws Exception {
        String owner = "acct-" + UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> ticketStatus(owner, 10, start));
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> ticketStatus(owner, 10, start));
        start.countDown();

        assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(200, 429);
        Long count = db.sql("""
                        SELECT COUNT(*)::bigint AS c FROM media_reference
                        WHERE owner_account_id=:owner AND status='pending'
                        """)
                .bind("owner", owner).map(row -> row.get("c", Long.class)).one().block();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmAndDeleteRemoveBothTemporaryAndFinalKeysFromStorage() throws Exception {
        String owner = "acct-" + UUID.randomUUID();
        Map<String, Object> ticket = createTicket(owner, PNG.length);
        UUID id = UUID.fromString((String) ticket.get("id"));
        URI uploadUrl = URI.create((String) ticket.get("uploadUrl"));
        put(uploadUrl, PNG);
        MediaReference pending = mediaRefs.findById(id).block();
        assertThat(pending).isNotNull();
        assertThat(storage.headObject(pending.uploadKey())).isPresent();

        client().post().uri("/api/media/{id}/confirm", id)
                .header("X-Grassland-Identity", sign(owner, null))
                .exchange().expectStatus().isOk();

        MediaReference active = mediaRefs.findById(id).block();
        assertThat(active).isNotNull();
        assertThat(storage.headObject(active.objectKey())).isPresent();
        // confirm 服务端翻转后立即删除临时对象，旧 PUT URL 无法再覆盖最终资产。
        assertThat(storage.headObject(pending.uploadKey())).isEmpty();

        client().delete().uri("/api/media/{id}", id)
                .header("X-Grassland-Identity", sign(owner, null))
                .exchange().expectStatus().isOk();

        assertThat(mediaRefs.findById(id).block().status()).isEqualTo(MediaStatus.DELETED);
        assertThat(storage.headObject(active.objectKey())).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void oversizedPutBodyRejectedAtIngestionBySignedContentLength() throws Exception {
        String owner = "acct-" + UUID.randomUUID();
        Map<String, Object> ticket = createTicket(owner, 4);
        URI uploadUrl = URI.create((String) ticket.get("uploadUrl"));
        // 票据签的是 4 字节 Content-Length；PUT 一个远大于此的 body 必须被存储后端拒绝，
        // 防止攻击者用小票据在 media-pending/ 堆积超配额对象（confirm 前的 DoS）。
        byte[] oversized = new byte[1024];
        HttpResponse<Void> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uploadUrl)
                        .header("Content-Type", "image/png")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(oversized))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(resp.statusCode()).isNotEqualTo(200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceMetadataReturnsAttachmentMetadataForMarketplaceService() throws Exception {
        String owner = "acct-" + UUID.randomUUID();
        String org = "org-" + UUID.randomUUID();
        UUID mediaId = createActiveAttachment(owner, org, PNG.length);
        WebTestClient client = client();

        Map<String, Object> envelope = client.get().uri("/api/media/{id}/metadata", mediaId)
                .header("X-Grassland-Identity", signService(org))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(envelope).isNotNull();
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertThat(data.get("id")).isEqualTo(mediaId.toString());
        assertThat(data.get("ownerAccountId")).isEqualTo(owner);
        assertThat(data.get("purpose")).isEqualTo("engagement_attachment");
        assertThat(data.get("status")).isEqualTo("active");
        assertThat(data.get("mimeType")).isEqualTo("image/png");
        assertThat(data.get("sizeBytes")).isEqualTo(PNG.length);
        assertThat(data.get("expiresAt")).isNotNull();
    }

    @Test
    void serviceMetadataRejectsUserAssertionAndNonMarketplacePrincipal() throws Exception {
        String owner = "acct-" + UUID.randomUUID();
        String org = "org-" + UUID.randomUUID();
        UUID mediaId = createActiveAttachment(owner, org, PNG.length);
        WebTestClient client = client();

        // 终端用户断言（callerKind=null）→ 403：服务间断点不对浏览器/终端用户开放
        client.get().uri("/api/media/{id}/metadata", mediaId)
                .header("X-Grassland-Identity", sign(owner, org))
                .exchange().expectStatus().isEqualTo(403);

        // 服务断言但 principal 不是 marketplace → 403
        client.get().uri("/api/media/{id}/metadata", mediaId)
                .header("X-Grassland-Identity", signService(org, "trust"))
                .exchange().expectStatus().isEqualTo(403);

        // 缺断言 → 401
        client.get().uri("/api/media/{id}/metadata", mediaId)
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void serviceMetadataReturnsNotFoundForNonAttachmentInactiveExpiredOrMissing() {
        String org = "org-" + UUID.randomUUID();
        WebTestClient client = client();

        // 非 engagement_attachment 用途（user_upload pending）→ 404
        UUID userUploadId = UUID.fromString(
                (String) createTicket("acct-a-" + UUID.randomUUID(), PNG.length).get("id"));
        client.get().uri("/api/media/{id}/metadata", userUploadId)
                .header("X-Grassland-Identity", signService(org))
                .exchange().expectStatus().isNotFound();

        // engagement_attachment 但 pending（未 confirm）→ 404
        UUID pendingId = UUID.fromString((String) createAttachmentTicket(
                "acct-b-" + UUID.randomUUID(), org, PNG.length).get("id"));
        client.get().uri("/api/media/{id}/metadata", pendingId)
                .header("X-Grassland-Identity", signService(org))
                .exchange().expectStatus().isNotFound();

        // engagement_attachment active 但已过期 → 404
        UUID expiredId = insertMedia("acct-c-" + UUID.randomUUID(), org,
                "engagement_attachment", MediaStatus.ACTIVE, Instant.now().minusSeconds(60));
        client.get().uri("/api/media/{id}/metadata", expiredId)
                .header("X-Grassland-Identity", signService(org))
                .exchange().expectStatus().isNotFound();

        // 不存在的 id → 404（存在性不可探测）
        client.get().uri("/api/media/{id}/metadata", UUID.randomUUID())
                .header("X-Grassland-Identity", signService(org))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceDownloadUrlReturnsPresignedUrlServingOriginalBytes() throws Exception {
        String owner = "acct-" + UUID.randomUUID();
        String org = "org-" + UUID.randomUUID();
        UUID mediaId = createActiveAttachment(owner, org, PNG.length);
        WebTestClient client = client();

        Map<String, Object> envelope = client.get().uri("/api/media/{id}/download-url", mediaId)
                .header("X-Grassland-Identity", signService(org))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        assertThat(envelope).isNotNull();
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        URI downloadUrl = URI.create((String) data.get("downloadUrl"));
        assertThat(data.get("expiresAt")).isNotNull();

        // 真 GET presigned URL 对 MinIO：200 + 原始字节（与 owner-only read 同源签名语义）
        HttpResponse<byte[]> download = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(downloadUrl).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.body()).isEqualTo(PNG);
    }

    @Test
    void serviceDownloadUrlReturnsNotFoundForExpiredOrDeletedAndRejectsUser() throws Exception {
        String org = "org-" + UUID.randomUUID();
        WebTestClient client = client();

        // 已过期 active → 404（download-url 在 presign 前就被 serviceAttachment 过滤掉）
        UUID expiredId = insertMedia("acct-a-" + UUID.randomUUID(), org,
                "engagement_attachment", MediaStatus.ACTIVE, Instant.now().minusSeconds(60));
        client.get().uri("/api/media/{id}/download-url", expiredId)
                .header("X-Grassland-Identity", signService(org))
                .exchange().expectStatus().isNotFound();

        // media 被删后 → 404
        String ownerB = "acct-b-" + UUID.randomUUID();
        UUID liveId = createActiveAttachment(ownerB, org, PNG.length);
        client.delete().uri("/api/media/{id}", liveId)
                .header("X-Grassland-Identity", sign(ownerB, org))
                .exchange().expectStatus().isOk();
        client.get().uri("/api/media/{id}/download-url", liveId)
                .header("X-Grassland-Identity", signService(org))
                .exchange().expectStatus().isNotFound();

        // 用户断言 → 403（download-url 同 metadata 的 service gate）
        String ownerC = "acct-c-" + UUID.randomUUID();
        UUID liveId2 = createActiveAttachment(ownerC, org, PNG.length);
        client.get().uri("/api/media/{id}/download-url", liveId2)
                .header("X-Grassland-Identity", sign(ownerC, org))
                .exchange().expectStatus().isEqualTo(403);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createTicket(String owner, int sizeBytes) {
        Map<String, Object> envelope = client().post().uri("/api/media/upload-tickets")
                .header("X-Grassland-Identity", sign(owner, null))
                .bodyValue(Map.of("contentType", "image/png", "purpose", "user_upload", "sizeBytes", sizeBytes))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) envelope.get("data");
    }

    /** 申请 engagement_attachment 上传凭据（pending，未 confirm）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> createAttachmentTicket(String owner, String org, int sizeBytes) {
        Map<String, Object> envelope = client().post().uri("/api/media/upload-tickets")
                .header("X-Grassland-Identity", sign(owner, org))
                .bodyValue(Map.of(
                        "contentType", "image/png",
                        "purpose", "engagement_attachment",
                        "domainType", "application",
                        "domainId", "app-1",
                        "sizeBytes", sizeBytes,
                        "ttlSeconds", 3600))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) envelope.get("data");
    }

    /** 三步上传一个 active 的 engagement_attachment 附件，返回 media id。 */
    private UUID createActiveAttachment(String owner, String org, int sizeBytes) throws Exception {
        Map<String, Object> ticket = createAttachmentTicket(owner, org, sizeBytes);
        UUID id = UUID.fromString((String) ticket.get("id"));
        put(URI.create((String) ticket.get("uploadUrl")), PNG);
        client().post().uri("/api/media/{id}/confirm", id)
                .header("X-Grassland-Identity", sign(owner, org))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("active");
        return id;
    }

    /** 直接落一条 media_reference 行（绕过上传/配额），用于构造 active+过期等边缘态做服务端点过滤验证。 */
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

    private void put(URI uploadUrl, byte[] content) throws Exception {
        HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uploadUrl)
                        .header("Content-Type", "image/png")
                        .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private int confirmStatus(String owner, UUID id, CountDownLatch start) {
        await(start);
        return client().post().uri("/api/media/{id}/confirm", id)
                .header("X-Grassland-Identity", sign(owner, null))
                .exchange().returnResult(Void.class).getStatus().value();
    }

    private int ticketStatus(String owner, int sizeBytes, CountDownLatch start) {
        await(start);
        return client().post().uri("/api/media/upload-tickets")
                .header("X-Grassland-Identity", sign(owner, null))
                .bodyValue(Map.of("contentType", "image/png", "purpose", "user_upload", "sizeBytes", sizeBytes))
                .exchange().returnResult(Void.class).getStatus().value();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }

    private WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    private String sign(String accountId, String organizationId) {
        Instant now = Instant.now();
        return signer.sign(new IdentityAssertion(
                accountId, "recommender", "sid-" + accountId, organizationId, null,
                "cookie-session", "level1", null, "request", "trace",
                "grassland-internal", now, now.plusSeconds(60), null, null));
    }

    /** 造一个 marketplace 服务断言（callerKind=service + principal=marketplace + org 上下文），镜像 ServiceAssertionIssuer.issueForOrg。 */
    private String signService(String organizationId) {
        return signService(organizationId, "marketplace");
    }

    private String signService(String organizationId, String principal) {
        Instant now = Instant.now();
        return signer.sign(new IdentityAssertion(
                "service:" + principal, null, null, organizationId, null,
                "service", "internal", null, "request", "trace",
                "grassland-internal", now, now.plusSeconds(30),
                "service", principal));
    }
}
