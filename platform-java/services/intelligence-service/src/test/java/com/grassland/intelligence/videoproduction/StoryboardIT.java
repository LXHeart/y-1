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
    /** E1 用例独立账号：preflight 限流 10 次/分钟/账号，类内请求数已贴上限。 */
    private static final String ACCOUNT_E1 = "42324232-3232-3232-3232-323232323232";
    /** 解析容忍用例独立账号：同样为 preflight 限流余量。 */
    private static final String ACCOUNT_DIRTY = "43344334-3434-3434-3434-343434343434";
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
    @DisplayName("#65 卡1：180 秒 + bilibili → 200 且 resolution 缺省横版 1920x1080 落库")
    void bilibili180sDefaultsToLandscape() {
        stubCompletion(shotLines(3));
        client().post().uri("/api/video-production/storyboard")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(180, 1, "bilibili", null))
                .exchange().expectStatus().isOk();
        QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("目标总时长：180 秒"))
                .withRequestBody(containing("镜头数 3–30")));

        String resolution = db.sql("SELECT resolution FROM video_storyboard "
                        + "WHERE account_id=:account ORDER BY created_at DESC LIMIT 1")
                .bind("account", ACCOUNT).map(row -> row.get("resolution", String.class)).one().block();
        assertThat(resolution).isEqualTo("1920x1080");
    }

    @Test
    @DisplayName("#65 卡1：非 bilibili 平台缺省竖版；显式 resolution 白名单外 → 400")
    void nonBilibiliDefaultsPortraitAndResolutionIsWhitelisted() {
        stubCompletion(shotLines(3));
        client().post().uri("/api/video-production/storyboard")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(180, 1, "douyin", null))
                .exchange().expectStatus().isOk();
        String resolution = db.sql("SELECT resolution FROM video_storyboard "
                        + "WHERE account_id=:account ORDER BY created_at DESC LIMIT 1")
                .bind("account", ACCOUNT).map(row -> row.get("resolution", String.class)).one().block();
        assertThat(resolution).isEqualTo("1080x1920");

        client().post().uri("/api/video-production/storyboard")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(30, 1, "douyin", "720x1280"))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("分辨率仅支持 1080x1920 或 1920x1080");
    }

    @Test
    @DisplayName("#65 卡1：超过 180 秒 → 400；31 镜超上限 → 400")
    void durationAndShotCountUpperBounds() {
        client().post().uri("/api/video-production/storyboard")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(185, 1, "douyin", null))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("成片时长须为 15-180 秒且按 5 秒步进");

        stubCompletion(shotLines(31));
        client().post().uri("/api/video-production/storyboard")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(180, 1, "douyin", null))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("分镜镜头数须在 1-30 之间");
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

    @Test
    @DisplayName("#66 E1：带参考结构 → user 消息按 §3 注入「参考结构」段（含复刻红线）")
    void referenceStructureInjectedPerContract() {
        stubCompletion(shotLines(3));
        Map<String, Object> body = requestBody(20, 1);
        body.put("referenceShotStructure", Map.of(
                "shotStructure", List.of(
                        Map.of("durationSeconds", 5, "purpose", "hook"),
                        Map.of("durationSeconds", 4, "purpose", "point"),
                        Map.of("durationSeconds", 6, "purpose", "cta")),
                "hookAtSeconds", 0));
        client().post().uri("/api/video-production/storyboard")
                .header("X-Grassland-Identity", sign(ACCOUNT_E1, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk();
        QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("参考结构（仅参考节奏与结构，不得复刻其内容与文案）"))
                .withRequestBody(containing("镜头时长序列 [5, 4, 6] 秒"))
                .withRequestBody(containing("开场钩子位于第 0 秒")));
    }

    @Test
    @DisplayName("#66 E1：不带参考结构 → user 消息零变化（无「参考结构」段）")
    void withoutReferenceStructureZeroChange() {
        stubCompletion(shotLines(3));
        client().post().uri("/api/video-production/storyboard")
                .header("X-Grassland-Identity", sign(ACCOUNT_E1, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(20, 1))
                .exchange().expectStatus().isOk();
        QWEN.verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
                .withRequestBody(containing("目标总时长：20 秒"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .not(containing("参考结构"))));
    }

    @Test
    @DisplayName("LLM 输出偏离 NDJSON（寒暄/编号/数组/多行）→ 容忍解析仍出帧（2026-09-03 MiniMax-M3 实跑缺陷）")
    void toleratesDeviatedUpstreamOutput() {
        String content = String.join("\n", List.of(
                "好的，以下是为店铺设计的分镜：",
                "1. {\"seq\":1,\"visual\":\"招牌特写\",\"narration\":\"老王面馆\",\"plannedSeconds\":5,"
                        + "\"cameraMove\":\"缓慢推近\",\"anchorImageIndex\":1,\"prompt\":\"招牌特写\"}",
                "[{\"seq\":2,\"visual\":\"后厨实拍\",\"narration\":\"现切鲜面\",\"plannedSeconds\":5,"
                        + "\"cameraMove\":\"固定机位\",\"anchorImageIndex\":0,\"prompt\":\"后厨\"},",
                "{\"seq\":3,\"visual\":\"顾客用餐\",\"narration\":\"街坊都爱\",\"plannedSeconds\":5,"
                        + "\"cameraMove\":\"环绕\",\"anchorImageIndex\":0,\"prompt\":\"堂食\"}]",
                "以上就是全部镜头。"));
        stubCompletion(content);

        String body = client().post().uri("/api/video-production/storyboard")
                .header("X-Grassland-Identity", sign(ACCOUNT_DIRTY, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody(20, 1))
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).startsWith("data: {\"type\":\"meta\"");
        assertThat(body).contains("\"visual\":\"招牌特写\"");
        assertThat(body).contains("\"visual\":\"后厨实拍\"");
        assertThat(body).contains("\"visual\":\"顾客用餐\"");
        assertThat(body).contains("data: [DONE]");
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
        return requestBody(targetDurationSeconds, imageCount, "douyin", null);
    }

    /** #65 卡1：带平台与可选 resolution 的请求体。 */
    private static Map<String, Object> requestBody(int targetDurationSeconds, int imageCount,
            String targetPlatform, String resolution) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("images", java.util.Collections.nCopies(imageCount, IMAGE));
        body.put("shopName", "老王面馆");
        body.put("industryType", "餐饮");
        body.put("videoStyle", "烟火纪实");
        body.put("targetPlatform", targetPlatform);
        body.put("targetDurationSeconds", targetDurationSeconds);
        if (resolution != null) {
            body.put("resolution", resolution);
        }
        return body;
    }

    /** count 镜的最小 NDJSON 输出（每镜 5 秒）。 */
    private static String shotLines(int count) {
        StringBuilder lines = new StringBuilder();
        for (int seq = 1; seq <= count; seq++) {
            if (seq > 1) {
                lines.append('\n');
            }
            lines.append("{\"seq\":").append(seq)
                    .append(",\"visual\":\"画面").append(seq)
                    .append("\",\"narration\":\"旁白\",\"plannedSeconds\":5,")
                    .append("\"cameraMove\":\"固定机位\",\"anchorImageIndex\":1,\"prompt\":\"p\"}");
        }
        return lines.toString();
    }
}
