package com.grassland.intelligence.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.TextCompletionCommand;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 五条流内联安全检查帧端到端（任务书 #34 B3 / ADR-D16 D8）：每条流「生成 → 流尾 safety 帧」。
 * 任务书指定 mock 生成边界（AiCapabilityAdapter/CreditsClient 替身，真 controller + 真 L1 检查）；
 * 深检默认未配置（无 platform_model_config）→ safety 帧 deepCheck:false，验证「生成零影响」基线。
 */
class ContentSafetyStreamIntegrationIT extends IntelligenceItSupport {

    private static final String H = "X-Grassland-Identity";
    /** 各流生成的文本（含三类命中词：极限词/违规承诺/导流）。 */
    private static final String GENERATED = "这家店的甜品全城最好吃，无效退款，加微信 sweet8888";

    @MockitoBean
    private AiCapabilityAdapter ai;

    @MockitoBean
    private CreditsClient credits;

    @BeforeEach
    void stubs() {
        Mockito.reset(ai, credits);
        CreditsStubs.stubDefaults(credits);
        db.sql("DELETE FROM platform_model_config WHERE capability = 'content_safety'").then().block();
        // 流式（chunk）与非流式（聚合）两种 adapter 形态都产出同一文本
        when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk(GENERATED)));
        when(ai.completeText(any(TextCompletionCommand.class)))
                .thenReturn(Mono.just(GENERATED));
        when(ai.completeMultimodal(any(), any(Duration.class))).thenReturn(Mono.just(GENERATED));
    }

    private String run(String uri, Map<String, Object> body) {
        return new String(client().post().uri(uri)
                .header(H, sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody());
    }

    @Test
    @DisplayName("文章正文（SSE chunk 流）：流尾追加 safety 帧，findings 命中三类 + deepCheck:false")
    void articleContentStreamAppendsSafetyFrame() {
        String stream = run("/api/article-generation/content", Map.of(
                "topic", "甜品探店", "title", "测试标题",
                "outline", "一、开店背景二、产品介绍三、总结", "platform", "xiaohongshu"));
        assertThat(stream).contains("\"content\"");
        assertThat(stream).contains("\"type\":\"safety\"");
        assertThat(stream).contains("absolute_claims").contains("false_promises").contains("diversion");
        assertThat(stream).contains("\"deepCheck\":false");
        assertThat(stream).endsWith("data: [DONE]\n\n");
    }

    @Test
    @DisplayName("文章标题（非流式 JSON）：data.safety 内嵌（短文本仅 L1）")
    void articleTitlesEmbedSafetyInData() {
        String titlesPayload = "{\"titles\":[{\"title\":\"" + GENERATED + "\",\"hook\":\"快来\"}]}";
        when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk("```json\n"),
                new ChatChunk(titlesPayload), new ChatChunk("\n```")));
        client().post().uri("/api/article-generation/titles")
                .header(H, sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "甜品探店"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.titles[0].title").exists()
                .jsonPath("$.data.safety.deepCheck").isEqualTo(false)
                .jsonPath("$.data.safety.lexiconVersion").isEqualTo("lexicon-v1")
                .jsonPath("$.data.safety.findings[?(@.category=='absolute_claims')]").exists();
    }

    @Test
    @DisplayName("喜剧脚本（SSE）：safety 帧追加且生成主帧完整保留")
    void comedyScriptStreamAppendsSafetyFrame() {
        String stream = run("/api/comedy-generation/generate-script",
                Map.of("topic", "探店", "duration", 60));
        assertThat(stream).contains("\"content\"");
        assertThat(stream).contains("\"type\":\"safety\"");
        assertThat(stream).contains("absolute_claims");
    }

    @Test
    @DisplayName("视频脚本（SSE）：safety 帧追加")
    void videoScriptStreamAppendsSafetyFrame() {
        String stream = run("/api/video-production/generate-script", Map.of(
                "videoStyle", "烟火纪实", "industryType", "餐饮", "shopName", "甜品店",
                "targetPlatform", "douyin", "images", java.util.List.of(
                        "data:image/jpeg;base64,/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwcJC4nICIsIxwcKDcpLDA1NDQ0Hyc5PTgyPDs0NDT/wAARCAABAAEDASIAAhEBAxEB/8QAFQABAQAAAAAAAAAAAAAAAAAAAAv/xAAUEAEAAAAAAAAAAAAAAAAAAAAA/8QAFQEBAQAAAAAAAAAAAAAAAAAAAAX/xAAUEQEAAAAAAAAAAAAAAAAAAAAA/9oADAMBAAIRAxEAPwA/8A/9k=")));
        assertThat(stream).contains("\"type\":\"safety\"");
        assertThat(stream).contains("diversion");
    }

    @Test
    @DisplayName("朋友圈文案（JSON 请求 SSE）：copy 提取 → safety 帧追加")
    void momentsStreamAppendsSafetyFrame() {
        // moments 的 completeText 需要 {copy,...} JSON（parseResult 校验）——单独 stub。
        when(ai.completeText(any(TextCompletionCommand.class)))
                .thenReturn(Mono.just("{\"copy\":\"" + GENERATED + "\",\"imageOrder\":[],\"captions\":[]}"));
        String stream = run("/api/moments-generation/generate",
                Map.of("topic", "甜品店开业", "style", "lifestyle"));
        // moments 独立模式经 completeText（非流式）→ service result 帧 copy → controller 流尾 safety 帧
        assertThat(stream).contains("\"type\":\"safety\"");
        assertThat(stream).contains("absolute_claims");
    }

    @Test
    @DisplayName("生成失败：error 帧保留、safety 不掩盖错误（深检/安全链零影响主结果）")
    void generationErrorNotMaskedBySafety() {
        when(ai.startTextRun(any())).thenReturn(Flux.error(new RuntimeException("upstream down")));
        String stream = run("/api/comedy-generation/generate-script",
                Map.of("topic", "探店", "duration", 60));
        assertThat(stream).contains("error");
        assertThat(stream).endsWith("data: [DONE]\n\n");
    }
}
