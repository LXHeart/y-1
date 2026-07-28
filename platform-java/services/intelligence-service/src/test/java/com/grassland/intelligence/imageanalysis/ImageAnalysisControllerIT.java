package com.grassland.intelligence.imageanalysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import com.grassland.intelligence.ai.TextCompletionCommand;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.InsufficientCreditsException;
import com.grassland.intelligence.imageanalysis.StylePreferencesService.StyleSnapshot;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import reactor.core.publisher.Mono;

/**
 * 图片评价 9 端点集成测试（草场 intelligence Slice 6）。复用 {@link IntelligenceItSupport}（testcontainers postgres +
 * WireMock Qwen + 真实断言签名）。重点锁定：SSE 事件契约、积分时序（analyze 扣 IMAGE_ANALYSIS、402→SSE error 帧）、
 * 匿名语义、multipart 图片校验、风格注入、飞书缺凭据 400。
 */
class ImageAnalysisControllerIT extends IntelligenceItSupport {

    @MockitoBean
    protected AiCapabilityAdapter ai;
    @MockitoBean
    protected CreditsClient credits;

    private static final String JSON_RESULT = "{\"review\":\"很不错的商品\",\"title\":\"好评推荐\",\"tags\":[\"新鲜\",\"实惠\"]}";

    @BeforeEach
    void resetMocks() {
        Mockito.reset(ai, credits);
        when(credits.consume(any(), any())).thenReturn(Mono.empty());
        when(ai.completeText(any())).thenReturn(Mono.just(JSON_RESULT));
    }

    // ---------------- analyze ----------------

    @Test
    void analyzeStreamsProgressResultDoneAndChargesImageAnalysis() {
        when(credits.consume(any(), eq(CreditFeature.IMAGE_ANALYSIS))).thenReturn(Mono.empty());

        String body = client().post().uri("/api/image-analysis/analyze")
                .header(header(), sign("user-analyze", null))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(analyzeForm("120", null, "taobao"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"type\":\"progress\"");
        assertThat(body).contains("\"stage\":\"draft\"");
        assertThat(body).contains("\"stage\":\"optimize\"");
        assertThat(body).contains("\"stage\":\"complete\"");
        assertThat(body).contains("\"type\":\"result\"");
        assertThat(body).contains("\"review\":\"很不错的商品\"");
        assertThat(body).contains("\"imageCount\":1");
        assertThat(body).endsWith("data: [DONE]\n\n");
        verify(credits).consume("user-analyze", CreditFeature.IMAGE_ANALYSIS);
    }

    @Test
    void analyzeInsufficientCreditsSurfacesAsSseErrorFrameOverHttp200() {
        when(credits.consume(any(), eq(CreditFeature.IMAGE_ANALYSIS)))
                .thenReturn(Mono.error(new InsufficientCreditsException()));

        String body = client().post().uri("/api/image-analysis/analyze")
                .header(header(), sign("user-broke", null))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(analyzeForm("100", null, "taobao"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"type\":\"error\"");
        assertThat(body).contains("\"error\":\"积分不足\"");
        verify(ai, never()).completeText(any());
    }

    @Test
    void analyzeAnonymousEmitsGenericErrorFrameNot401() {
        String body = client().post().uri("/api/image-analysis/analyze")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(analyzeForm("100", null, "taobao"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"type\":\"error\"");
        assertThat(body).contains("\"error\":\"评价生成失败，请稍后重试\"");
        verify(credits, never()).consume(any(), any());
    }

    @Test
    void analyzeBadTextFieldReturns400BeforeSse() {
        client().post().uri("/api/image-analysis/analyze")
                .header(header(), sign("user-bad", null))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(analyzeForm("5", null, "taobao")) // reviewLength 5 不在 15-300 / 0
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("评价字数需在 15-300 之间，或填 0 不限制");
    }

    @Test
    void analyzeInjectsUserStylePreferencesIntoPrompt() {
        // 先写风格偏好
        client().put().uri("/api/image-analysis/style-preferences")
                .header(header(), sign("user-style", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("preferences", List.of("偏好短句")))
                .exchange()
                .expectStatus().isOk();

        String body = client().post().uri("/api/image-analysis/analyze")
                .header(header(), sign("user-style", null))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(analyzeForm("100", null, "taobao"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"type\":\"result\"");

        ArgumentCaptor<TextCompletionCommand> captor = ArgumentCaptor.forClass(TextCompletionCommand.class);
        verify(ai, Mockito.atLeastOnce()).completeText(captor.capture());
        boolean anyPromptHasStyle = captor.getAllValues().stream()
                .map(TextCompletionCommand::messages)
                .flatMap(List::stream)
                .anyMatch(ImageAnalysisControllerIT::hasImagePartContainingStyle);
        assertThat(anyPromptHasStyle).isTrue();
    }

    // ---------------- draft ----------------

    @Test
    void draftStreamsResultWithoutCharging() {
        String body = client().post().uri("/api/image-analysis/step/draft")
                .header(header(), sign("user-draft", null))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(analyzeForm("100", null, "taobao"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"type\":\"result\"");
        assertThat(body).contains("\"imageCount\":1");
        assertThat(body).endsWith("data: [DONE]\n\n");
        verify(credits, never()).consume(any(), any());
    }

    @Test
    void draftAnonymousCanGenerate() {
        client().post().uri("/api/image-analysis/step/draft")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(analyzeForm("100", null, "taobao"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).value(s -> assertThat(s).contains("\"type\":\"result\""));
    }

    // ---------------- optimize / style-refine ----------------

    @Test
    void optimizeReturnsJsonResultWithoutImageCount() {
        client().post().uri("/api/image-analysis/step/optimize")
                .header(header(), sign("user-opt", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("review", "待优化文案", "reviewLength", 100, "platform", "taobao"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.review").isEqualTo("很不错的商品")
                .jsonPath("$.data.imageCount").doesNotExist();
    }

    @Test
    void optimizeRejectsEmptyReview() {
        client().post().uri("/api/image-analysis/step/optimize")
                .header(header(), sign("user-opt", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("review", "  ", "reviewLength", 100))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void styleRefineReturnsJsonResult() {
        client().post().uri("/api/image-analysis/step/style-refine")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("review", "待调整文案", "reviewLength", 100))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.review").isEqualTo("很不错的商品");
    }

    // ---------------- 风格偏好 ----------------

    @Test
    void stylePreferencesAnonymousReturnsEmptyList() {
        client().get().uri("/api/image-analysis/style-preferences")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.preferences").isArray().jsonPath("$.data.preferences.length()").isEqualTo(0);
    }

    @Test
    void stylePreferencesRoundTrip() {
        client().put().uri("/api/image-analysis/style-preferences")
                .header(header(), sign("user-pref", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("preferences", List.of("偏好短句", "口语化")))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.preferences[0]").isEqualTo("偏好短句");

        client().get().uri("/api/image-analysis/style-preferences")
                .header(header(), sign("user-pref", null))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.preferences.length()").isEqualTo(2);
    }

    @Test
    void stylePreferencesPutRequiresAuth() {
        client().put().uri("/api/image-analysis/style-preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("preferences", List.of("x")))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void stylePreferencesOptimizeCallsLlm() {
        when(ai.completeText(any())).thenReturn(Mono.just("偏好短句\n偏好短句\n口语化"));
        client().post().uri("/api/image-analysis/style-preferences/optimize")
                .header(header(), sign("user-opt-pref", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("preferences", List.of("偏好短句", "偏好短的句子")))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.data.preferences.length()").isEqualTo(3);
        verify(ai).completeText(any());
    }

    @Test
    void saveStyleMemorySkipsLlmWhenOriginalEqualsEdited() {
        StyleSnapshot snap = new StyleSnapshot("评价内容", null, null);
        client().post().uri("/api/image-analysis/save-style-memory")
                .header(header(), sign("user-mem", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("original", snap, "edited", snap))
                .exchange()
                .expectStatus().isOk();
        verify(ai, never()).completeText(any());
    }

    // ---------------- export-feishu ----------------

    @Test
    void exportFeishuMissingCredentialsReturns400() {
        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("review", "评价内容").contentType(MediaType.TEXT_PLAIN);
        client().post().uri("/api/image-analysis/export-feishu")
                .header(header(), sign("user-feishu", null))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(b.build())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("飞书应用凭证未配置，请在设置中填写 App ID 和 App Secret");
    }

    @Test
    void exportFeishuRequiresAuth() {
        MultipartBodyBuilder b = new MultipartBodyBuilder();
        b.part("review", "评价内容").contentType(MediaType.TEXT_PLAIN);
        client().post().uri("/api/image-analysis/export-feishu")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .bodyValue(b.build())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---------------- helpers ----------------

    private String header() {
        return "X-Grassland-Identity";
    }

    private static org.springframework.util.MultiValueMap<String, org.springframework.http.HttpEntity<?>> analyzeForm(
            String reviewLength, String feelings, String platform) {
        MultipartBodyBuilder b = new MultipartBodyBuilder();
        if (reviewLength != null) {
            b.part("reviewLength", reviewLength).contentType(MediaType.TEXT_PLAIN);
        }
        if (feelings != null) {
            b.part("feelings", feelings).contentType(MediaType.TEXT_PLAIN);
        }
        if (platform != null) {
            b.part("platform", platform).contentType(MediaType.TEXT_PLAIN);
        }
        b.part("images", pngResource()).contentType(MediaType.IMAGE_PNG);
        return b.build();
    }

    private static ByteArrayResource pngResource() {
        return new ByteArrayResource(pngBytes()) {
            @Override
            public String getFilename() {
                return "photo.png";
            }
        };
    }

    private static byte[] pngBytes() {
        // PNG signature（8 字节 magic）+ 少量填充。service.validateAndEncode 仅校验 magic。
        return new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x01, 0x02};
    }

    private static boolean hasImagePartContainingStyle(ChatMessage message) {
        if (!message.multimodal() || message.parts() == null) {
            return false;
        }
        boolean hasImage = false;
        boolean hasStyle = false;
        for (ContentPart part : message.parts()) {
            if (part instanceof ContentPart.Image) {
                hasImage = true;
            } else if (part instanceof ContentPart.Text t && t.text().contains("用户个人风格偏好")) {
                hasStyle = true;
            }
        }
        return hasImage && hasStyle;
    }
}
