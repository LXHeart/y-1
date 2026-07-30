package com.grassland.intelligence.videorecreation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.articleimage.ArticleImageService;
import com.grassland.intelligence.articleimage.GeneratedImageResponse;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.media.MediaPurpose;
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

    @BeforeEach
    void setUp() {
        reset(images, credits);
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
    @DisplayName("generate-scene-image rejects over-long dialogue voiceover")
    void generateSceneImageRejectsTooLongDialogue() {
        client().post().uri("/api/video-recreation/generate-scene-image")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "scene", Map.of(
                                "shotDescription", "镜头", "characterDescription", "角色",
                                "dialogueVoiceover", "x".repeat(1001), "sceneEnvironment", "夜景")))
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
                                        "sceneEnvironment", "环境1"),
                                Map.of("shotDescription", "镜头2", "characterDescription", "角色",
                                        "sceneEnvironment", "环境2"))))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.images.length()").isEqualTo(2);

        // 空场景列表 → 400。
        client().post().uri("/api/video-recreation/generate-all-scene-images")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("scenes", List.of()))
                .exchange().expectStatus().isBadRequest();
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
