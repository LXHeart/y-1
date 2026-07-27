package com.grassland.intelligence.articleimage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditsClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

class ArticleImageControllerIT extends IntelligenceItSupport {

    @MockitoBean
    private ArticleImageService images;

    @MockitoBean
    private CreditsClient credits;

    @BeforeEach
    void setUp() {
        reset(images, credits);
        when(images.recommend(any())).thenReturn(Mono.just(new ImageRecommendation(1, List.of(
                new ImagePlacement("封面", "概念图", "职场 插画", "现代商务插画")))));
        when(images.search(anyString(), anyInt())).thenReturn(Mono.just(List.of(
                new ImageSearchResult("https://cdn.example/a.jpg", "https://cdn.example/t.jpg",
                        null, "图A", 100, 80))));
        when(images.generate(any())).thenReturn(Mono.just(
                new GeneratedImageResponse("https://cdn.example/generated.png", "优化后")));
    }

    private String signed() {
        return sign(UUID.randomUUID().toString(), "recommender");
    }

    @Test
    @DisplayName("image recommendations require auth, preserve envelope and never consume credits")
    void recommendsImagesWithoutCredits() {
        client().post().uri("/api/article-generation/image-recommendations")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "这是一段长度足够用于图片推荐的文章正文内容。"))
                .exchange().expectStatus().isUnauthorized();

        client().post().uri("/api/article-generation/image-recommendations")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", " 这是一段长度足够用于图片推荐的文章正文内容。 "))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.recommendedCount").isEqualTo(1)
                .jsonPath("$.data.placements[0].searchKeywords").isEqualTo("职场 插画");

        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("search defaults count to three and rejects out-of-range counts")
    void searchesImagesWithLegacyValidation() {
        client().post().uri("/api/article-generation/search-images")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("keywords", " 职场沟通 "))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.images[0].url").isEqualTo("https://cdn.example/a.jpg");
        verify(images).search("职场沟通", 3);

        client().post().uri("/api/article-generation/search-images")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("keywords", "图", "count", 11))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("multipart generation accepts reference image MIME types without consuming credits")
    void generatesFromMultipartReferences() {
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("prompt", " 生成暖色餐饮插画 ");
        body.part("size", "1024x1792");
        body.part("images", resource("first.png", new byte[] {1, 2}), MediaType.IMAGE_PNG);
        body.part("images", resource("second.webp", new byte[] {3, 4}), MediaType.parseMediaType("image/webp"));

        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(body.build())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.imageUrl").isEqualTo("https://cdn.example/generated.png");

        verify(images).generate(any());
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("JSON generation remains accepted for article composable with no references")
    void generatesFromJsonWithoutReferences() {
        client().post().uri("/api/article-generation/generate-image")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("prompt", "现代商务插画"))
                .exchange().expectStatus().isOk();

        verify(images).generate(any());
    }

    @Test
    @DisplayName("generated image GET is public and preserves PNG/cache contract")
    void servesGeneratedImagePublicly() throws Exception {
        Path image = Files.createTempFile("article-image-it", ".png");
        Files.write(image, new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
        String id = UUID.randomUUID().toString();
        when(images.findGenerated(id)).thenReturn(Mono.just(new GeneratedImageStore.StoredImage(image)));

        byte[] body = client().get().uri("/api/article-generation/generated-images/" + id)
                .exchange().expectStatus().isOk()
                .expectHeader().contentType(MediaType.IMAGE_PNG)
                .expectHeader().valueEquals("Cache-Control", "max-age=1800, private")
                .expectBody().returnResult().getResponseBody();

        assertThat(body).isEqualTo(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47});
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("invalid generated image id returns compatible 404")
    void rejectsInvalidGeneratedImageId() {
        client().get().uri("/api/article-generation/generated-images/not-a-uuid")
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.success").isEqualTo(false);
    }

    private static ByteArrayResource resource(String filename, byte[] bytes) {
        return new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }
}
