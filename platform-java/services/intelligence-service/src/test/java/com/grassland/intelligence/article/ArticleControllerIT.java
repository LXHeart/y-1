package com.grassland.intelligence.article;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatChunk;
import com.grassland.intelligence.ai.TextRunCommand;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.InsufficientCreditsException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 文章三端点端到端（草场 intelligence Slice 3）：titles（扣积分+聚合解析 JSON）/ outline / content（免费 SSE）。
 * 关键不变量：仅 titles 扣积分；outline/content 不扣。
 */
class ArticleControllerIT extends IntelligenceItSupport {

    @MockitoBean
    private AiCapabilityAdapter ai;

    @MockitoBean
    private CreditsClient credits;

    @BeforeEach
    void stubDefaults() {
        reset(ai, credits);
        when(credits.consume(any(), any())).thenReturn(Mono.empty());
    }

    private String signed() {
        return sign(UUID.randomUUID().toString(), "recommender");
    }

    // ---------- titles ----------

    @Test
    @DisplayName("titles 无断言 → 401；题材空 → 400；均不扣积分")
    void titlesAuthAndValidation() {
        client().post().uri("/api/article-generation/titles")
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "职场"))
                .exchange().expectStatus().isUnauthorized();
        verify(credits, never()).consume(any(), any());

        client().post().uri("/api/article-generation/titles")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "  "))
                .exchange().expectStatus().isBadRequest();
        verify(credits, never()).consume(any(), any());
    }

    @Test
    @DisplayName("titles 积分不足 → 402，不调 AI")
    void titlesInsufficientCredits() {
        when(credits.consume(any(), any())).thenReturn(Mono.error(new InsufficientCreditsException()));
        client().post().uri("/api/article-generation/titles")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "职场", "platform", "wechat"))
                .exchange().expectStatus().isEqualTo(402);
        verify(ai, never()).startTextRun(any());
    }

    @Test
    @DisplayName("titles 成功 → 扣 article_generation + 聚合流式（跨块 code fence）→ 解析 {title,hook}")
    void titlesAggregatesAndParses() {
        ArgumentCaptor<CreditFeature> featureCaptor = ArgumentCaptor.forClass(CreditFeature.class);
        when(credits.consume(any(), featureCaptor.capture())).thenReturn(Mono.empty());

        // 模型分块流式输出，拼起来是带 ```json fence 的 JSON（覆盖剥 fence + 跨块拼接）。
        when(ai.startTextRun(any())).thenReturn(Flux.just(
                new ChatChunk("```json\n"),
                new ChatChunk("{\"titles\":[{\"title\":\"爆款\",\"hook\":\"好奇\"}]}"),
                new ChatChunk("\n```")));

        client().post().uri("/api/article-generation/titles")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "职场", "platform", "wechat"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.titles[0].title").isEqualTo("爆款")
                .jsonPath("$.data.titles[0].hook").isEqualTo("好奇");

        assertThat(featureCaptor.getValue()).isEqualTo(CreditFeature.ARTICLE_GENERATION);
    }

    @Test
    @DisplayName("titles 返回非 JSON → 502（解析失败透传，不落 500）")
    void titlesUnparseableReturns502() {
        when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk("这不是JSON")));
        client().post().uri("/api/article-generation/titles")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "职场"))
                .exchange().expectStatus().isEqualTo(502);
    }

    // ---------- outline / content：免费 SSE ----------

    @Test
    @DisplayName("outline 成功 → 免费 SSE（不扣积分）；prompt 含主题/标题")
    void outlineFreeStream() {
        ArgumentCaptor<TextRunCommand> cmdCaptor = ArgumentCaptor.forClass(TextRunCommand.class);
        when(ai.startTextRun(cmdCaptor.capture())).thenReturn(Flux.just(new ChatChunk("# 一、开头")));

        byte[] body = client().post().uri("/api/article-generation/outline")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "职场", "title", "打工人的清晨", "platform", "wechat"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody().returnResult().getResponseBody();

        assertThat(new String(body, UTF_8)).isEqualTo("data: {\"content\":\"# 一、开头\"}\n\ndata: [DONE]\n\n");
        verify(credits, never()).consume(any(), any());   // outline 免费
        TextRunCommand cmd = cmdCaptor.getValue();
        assertThat(cmd.messages().get(0).content()).contains("大纲");
        assertThat(cmd.messages().get(1).content()).contains("主题：职场").contains("标题：打工人的清晨");
    }

    @Test
    @DisplayName("content 成功 → 免费 SSE；prompt 含大纲")
    void contentFreeStream() {
        ArgumentCaptor<TextRunCommand> cmdCaptor = ArgumentCaptor.forClass(TextRunCommand.class);
        when(ai.startTextRun(cmdCaptor.capture())).thenReturn(Flux.just(new ChatChunk("正文段落")));

        client().post().uri("/api/article-generation/content")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(outlineBody())
                .exchange().expectStatus().isOk()
                .expectHeader().valueEquals("X-Accel-Buffering", "no");

        verify(credits, never()).consume(any(), any());   // content 免费
        assertThat(cmdCaptor.getValue().messages().get(1).content()).contains("大纲：").contains("一、开头");
    }

    @Test
    @DisplayName("content 大纲过短 → 400")
    void contentShortOutline() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", "职场");
        body.put("title", "标题");
        body.put("outline", "短");
        client().post().uri("/api/article-generation/content")
                .header("X-Grassland-Identity", signed())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isBadRequest();
    }

    private static Map<String, Object> outlineBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", "职场");
        body.put("title", "打工人的清晨");
        body.put("outline", "一、开头引子\n二、展开吐槽\n三、收尾升华");
        body.put("platform", "wechat");
        return body;
    }
}
