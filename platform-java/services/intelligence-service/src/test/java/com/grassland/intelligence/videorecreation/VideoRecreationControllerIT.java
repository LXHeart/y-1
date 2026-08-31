package com.grassland.intelligence.videorecreation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
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
    private com.grassland.intelligence.articleimage.IndependentImageGenerationService independentImages;

    @MockitoBean
    private CreditsClient credits;

    /** BYOK 改编执行客户端打桩（真 pinned HTTPS 链路由 TextCompletionClient 自测覆盖）。 */
    @MockitoBean
    private TextCompletionClient textCompletion;
    @MockitoBean
    private com.grassland.intelligence.ai.byok.ByokRoutingService byokRouting;
    @MockitoBean
    private com.grassland.intelligence.ai.run.ProviderKeyDecryptor keyDecryptor;

    @BeforeEach
    void setUp() {
        reset(independentImages, credits, textCompletion);
        when(independentImages.generate(any(), any(), any(), eq(MediaPurpose.VIDEO_ASSET))).thenReturn(Mono.just(
                new com.grassland.intelligence.articleimage.IndependentImageGenerationService.Traced(
                        new GeneratedImageResponse("/api/article-generation/generated-images/abc", "优化后"),
                        java.util.UUID.randomUUID(), "qwen", "wanx-v1")));
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

        verify(independentImages).generate(argThat(cmd -> cmd.prompt().contains("角色名")), any(), any(), eq(MediaPurpose.VIDEO_ASSET));
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

        verify(independentImages, org.mockito.Mockito.times(2)).generate(any(), any(), any(), eq(MediaPurpose.VIDEO_ASSET));
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

        verify(independentImages).generate(argThat(cmd -> cmd.prompt().contains("镜头")), any(), any(), eq(MediaPurpose.VIDEO_ASSET));
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
    @DisplayName("adapt-content 平台路径：经统一路由调平台模型，proxyVideoUrl 不进上游消息")
    void adaptContentReturnsEnvelope() {
        stubPlatformRouting("https://platform.example.com/v1", "qwen-plus", "sk-platform");
        stubTextCompletion("https://platform.example.com/v1", "sk-platform", "qwen-plus", false, "改编摘要");
        client().post().uri("/api/video-recreation/adapt-content")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(adaptBody("脚本内容"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.adaptedSummary").isEqualTo("改编摘要");

        // proxyVideoUrl 绝不能进入上游消息体（多模态 parts 序列化后断言）。
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<ChatMessage>> msgsCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(textCompletion).completeMessages(anyString(), eq("https://platform.example.com/v1"), eq("sk-platform"),
                eq("qwen-plus"), msgsCaptor.capture(), eq(4096), eq(false), any());
        String serialized = msgsCaptor.getValue().toString();
        assertThat(serialized).doesNotContain("secret-proxy-token");
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
    @DisplayName("adapt-content 优先使用用户 BYOK（模型密钥开关 on），不触达平台模型、不扣积分")
    void adaptContentPrefersUserByok() {
        String accountId = UUID.randomUUID().toString();
        com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution byok =
                com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution.byok(
                        "openai-compatible", "https://byok.example.com/v1", "byok-model", "cipher", "kv-1");
        when(byokRouting.resolveProvider(isNull(), eq(accountId), eq("text"), eq(true)))
                .thenReturn(Mono.just(byok));
        when(keyDecryptor.decryptIfNeeded(byok)).thenReturn("byok-key");
        stubTextCompletion("https://byok.example.com/v1", "byok-key", "byok-model", true, "BYOK改编摘要");

        client().post().uri("/api/video-recreation/adapt-content")
                .header("X-Grassland-Identity", sign(accountId, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(adaptBody("脚本内容"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.adaptedSummary").isEqualTo("BYOK改编摘要");

        verify(textCompletion).completeMessages(
                anyString(), eq("https://byok.example.com/v1"), eq("byok-key"), eq("byok-model"),
                argThat(messages -> messages.stream().allMatch(ChatMessage::multimodal)),
                eq(4096), eq(true), any());
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("adapt-content BYOK 未命中（无密钥/开关 off）→ 平台内置模型兜底")
    void adaptContentFallsBackToPlatformWithoutByok() {
        String accountId = UUID.randomUUID().toString();
        when(byokRouting.resolveProvider(isNull(), eq(accountId), eq("text"), eq(true)))
                .thenReturn(Mono.just(com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution
                        .platform(null, "qwen", "https://platform.example.com/v1", "qwen-plus", 1, null)));
        when(keyDecryptor.decryptIfNeeded(org.mockito.ArgumentMatchers.any()))
                .thenReturn("sk-platform");
        stubTextCompletion("https://platform.example.com/v1", "sk-platform", "qwen-plus", false, "平台改编摘要");

        client().post().uri("/api/video-recreation/adapt-content")
                .header("X-Grassland-Identity", sign(accountId, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(adaptBody("脚本内容"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.adaptedSummary").isEqualTo("平台改编摘要");

        // 平台兜底同样经 TextCompletionClient（byok=false），不再有 env 直连旁路。
        verify(textCompletion).completeMessages(
                anyString(), eq("https://platform.example.com/v1"), eq("sk-platform"), eq("qwen-plus"),
                any(), eq(4096), eq(false), any());
    }

    /** 平台路由桩：resolveProvider → 平台解析 + 解密 bearer。 */
    private void stubPlatformRouting(String baseUrl, String model, String bearer) {
        com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution platform =
                com.grassland.intelligence.ai.byok.ByokRoutingService.ProviderResolution.platform(
                        null, "qwen", baseUrl, model, 1, null);
        when(byokRouting.resolveProvider(any(), any(), eq("text"), anyBoolean()))
                .thenReturn(Mono.just(platform));
        when(keyDecryptor.decryptIfNeeded(platform)).thenReturn(bearer);
    }

    /** TextCompletionClient 桩：返回标准改编 JSON（usage 齐备）。 */
    private void stubTextCompletion(String baseUrl, String bearer, String model, boolean byok, String summary) {
        when(textCompletion.completeMessages(anyString(), eq(baseUrl), eq(bearer), eq(model),
                any(), eq(4096), eq(byok), any()))
                .thenReturn(Mono.just(new TextCompletionResult(
                        "{\"adapted_summary\":\"" + summary + "\",\"adapted_script\":[],"
                                + "\"character_sheets\":[],\"scene_cards\":[],\"prop_cards\":[]}",
                        10, 5)));
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
