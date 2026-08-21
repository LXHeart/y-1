package com.grassland.intelligence.moments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.TextCompletionCommand;
import com.grassland.intelligence.ai.run.FrozenTextExecutionService;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.credits.InsufficientCreditsException;
import com.grassland.intelligence.creationcontext.CreationContextSnapshot;
import com.grassland.intelligence.creationcontext.CreationContextSnapshotRepository;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 朋友圈内容生成端到端（PRD §4.4 图片+文字）。复用 {@link IntelligenceItSupport}
 * （testcontainers postgres + 真实断言签名）。Ai 能力、积分与冻结执行用 {@link MockitoBean} 隔离，
 * 任务模式走真实 {@link MomentsTaskCreationContext} DB 绑定。
 *
 * <p>锁定：401 无断言、400 风格/任务模式校验、402 积分不足（不流式）、
 * 200 SSE（progress/result/[DONE]、扣 moments_generation、多模态 prompt、图片校验 400）、
 * 上游失败退款 + error 帧、任务模式绑定 moments+image-text 快照（跨账号/错形式 fail-closed）。
 */
class MomentsGenerationControllerIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "43434343-4343-4343-4343-434343434343";
    private static final String OTHER = "44444444-4444-4444-4444-444444444444";
    private static final byte[] PNG_MAGIC = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};

    @MockitoBean
    private AiCapabilityAdapter ai;
    @MockitoBean
    private CreditsClient credits;
    @MockitoBean
    private FrozenTextExecutionService frozenText;

    @Autowired
    private CreationContextSnapshotRepository snapshots;

    @BeforeEach
    void resetMocks() {
        reset(ai, credits, frozenText);
        CreditsStubs.stubDefaults(credits);
        db.sql("DELETE FROM creation_context_snapshot").then().block();
    }

    @Test
    @DisplayName("无断言 → 401，不扣积分")
    void unauthenticatedRejected() {
        client().post().uri("/api/moments-generation/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("lifestyle"))
                .exchange().expectStatus().isUnauthorized();
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("未知风格 → 400，不扣积分")
    void invalidStyleRejected() {
        client().post().uri("/api/moments-generation/generate")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("viral"))
                .exchange().expectStatus().isBadRequest();
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("素材图 magic byte 不匹配 → 400 JSON（SSE 前），不扣积分")
    void invalidImageRejectedBeforeCharge() {
        String fakeJpeg = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(PNG_MAGIC);
        client().post().uri("/api/moments-generation/generate")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "开业", "style", "lifestyle", "images", List.of(fakeJpeg)))
                .exchange().expectStatus().isBadRequest();
        verify(credits, never()).consume(any(), any());
        verify(ai, never()).completeText(any());
    }

    @Test
    @DisplayName("积分不足 → 402，不发 SSE、不调 AI（拒绝经执行环 prepare 段透传）")
    void insufficientCreditsRejected() {
        when(frozenText.executeIndependent(any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.error(new com.grassland.intelligence.security.IntelligenceException(402, "积分不足")));
        client().post().uri("/api/moments-generation/generate")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("lifestyle"))
                .exchange().expectStatus().isEqualTo(402);
        verify(ai, never()).completeText(any());
    }

    @Test
    @DisplayName("成功 → 200 SSE：经执行环（MOMENTS_GENERATION）+ 多模态 prompt + progress/result/[DONE]")
    void streamsMomentsResult() {
        // GL-P3-AI-001 尾巴清偿：独立模式经执行环（扣分/退款/预算在环内，此处桩环出口）；
        // 消息断言从环入口（executeIndependent messages）捕获。
        ArgumentCaptor<List<com.grassland.intelligence.ai.ChatMessage>> msgCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        when(frozenText.executeIndependent(any(), msgCaptor.capture(), anyInt(),
                org.mockito.ArgumentMatchers.eq(CreditFeature.MOMENTS_GENERATION), any()))
                .thenReturn(Mono.just(traced(new MomentsGenerationService.MomentsResult(
                        "开业大吉，周末来店里坐坐☕",
                        List.of(new MomentsGenerationService.OrderSuggestion(1, "封面招牌")),
                        List.of(new MomentsGenerationService.Caption(1, "门店招牌"))))));

        client().post().uri("/api/moments-generation/generate")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("store-visit"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectHeader().valueEquals("X-Accel-Buffering", "no")
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("\"type\":\"progress\"");
                    assertThat(body).contains("\"type\":\"result\"");
                    assertThat(body).contains("开业大吉，周末来店里坐坐");
                    assertThat(body).contains("\"imageOrder\":[{\"index\":1,\"reason\":\"封面招牌\"}]");
                    assertThat(body).contains("data: [DONE]");
                });

        List<com.grassland.intelligence.ai.ChatMessage> messages = msgCaptor.getValue();
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).content())
                .contains("到店体验")
                .contains("不使用话题标签")
                .contains("\"copy\"");
        assertThat(messages.get(1).content()).contains("开业");
        assertThat(messages.get(1).multimodal()).isFalse();
    }

    /** 独立模式执行环出口桩（Traced 元数据对齐平台默认）。 */
    private static <T> com.grassland.intelligence.ai.run.FrozenTextExecutionService.Traced<T> traced(T value) {
        return new com.grassland.intelligence.ai.run.FrozenTextExecutionService.Traced<>(
                value, null, "qwen", "qwen-plus", 1, false);
    }

    @Test
    @DisplayName("带素材图 → 多模态 user 消息（image part + 文本 part，经执行环入口捕获）")
    void sendsImagesAsMultimodalParts() {
        ArgumentCaptor<List<com.grassland.intelligence.ai.ChatMessage>> msgCaptor =
                ArgumentCaptor.forClass((Class) List.class);
        when(frozenText.executeIndependent(any(), msgCaptor.capture(), anyInt(), any(), any()))
                .thenReturn(Mono.just(traced(new MomentsGenerationService.MomentsResult(
                        "配图版文案", List.of(), List.of()))));

        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG_MAGIC);
        client().post().uri("/api/moments-generation/generate")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "开业", "style", "friends-share", "images", List.of(dataUrl)))
                .exchange().expectStatus().isOk();

        assertThat(msgCaptor.getValue().get(1).multimodal()).isTrue();
        assertThat(msgCaptor.getValue().get(1).parts()).hasSize(2);
    }

    @Test
    @DisplayName("上游失败 → 502 JSON 先于 SSE（退款在执行环内闭环）")
    void upstreamFailureFailsBeforeSse() {
        when(frozenText.executeIndependent(any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("upstream down")));

        client().post().uri("/api/moments-generation/generate")
                .header("X-Grassland-Identity", sign(ACCOUNT, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request("lifestyle"))
                .exchange()
                .expectStatus().isEqualTo(502);
    }

    @Test
    @DisplayName("任务模式 → 绑定 moments+image-text 快照，冻结执行带上下文与 MOMENTS_GENERATION")
    void taskModeBindsFrozenContext() {
        String snapshotId = seedSnapshot(ACCOUNT, "moments", "image-text");
        when(frozenText.executeTraced(any(), any(UUID.class), any(), anyInt(), any(CreditFeature.class),
                any(Function.class)))
                .thenReturn(Mono.just(new com.grassland.intelligence.ai.run.FrozenTextExecutionService.Traced<>(
                        new MomentsGenerationService.MomentsResult("任务朋友圈文案", List.of(), List.of()),
                        null, "qwen", "qwen-plus", 1, false)));

        client().post().uri("/api/moments-generation/generate")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "任务主题", "style", "event",
                        "taskMode", true, "contextSnapshotId", snapshotId))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("任务朋友圈文案");
                    assertThat(body).contains("data: [DONE]");
                });

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<com.grassland.intelligence.ai.ChatMessage>> messagesCaptor =
                ArgumentCaptor.forClass((Class<List<com.grassland.intelligence.ai.ChatMessage>>) (Class<?>) List.class);
        verify(frozenText).executeTraced(any(), eq(UUID.fromString(snapshotId)), messagesCaptor.capture(),
                anyInt(), eq(CreditFeature.MOMENTS_GENERATION), any());
        assertThat(messagesCaptor.getValue()).hasSize(3);
        assertThat(messagesCaptor.getValue().get(0).content()).contains("朋友圈图文任务上下文");
        assertThat(messagesCaptor.getValue().get(1).content()).contains("活动通知");
        // 任务模式积分由冻结执行闭环，控制器不手动扣
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("任务模式 fail-closed：跨账号 403 / 非 image-text 快照 409")
    void taskModeFailsClosed() {
        String own = seedSnapshot(ACCOUNT, "moments", "image-text");
        String graphic = seedSnapshot(ACCOUNT, "moments", "graphic");
        String foreign = seedSnapshot(OTHER, "moments", "image-text");

        postTask(ACCOUNT, foreign).expectStatus().isForbidden();
        postTask(ACCOUNT, graphic).expectStatus().isEqualTo(409);
        postTask(ACCOUNT, null).expectStatus().isBadRequest();
        verify(ai, never()).completeText(any());
        verify(frozenText, never()).execute(any(), any(), any(), anyInt(), any(), any());
    }

    // ---------------- helpers ----------------

    private static Map<String, Object> request(String style) {
        return Map.of("topic", "开业大吉", "style", style);
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec postTask(
            String accountId, String snapshotId) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("topic", "任务主题");
        body.put("style", "event");
        body.put("taskMode", true);
        if (snapshotId != null) {
            body.put("contextSnapshotId", snapshotId);
        }
        return client().post().uri("/api/moments-generation/generate")
                .header("X-Grassland-Identity", sign(accountId, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private String seedSnapshot(String accountId, String platform, String contentForm) {
        CreationContextSnapshot created = snapshots.create(new CreationContextSnapshot(
                null, accountId, "organization-1", "task-1", "application-1", 3,
                platform, contentForm,
                Map.of("taskId", "task-1", "applicationId", "application-1",
                        "taskVersion", 3, "title", "朋友圈任务"),
                Map.of("version", "test", "platform", platform, "contentForm", contentForm),
                Map.of("items", List.of()),
                Map.of("resolutionType", "PLATFORM", "provider", "qwen", "model", "qwen-plus"),
                Map.of(),
                null)).block();
        assert created != null;
        return created.id().toString();
    }
}
