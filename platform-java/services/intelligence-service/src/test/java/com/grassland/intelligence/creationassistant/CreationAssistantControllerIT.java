package com.grassland.intelligence.creationassistant;

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
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.credits.InsufficientCreditsException;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 智能创作助手集成测试（草场 PRD §4.9.4/§4.9.6 / Slice 15 Stage 2）。复用 {@link IntelligenceItSupport}
 *（testcontainers postgres + 真实断言签名）。Ai 能力与积分用 {@link MockitoBean} 隔离，
 * 断言真实 controller 编排（断言→扣费→prompt 组装→SSE 帧）。
 *
 * <p>锁定：评分（聚合 JSON → 逐维度 SSE 帧）、建议（纯流式）、扣 CREATION_ASSISTANT、积分不足 402、
 * 上游失败退款、参数校验。
 */
class CreationAssistantControllerIT extends IntelligenceItSupport {

    @MockitoBean
    private AiCapabilityAdapter ai;
    @MockitoBean
    private CreditsClient credits;

    private String header() {
        return "X-Grassland-Identity";
    }

    @BeforeEach
    void resetMocks() {
        reset(ai, credits);
        CreditsStubs.stubDefaults(credits);
    }

    // ---------------- score ----------------

    @Test
    void scoreParsesDimensionsAndStreamsFrames() {
        ArgumentCaptor<CreditFeature> featureCaptor = ArgumentCaptor.forClass(CreditFeature.class);
        when(credits.consume(any(), featureCaptor.capture())).thenAnswer(inv ->
                CreditsStubs.charge(inv.getArgument(0), inv.getArgument(1)));
        // LLM 返回评分 JSON
        when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk(
                "{\"dimensions\":["
                + "{\"dimension\":\"title_appeal\",\"score\":8,\"advice\":\"标题有吸引力\"},"
                + "{\"dimension\":\"structure\",\"score\":6,\"advice\":\"开头可更抓人\"}"
                + "],\"overall\":7}")));

        byte[] body = client().post().uri("/api/creation-assistant/score")
                .header(header(), sign("user-score", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "这是一段需要评分的测试内容，至少十个字", "platform", "xiaohongshu"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody().returnResult().getResponseBody();

        String sse = new String(body, UTF_8);
        // 逐维度帧 + overall 帧 + [DONE]
        assertThat(sse).contains("\"type\":\"score\"");
        assertThat(sse).contains("\"dimension\":\"title_appeal\"");
        assertThat(sse).contains("\"dimension\":\"structure\"");
        assertThat(sse).contains("\"type\":\"overall\"");
        assertThat(sse).endsWith("data: [DONE]\n\n");
        // 扣的是智能助手功能键
        assertThat(featureCaptor.getValue()).isEqualTo(CreditFeature.CREATION_ASSISTANT);
        // 不退款（成功）
        verify(credits, never()).refund(any(), any());
    }

    @Test
    void scoreRefundsOnUpstreamFailure() {
        when(credits.consume(any(), any())).thenReturn(
                CreditsStubs.charge("user-fail", CreditFeature.CREATION_ASSISTANT));
        when(ai.startTextRun(any())).thenReturn(Flux.error(new RuntimeException("LLM 不可用")));

        client().post().uri("/api/creation-assistant/score")
                .header(header(), sign("user-fail", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "这是一段需要评分的测试内容，至少十个字"))
                .exchange()
                .expectStatus().is5xxServerError();

        // 上游失败 → 退款
        verify(credits).refund(any(), any());
    }

    @Test
    void scoreRejectsShortContent() {
        client().post().uri("/api/creation-assistant/score")
                .header(header(), sign("user-short", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "太短"))
                .exchange()
                .expectStatus().is4xxClientError();
        verify(credits, never()).consume(any(), any());
    }

    // ---------------- suggest ----------------

    @Test
    void suggestStreamsChunksAndChargesCreationAssistant() {
        ArgumentCaptor<CreditFeature> featureCaptor = ArgumentCaptor.forClass(CreditFeature.class);
        when(credits.consume(any(), featureCaptor.capture())).thenAnswer(inv ->
                CreditsStubs.charge(inv.getArgument(0), inv.getArgument(1)));
        when(ai.startTextRun(any())).thenReturn(
                Flux.just(new ChatChunk("亮点："), new ChatChunk("开头生动。")));

        byte[] body = client().post().uri("/api/creation-assistant/suggest")
                .header(header(), sign("user-suggest", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "这是一段需要优化建议的测试内容，至少十个字", "platform", "zhihu"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody().returnResult().getResponseBody();

        String sse = new String(body, UTF_8);
        assertThat(sse).isEqualTo(
                "data: {\"content\":\"亮点：\"}\n\n"
                + "data: {\"content\":\"开头生动。\"}\n\n"
                + "data: [DONE]\n\n");
        assertThat(featureCaptor.getValue()).isEqualTo(CreditFeature.CREATION_ASSISTANT);
    }

    @Test
    void insufficientCreditsReturnsErrorWithoutCallingAi() {
        when(credits.consume(any(), any())).thenReturn(Mono.error(new InsufficientCreditsException()));

        client().post().uri("/api/creation-assistant/suggest")
                .header(header(), sign("user-broke", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "这是一段需要优化建议的测试内容，至少十个字"))
                .exchange()
                .expectStatus().is4xxClientError();

        verify(ai, never()).startTextRun(any());
    }

    @Test
    void requiresAuthentication() {
        client().post().uri("/api/creation-assistant/score")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "这是一段需要评分的测试内容，至少十个字"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---------------- guide（§4.9.1/§4.9.2）----------------

    @Test
    void guideAskStreamsQuestionWhenInfoInsufficient() {
        ArgumentCaptor<CreditFeature> featureCaptor = ArgumentCaptor.forClass(CreditFeature.class);
        when(credits.consume(any(), featureCaptor.capture())).thenAnswer(inv ->
                CreditsStubs.charge(inv.getArgument(0), inv.getArgument(1)));
        when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk(
                "{\"action\":\"ask\",\"question\":\"你想发布到哪个平台？\"}")));

        byte[] body = client().post().uri("/api/creation-assistant/guide")
                .header(header(), sign("user-guide", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userInput", "想写一篇探店笔记"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody().returnResult().getResponseBody();

        String sse = new String(body, UTF_8);
        assertThat(sse).contains("\"type\":\"ask\"");
        assertThat(sse).contains("你想发布到哪个平台");
        assertThat(sse).endsWith("data: [DONE]\n\n");
        assertThat(featureCaptor.getValue()).isEqualTo(CreditFeature.CREATION_ASSISTANT);
    }

    @Test
    void guideBriefMarksInferredFields() {
        when(credits.consume(any(), any())).thenAnswer(inv ->
                CreditsStubs.charge(inv.getArgument(0), inv.getArgument(1)));
        when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk(
                "{\"action\":\"brief\",\"brief\":{\"angle\":\"探店种草\",\"audience\":\"年轻白领\","
                + "\"structure\":\"开头钩子+菜品+环境+地址\",\"inferredFields\":[\"audience\",\"style\"]}}")));

        byte[] body = client().post().uri("/api/creation-assistant/guide")
                .header(header(), sign("user-brief", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userInput", "想写一篇小红书探店笔记", "platform", "xiaohongshu",
                        "history", "已选小红书，主题探店"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        String sse = new String(body, UTF_8);
        assertThat(sse).contains("\"type\":\"brief\"");
        assertThat(sse).contains("探店种草");
        // §4.9.2 推测标记：inferredFields 列出 AI 推测的字段
        assertThat(sse).contains("inferredFields");
        assertThat(sse).contains("audience,style");
    }

    @Test
    void guideRejectsEmptyInput() {
        client().post().uri("/api/creation-assistant/guide")
                .header(header(), sign("user-empty", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userInput", ""))
                .exchange()
                .expectStatus().is4xxClientError();
        verify(credits, never()).consume(any(), any());
    }

    // ---------------- task-coverage（§4.9.3）----------------

    @Test
    void taskCoverageStreamsGapsWhenRequirementsUnmet() {
        when(credits.consume(any(), any())).thenAnswer(inv ->
                CreditsStubs.charge(inv.getArgument(0), inv.getArgument(1)));
        when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk(
                "{\"covered\":false,\"gaps\":["
                + "{\"requirement\":\"必须提到门店地址\",\"status\":\"missing\",\"hint\":\"结尾加地址\"},"
                + "{\"requirement\":\"带3张以上配图\",\"status\":\"weak\",\"hint\":\"补图\"}"
                + "]}")));

        byte[] body = client().post().uri("/api/creation-assistant/task-coverage")
                .header(header(), sign("user-cov", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "content", "这家店不错，菜品新鲜，推荐大家来试试。",
                        "taskRequirements", "必须提到门店地址；带3张以上配图；200字以上"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody().returnResult().getResponseBody();

        String sse = new String(body, UTF_8);
        assertThat(sse).contains("\"type\":\"gap\"");
        assertThat(sse).contains("必须提到门店地址");
        assertThat(sse).contains("带3张以上配图");
        assertThat(sse).contains("\"type\":\"covered\"");
        assertThat(sse).contains("\"covered\":false");
    }

    @Test
    void taskCoverageReportsAllCovered() {
        when(credits.consume(any(), any())).thenAnswer(inv ->
                CreditsStubs.charge(inv.getArgument(0), inv.getArgument(1)));
        when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk(
                "{\"covered\":true,\"gaps\":[]}")));

        byte[] body = client().post().uri("/api/creation-assistant/task-coverage")
                .header(header(), sign("user-covered", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "content", "门店在南京路1号，菜品新鲜环境好，推荐大家来试试。",
                        "taskRequirements", "提到门店地址"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        String sse = new String(body, UTF_8);
        assertThat(sse).contains("\"covered\":true");
        // 无 gap 帧
        assertThat(sse).doesNotContain("\"type\":\"gap\"");
    }

    @Test
    void taskCoverageRejectsMissingRequirements() {
        client().post().uri("/api/creation-assistant/task-coverage")
                .header(header(), sign("user-noreq", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("content", "这是一段足够长的内容用于测试，至少十个字"))
                .exchange()
                .expectStatus().is4xxClientError();
        verify(credits, never()).consume(any(), any());
    }

    // ---------------- topic-from-hot（§4.9.5）----------------

    @Test
    void topicFromHotStructuresTitleIntoAngleThesisAudience() {
        ArgumentCaptor<CreditFeature> featureCaptor = ArgumentCaptor.forClass(CreditFeature.class);
        when(credits.consume(any(), featureCaptor.capture())).thenAnswer(inv ->
                CreditsStubs.charge(inv.getArgument(0), inv.getArgument(1)));
        when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk(
                "{\"topic\":\"打工人早餐新选择\",\"angle\":\"平价高效\","
                + "\"thesis\":\"5分钟搞定营养早餐\",\"audience\":\"通勤白领\","
                + "\"entryPoints\":[\"时间对比\",\"营养搭配\",\"价格真相\"]}")));

        byte[] body = client().post().uri("/api/creation-assistant/topic-from-hot")
                .header(header(), sign("user-hot", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("hotTitle", "打工人早餐调查", "platform", "xiaohongshu"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody().returnResult().getResponseBody();

        String sse = new String(body, UTF_8);
        assertThat(sse).contains("\"type\":\"topic\"");
        assertThat(sse).contains("打工人早餐新选择");
        assertThat(sse).contains("通勤白领");
        // entryPoints 是结构化切入点，而非纯字符串 topic
        assertThat(sse).contains("时间对比");
        assertThat(sse).contains("价格真相");
        assertThat(featureCaptor.getValue()).isEqualTo(CreditFeature.CREATION_ASSISTANT);
    }

    @Test
    void topicFromHotRejectsEmptyTitle() {
        client().post().uri("/api/creation-assistant/topic-from-hot")
                .header(header(), sign("user-notitle", null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("hotTitle", ""))
                .exchange()
                .expectStatus().is4xxClientError();
        verify(credits, never()).consume(any(), any());
    }
}
