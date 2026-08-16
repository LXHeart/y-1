package com.grassland.intelligence.videorecreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.run.TextCompletionClient;
import com.grassland.intelligence.ai.run.TextCompletionResult;
import com.grassland.intelligence.articleimage.ArticleImageService;
import com.grassland.intelligence.articleimage.GeneratedImageResponse;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.media.MediaPurpose;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/** {@link VideoRecreationController} 四端点集成测试（草场 Slice 9）。镜像 ArticleImageControllerIT。 */
class VideoRecreationControllerIT extends IntelligenceItSupport {

    @MockitoBean
    private ArticleImageService images;

    @MockitoBean
    private CreditsClient credits;

    /** BYOK 改编执行客户端打桩（真 pinned HTTPS 链路由 TextCompletionClient 自测覆盖）。 */
    @MockitoBean
    private TextCompletionClient textCompletion;

    @BeforeEach
    void setUp() {
        reset(images, credits, textCompletion);
        when(images.generate(any(), any(), eq(MediaPurpose.VIDEO_ASSET))).thenReturn(Mono.just(
                new GeneratedImageResponse("/api/article-generation/generated-images/abc", "优化后")));
    }

    private String signed() {
        return sign(UUID.randomUUID().toString(), "recommender");
    }

    @Test
    @DisplayName("generate-asset-image requires auth")
    void generateAssetImageRequiresAuth() {
        client().post().uri("/api/video-recreation/generate-asset-image")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(characterBody())
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("generate-asset-image returns envelope with VIDEO_ASSET purpose and never consumes credits")
    void generateAssetImageReturnsEnvelope() {
        client().post().uri("/api/video-recreation/generate-asset-image")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(characterBody())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.imageUrl").isEqualTo("/api/article-generation/generated-images/abc")
                .jsonPath("$.data.revisedPrompt").isEqualTo("优化后");

        verify(images).generate(argThat(cmd -> cmd.prompt().contains("角色名")), any(), eq(MediaPurpose.VIDEO_ASSET));
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("generate-asset-image rejects unknown assetType")
    void generateAssetImageRejectsUnknownAssetType() {
        client().post().uri("/api/video-recreation/generate-asset-image")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("assetType", "bogus", "asset", Map.of(
                        "id", "a1", "name", "名", "description", "描述", "imagePrompt", "图")))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("generate-asset-image rejects invalid size")
    void generateAssetImageRejectsInvalidSize() {
        Map<String, Object> body = characterBody();
        body.put("size", "9999x9999");
        client().post().uri("/api/video-recreation/generate-asset-image")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("generate-all-asset-images returns images array in order")
    void generateAllAssetImagesReturnsArray() {
        client().post().uri("/api/video-recreation/generate-all-asset-images")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("assetType", "prop", "assets", List.of(
                        Map.of("id", "p1", "name", "道具1", "description", "描述", "imagePrompt", "图"),
                        Map.of("id", "p2", "name", "道具2", "description", "描述", "imagePrompt", "图"))))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.images[0].imageUrl").isEqualTo("/api/article-generation/generated-images/abc")
                .jsonPath("$.data.images[1].imageUrl").isEqualTo("/api/article-generation/generated-images/abc");

        verify(images, org.mockito.Mockito.times(2)).generate(any(), any(), eq(MediaPurpose.VIDEO_ASSET));
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("generate-scene-image returns envelope without consuming credits")
    void generateSceneImageReturnsEnvelope() {
        client().post().uri("/api/video-recreation/generate-scene-image")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "scene", Map.of(
                                "shotDescription", "镜头", "characterDescription", "角色",
                                "actionMovement", "走动", "dialogueVoiceover", "旁白",
                                "sceneEnvironment", "夜景"),
                        "overallStyle", "水墨"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.imageUrl").isEqualTo("/api/article-generation/generated-images/abc");

        verify(images).generate(argThat(cmd -> cmd.prompt().contains("镜头")), any(), eq(MediaPurpose.VIDEO_ASSET));
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("generate-scene-image rejects omitted or non-string required legacy fields but permits present empty strings")
    void generateSceneImagePreservesRequiredStringContract() {
        client().post().uri("/api/video-recreation/generate-scene-image")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("scene", Map.of(
                        "shotDescription", "镜头", "characterDescription", "角色", "sceneEnvironment", "夜景")))
                .exchange().expectStatus().isBadRequest();

        client().post().uri("/api/video-recreation/generate-scene-image")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("scene", Map.of(
                        "shotDescription", "镜头", "characterDescription", "角色",
                        "actionMovement", 1, "dialogueVoiceover", "", "sceneEnvironment", "夜景")))
                .exchange().expectStatus().isBadRequest();

        client().post().uri("/api/video-recreation/generate-scene-image")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("scene", Map.of(
                        "shotDescription", "镜头", "characterDescription", "角色",
                        "actionMovement", "", "dialogueVoiceover", "", "sceneEnvironment", "夜景")))
                .exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("generate-asset-image rejects non-string asset fields")
    void generateAssetImageRejectsNonStringFields() {
        client().post().uri("/api/video-recreation/generate-asset-image")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("assetType", "character-three-view", "asset", Map.of(
                        "id", 123, "name", "角色名", "description", "角色描述", "threeViewPrompt", "三视图")))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("generate-scene-image rejects over-long dialogue voiceover")
    void generateSceneImageRejectsTooLongDialogue() {
        client().post().uri("/api/video-recreation/generate-scene-image")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "scene", Map.of(
                                "shotDescription", "镜头", "characterDescription", "角色",
                                "actionMovement", "", "dialogueVoiceover", "x".repeat(1001), "sceneEnvironment", "夜景")))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("generate-all-scene-images returns images array and rejects empty list")
    void generateAllSceneImagesContract() {
        client().post().uri("/api/video-recreation/generate-all-scene-images")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "scenes", List.of(
                                Map.of("shotDescription", "镜头1", "characterDescription", "角色",
                                        "actionMovement", "", "dialogueVoiceover", "", "sceneEnvironment", "环境1"),
                                Map.of("shotDescription", "镜头2", "characterDescription", "角色",
                                        "actionMovement", "", "dialogueVoiceover", "", "sceneEnvironment", "环境2"))))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.images.length()").isEqualTo(2);

        // 空场景列表 → 400。
        client().post().uri("/api/video-recreation/generate-all-scene-images")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("scenes", List.of()))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("adapt-content requires auth")
    void adaptContentRequiresAuth() {
        client().post().uri("/api/video-recreation/adapt-content")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(adaptBody("脚本内容"))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("adapt-content returns envelope and never forwards proxyVideoUrl to Qwen")
    void adaptContentReturnsEnvelope() {
        stubQwenAdapt();
        client().post().uri("/api/video-recreation/adapt-content")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(adaptBody("脚本内容"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.adaptedSummary").isEqualTo("改编摘要");

        // proxyVideoUrl 绝不能进入上游请求体。
        List<com.github.tomakehurst.wiremock.stubbing.ServeEvent> events = QWEN.getAllServeEvents();
        assertThat(events).hasSizeGreaterThan(0);
        assertThat(events.get(events.size() - 1).getRequest().getBodyAsString())
                .doesNotContain("secret-proxy-token");
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("adapt-content rejects empty extracted content")
    void adaptContentRejectsEmptyContent() {
        client().post().uri("/api/video-recreation/adapt-content")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(adaptBody("   "))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("adapt-content 优先使用用户 BYOK（features.video），不触达平台 Qwen、不扣积分")
    void adaptContentPrefersUserByok() {
        String accountId = UUID.randomUUID().toString();
        seedAnalysisByok(accountId, "qwen", "https://byok.example.com/v1", "byok-key", "byok-model");
        when(textCompletion.completeMessages(
                eq("https://byok.example.com/v1"), eq("byok-key"), eq("byok-model"),
                any(), eq(4096), eq(true)))
                .thenReturn(Mono.just(new TextCompletionResult(
                        "{\"adapted_summary\":\"BYOK改编摘要\",\"adapted_script\":[],"
                                + "\"character_sheets\":[],\"scene_cards\":[],\"prop_cards\":[]}",
                        10, 5)));

        client().post().uri("/api/video-recreation/adapt-content")
                .header("X-Grassland-Identity", sign(accountId, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(adaptBody("脚本内容"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.adaptedSummary").isEqualTo("BYOK改编摘要");

        verify(textCompletion).completeMessages(
                eq("https://byok.example.com/v1"), eq("byok-key"), eq("byok-model"),
                argThat(messages -> messages.stream().allMatch(ChatMessage::multimodal)),
                eq(4096), eq(true));
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("adapt-content BYOK 未配齐时回落平台 Qwen，BYOK 客户端不参与")
    void adaptContentFallsBackToPlatformWithoutByok() {
        stubQwenAdapt();
        String accountId = UUID.randomUUID().toString();
        // 无 analysis 行 → 空配置 → 平台路径。
        client().post().uri("/api/video-recreation/adapt-content")
                .header("X-Grassland-Identity", sign(accountId, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(adaptBody("脚本内容"))
                .exchange().expectStatus().isOk();
        verify(textCompletion, never()).completeMessages(any(), any(), any(), any(), anyInt(), anyBoolean());

        // 配置了 provider=coze（独立协议，改编不支持）→ 400 引导切换，不回落。
        seedAnalysisByok(accountId, "coze", "https://byok.example.com/v1", "byok-key", null);
        client().post().uri("/api/video-recreation/adapt-content")
                .header("X-Grassland-Identity", sign(accountId, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(adaptBody("脚本内容"))
                .exchange().expectStatus().isBadRequest();
    }

    private void seedAnalysisByok(
            String accountId, String provider, String baseUrl, String apiKey, String model) {
        db.sql("""
                INSERT INTO app_users (id, email, password_hash)
                VALUES (CAST(:uid AS uuid), :email, 'test-hash')
                ON CONFLICT (id) DO NOTHING
                """)
                .bind("uid", UUID.fromString(accountId))
                .bind("email", accountId + "@test.local")
                .then().block();
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("provider", provider);
        video.put("baseUrl", baseUrl);
        if (apiKey != null) {
            video.put("apiKey", apiKey);
        }
        if (model != null) {
            video.put("model", model);
        }
        Map<String, Object> settings = Map.of("features", Map.of("video", video));
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(settings);
            db.sql("""
                    INSERT INTO user_settings (id, user_id, settings_type, settings_json)
                    VALUES (:id, CAST(:uid AS uuid), 'analysis', CAST(:json AS jsonb))
                    ON CONFLICT (user_id, settings_type)
                        DO UPDATE SET settings_json = excluded.settings_json
                    """)
                    .bind("id", UUID.randomUUID())
                    .bind("uid", UUID.fromString(accountId))
                    .bind("json", json)
                    .then().block();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private void stubQwenAdapt() {
        String adaptation = "{\"adapted_summary\":\"改编摘要\",\"adapted_script\":[],"
                + "\"character_sheets\":[],\"scene_cards\":[],\"prop_cards\":[]}";
        Map<String, Object> response = Map.of("choices",
                List.of(Map.of("message", Map.of("content", adaptation))));
        String body;
        try {
            body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        QWEN.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/chat/completions"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.ok()
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private Map<String, Object> adaptBody(String script) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("platform", "douyin");
        body.put("proxyVideoUrl", "/api/douyin/proxy/secret-proxy-token");
        body.put("extractedContent", Map.of("videoScript", script));
        return body;
    }

    private Map<String, Object> characterBody() {
        return new java.util.LinkedHashMap<>(Map.of(
                "assetType", "character-three-view",
                "visualStyle", "卡通",
                "size", "1024x1792",
                "asset", Map.of(
                        "id", "a1", "name", "角色名", "description", "角色描述", "threeViewPrompt", "三视图")));
    }
}
