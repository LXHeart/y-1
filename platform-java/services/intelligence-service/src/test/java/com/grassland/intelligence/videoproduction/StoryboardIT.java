package com.grassland.intelligence.videoproduction;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.storage.ObjectStorageAdapter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #64 卡3：结构化分镜 SSE——NDJSON 上游逐镜转发、锚定图映射校验、时长步进校验、
 * L1 命中出安全帧、storyboard/shots 落库、lineage 落 video_storyboard。
 */
@DisplayName("Structured storyboard generation")
class StoryboardIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "41314131-3131-3131-3131-313131313131";
    private static final String IMAGE = "data:image/jpeg;base64,AAAA";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    VideoStoryboardRepository storyboardRepo;

    @Autowired
    VideoShotRepository shotRepo;

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        when(credits.compensate(any(), anyString())).thenReturn(Mono.empty());

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM creation_generation WHERE kind='video_storyboard'").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .block(java.time.Duration.ofSeconds(10));
        db.sql("DELETE FROM platform_model_config WHERE capability='text'").then()
                .then(db.sql("""
                        INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                            health_status, enabled, version)
                        VALUES ('text','primary','qwen','qwen-plus',:baseUrl,'healthy',true,7)
                        """).bind("baseUrl", QWEN.baseUrl()).then())
                .block(java.time.Duration.ofSeconds(10));
        QWEN.resetAll();
        attachPlatformTextCredential();
    }

    @Test
    @DisplayName("NDJSON 上游 → meta/shot×3/safety 帧序列 + storyboard/shots/lineage 落库")
    void streamsShotsAndPersistsStoryboard() {
        String content = String.join("\n", List.of(
                "{\"seq\":1,\"visual\":\"招牌特写\",\"narration\":\"老王面馆，现熬骨汤\",\"plannedSeconds\":5,"
                        + "\"cameraMove\":\"缓慢推近\",\"anchorImageIndex\":1,\"prompt\":\"招牌特写，热气升腾\"}",
                "{\"seq\":2,\"visual\":\"后厨实拍\",\"narration\":\"每天现切鲜面\",\"plannedSeconds\":9,"
                        + "\"cameraMove\":\"跟随运镜\",\"anchorImageIndex\":2,\"prompt\":\"后厨拉面动作\"}",
                "{\"seq\":3,\"visual\":\"顾客用餐\",\"narration\":\"街坊都爱这口\",\"plannedSeconds\":5,"
                        + "\"cameraMove\":\"固定机位\",\"anchorImageIndex\":0,\"prompt\":\"堂食热闹场景\"}"));
        stubCompletion(content);

        String body = client().post().uri("/api/video-production/storyboard")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(30, 2))
                .exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).startsWith("data: {\"type\":\"meta\"");
        assertThat(body).contains("\"targetDurationSeconds\":30");
        assertThat(body).contains("\"storyboardId\"");
        assertThat(body).contains("\"type\":\"shot\"");
        assertThat(body).contains("\"visual\":\"招牌特写\"");
        assertThat(body).contains("\"narration\":\"街坊都爱这口\"");
        assertThat(body).contains("\"type\":\"safety\"");
        assertThat(body).contains("data: [DONE]");

        String storyboardId = db.sql("SELECT id::text FROM video_storyboard "
                        + "WHERE account_id=:account ORDER BY created_at DESC LIMIT 1")
                .bind("account", ACCOUNT).map(row -> row.get("id", String.class)).one().block();
        assertThat(storyboardId).isNotBlank();
        // plannedSeconds=9 被 §4.2 硬约束钳到 6；seq 重编号 1..3
        List<String> shots = db.sql("SELECT seq || ':' || planned_seconds || ':' || anchor_image_index AS shot_row "
                        + "FROM video_shot WHERE storyboard_id=CAST(:sb AS uuid) ORDER BY seq")
                .bind("sb", storyboardId).map(row -> row.get("shot_row", String.class)).all().collectList().block();
        assertThat(shots).containsExactly("1:5:1", "2:6:2", "3:5:0");

        String lineage = db.sql("SELECT kind || ':' || result::text AS lineage_row FROM creation_generation "
                        + "WHERE owner_account_id=:account AND kind='video_storyboard'")
                .bind("account", ACCOUNT).map(row -> row.get("lineage_row", String.class)).one().block();
        assertThat(lineage).startsWith("video_storyboard:").contains("shotCount");
        assertThat(lineage).contains(storyboardId);

        QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("短视频分镜导演"))
                .withRequestBody(containing("目标总时长：30 秒")));
    }

    @Test
    @DisplayName("L1 命中（绝对化用语）→ safety 帧携带 findings")
    void lexiconHitSurfacesSafetyFrame() {
        String content = "{\"seq\":1,\"visual\":\"招牌\",\"narration\":\"全网最好喝的骨汤面\","
                + "\"plannedSeconds\":5,\"cameraMove\":\"固定机位\",\"anchorImageIndex\":1,\"prompt\":\"招牌特写\"}";
        stubCompletion(content);

        String body = client().post().uri("/api/video-production/storyboard")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(15, 1))
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"type\":\"safety\"");
        assertThat(body).contains("absolute_claims");
    }

    @Test
    @DisplayName("anchorImageIndex 超出图片数 → 400（SSE 开始前拒绝）")
    void anchorImageIndexOutOfRangeRejected() {
        String content = "{\"seq\":1,\"visual\":\"招牌\",\"narration\":\"x\","
                + "\"plannedSeconds\":5,\"cameraMove\":\"固定机位\",\"anchorImageIndex\":5,\"prompt\":\"x\"}";
        stubCompletion(content);

        client().post().uri("/api/video-production/storyboard")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(20, 2))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("分镜锚定图序号超出范围");
    }

    @Test
    @DisplayName("时长不按 5 秒步进 → 400")
    void durationMustStepByFive() {
        client().post().uri("/api/video-production/storyboard")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(17, 1))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("未登录 → 401 且不触发上游调用")
    void unauthenticatedRejected() {
        client().post().uri("/api/video-production/storyboard")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(15, 1))
                .exchange().expectStatus().isUnauthorized();
        QWEN.verify(0, postRequestedFor(urlEqualTo("/chat/completions")));
    }

    private void stubCompletion(String content) {
        String body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                    "choices", List.of(Map.of("message", Map.of("content", content))),
                    "usage", Map.of("prompt_tokens", 25, "completion_tokens", 120)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        QWEN.stubFor(post(urlEqualTo("/chat/completions"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson(body)));
    }

    /** imageCount 张图 + 目标时长的最小合法请求体。 */
    private static Map<String, Object> requestBody(int targetDurationSeconds, int imageCount) {
        return Map.of(
                "images", java.util.Collections.nCopies(imageCount, IMAGE),
                "shopName", "老王面馆",
                "industryType", "餐饮",
                "videoStyle", "烟火纪实",
                "targetPlatform", "douyin",
                "targetDurationSeconds", targetDurationSeconds);
    }
}
