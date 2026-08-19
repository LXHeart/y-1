package com.grassland.intelligence.imagestudio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.media.MediaChecksums;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.storage.ObjectStorageAdapter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 图片编辑台抠图端点 IT（任务书 #43 Stage 1）：鉴权、media 归属过滤、sandbox 全链
 * （响应 PNG 含 alpha）、预算闸 run 结算、无配置 503、读端点 404 边界。
 */
class ImageStudioControllerIT extends IntelligenceItSupport {

    private static final String OWNER = "matting-owner";
    private static final String OTHER_OWNER = "matting-other";
    private static final int SIZE = 12;

    @Autowired
    private MediaReferenceRepository mediaReferences;

    @MockitoBean
    private ObjectStorageAdapter storage;

    private byte[] png;

    @BeforeEach
    void setUp() throws Exception {
        reset(storage);
        db.sql("DELETE FROM intelligence_outbox").then().block();
        db.sql("DELETE FROM ai_run").then().block();
        db.sql("DELETE FROM ai_model_budget").then().block();
        db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
        db.sql("DELETE FROM platform_model_config").then().block();
        db.sql("DELETE FROM media_reference WHERE purpose='user_upload'").then().block();
        seedImageEditModel();
        png = redPng();
        when(storage.getObject(anyString())).thenReturn(png.clone());
    }

    @Test
    void mattingRunsSandboxPipelineReturnsAlphaPngAndSettlesRun() throws Exception {
        UUID mediaId = image(OWNER, "image/png");

        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) matting(OWNER, mediaId)
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult().getResponseBody();
        assertThat(envelope).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        String imageUrl = (String) data.get("imageUrl");
        assertThat(imageUrl).startsWith("/api/image-studio/matting-results/");

        Resource stored = client().get().uri(imageUrl)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectBody(Resource.class)
                .returnResult().getResponseBody();
        assertThat(stored).isNotNull();
        BufferedImage result = ImageIO.read(stored.getInputStream());
        assertThat(result).isNotNull();
        assertThat(result.getColorModel().hasAlpha()).as("抠图结果必须含 alpha 通道").isTrue();
        assertThat(result.getWidth()).isEqualTo(SIZE);
        assertThat(result.getHeight()).isEqualTo(SIZE);
        // sandbox 语义：中央不透明、角落透明
        assertThat((result.getRGB(SIZE / 2, SIZE / 2) >>> 24) & 0xFF).isEqualTo(255);
        assertThat((result.getRGB(0, 0) >>> 24) & 0xFF).isZero();

        // 预算闸闭环：run 必须结算为 completed（不结算会悬挂占预算）
        Map<String, Object> run = db.sql("""
                        SELECT status, capability, provider, model FROM ai_run LIMIT 1
                        """)
                .map((row, metadata) -> Map.<String, Object>of(
                        "status", row.get("status", String.class),
                        "capability", row.get("capability", String.class),
                        "provider", row.get("provider", String.class),
                        "model", row.get("model", String.class)))
                .one().block();
        assertThat(run)
                .containsEntry("status", "completed")
                .containsEntry("capability", "image_edit")
                .containsEntry("provider", "sandbox")
                .containsEntry("model", "sandbox-matting-v1");
        assertThat(singleLong("SELECT COUNT(*) FROM intelligence_outbox "
                + "WHERE event_type='AiRunCompleted'")).isEqualTo(1L);
    }

    @Test
    void authenticationIsRequired() {
        UUID mediaId = image(OWNER, "image/png");

        client().post().uri("/api/image-studio/matting")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mediaId", mediaId))
                .exchange().expectStatus().isUnauthorized();
        assertThat(singleLong("SELECT COUNT(*) FROM ai_run")).isZero();
    }

    @Test
    void nonImageForeignOrInactiveMediaIsNotFound() {
        UUID video = image(OWNER, "video/mp4");
        UUID foreign = image(OTHER_OWNER, "image/png");

        matting(OWNER, video).expectStatus().isNotFound();
        matting(OWNER, foreign).expectStatus().isNotFound();
        matting(OTHER_OWNER, image(OWNER, "image/png")).expectStatus().isNotFound();
        verify(storage, never()).getObject(anyString());
        assertThat(singleLong("SELECT COUNT(*) FROM ai_run")).isZero();
    }

    @Test
    void missingImageEditModelReturnsStable503() {
        db.sql("DELETE FROM platform_model_config WHERE capability='image_edit'").then().block();
        UUID mediaId = image(OWNER, "image/png");

        matting(OWNER, mediaId)
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.code").isEqualTo("no_platform_model")
                .jsonPath("$.error").isEqualTo("未配置图像编辑模型");
        assertThat(singleLong("SELECT COUNT(*) FROM ai_run")).isZero();
    }

    @Test
    void unknownOrMalformedResultIdIsNotFound() {
        client().get().uri("/api/image-studio/matting-results/{id}", UUID.randomUUID())
                .exchange().expectStatus().isNotFound();
        client().get().uri("/api/image-studio/matting-results/not-a-uuid")
                .exchange().expectStatus().isNotFound();
    }

    private WebTestClient.ResponseSpec matting(String accountId, UUID mediaId) {
        return client().post().uri("/api/image-studio/matting")
                .header("X-Grassland-Identity", sign(accountId, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("mediaId", mediaId))
                .exchange();
    }

    private UUID image(String owner, String mimeType) {
        UUID id = UUID.randomUUID();
        mediaReferences.insert(new MediaReference(
                id, owner, null, MediaPurpose.USER_UPLOAD.db(), null, null,
                "media/user_upload/internal-object-key-" + id, null,
                mimeType, png.length, MediaChecksums.sha256(png), "upload",
                MediaStatus.ACTIVE, Instant.now(), Instant.now().plusSeconds(3600), null))
                .block();
        return id;
    }

    private void seedImageEditModel() {
        db.sql("""
                        INSERT INTO platform_model_config(
                            capability, model_role, provider, model, base_url,
                            health_status, enabled, version)
                        VALUES ('image_edit','primary','sandbox','sandbox-matting-v1',
                            'https://sandbox.invalid','healthy',true,1)
                        """)
                .then().block();
    }

    private static byte[] redPng() throws Exception {
        BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, SIZE, SIZE);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private long singleLong(String sql) {
        return db.sql(sql).map(row -> row.get(0, Long.class)).one().block();
    }
}
