package com.grassland.intelligence.creationlineage;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.ai.AiCapabilityAdapter;
import com.grassland.intelligence.ai.ChatChunk;
import static org.mockito.ArgumentMatchers.anyInt;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 文本创作流 lineage 端到端（任务书 #44 登记扩展）：文章正文/喜剧脚本/AI 中心引导的独立模式生成完成后
 * 落 {@code creation_generation}（kind/mode/prompt/result）；SSE 帧契约不因 lineage 改变。
 */
class TextCreationLineageIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "51515151-5151-5151-5151-515151515151";

    @MockitoBean
    private AiCapabilityAdapter ai;

    @MockitoBean
    private com.grassland.intelligence.ai.run.FrozenTextExecutionService frozenText;

    @MockitoBean
    private CreditsClient credits;

    @BeforeEach
    void stubDefaults() {
        reset(ai, credits);
        CreditsStubs.stubDefaults(credits);
        db.sql("DELETE FROM creation_generation WHERE owner_account_id = :owner")
                .bind("owner", ACCOUNT).then().block();
    }

    @Test
    @DisplayName("文章正文独立模式 → 流式输出后落 kind=article 行（prompt/result 摘要）")
    void articleIndependentStreamRecordsLineage() {
        when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk("开头"), new ChatChunk("结尾")));

        byte[] body = client().post().uri("/api/article-generation/content")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "露营咖啡", "title", "城市里的荒野", "outline",
                        "一、出发二、扎营三、回城"))
                .exchange().expectStatus().isOk()
                .expectBody().returnResult().getResponseBody();

        assertThat(new String(body, UTF_8)).contains("data: {\"content\":\"开头\"}");
        Map<String, Object> row = lineageRow("article");
        assertThat(row.get("mode")).isEqualTo("independent");
        assertThat((String) row.get("prompt_text")).contains("露营咖啡").contains("城市里的荒野");
        assertThat(row.get("provider").toString()).isEqualTo("qwen");
        assertThat((String) row.get("result")).contains("\"contentLength\": 4");
    }

    @Test
    @DisplayName("喜剧脚本独立模式 → 落 kind=comedy_script 行")
    void comedyIndependentStreamRecordsLineage() {
        when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk("大家好，今天聊聊加班。")));

        client().post().uri("/api/comedy-generation/generate-script")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("topic", "加班", "duration", 60))
                .exchange().expectStatus().isOk().expectBody(String.class).consumeWith(
                        result -> assertThat(result.getResponseBody()).contains("data: [DONE]"));

        Map<String, Object> row = lineageRow("comedy_script");
        assertThat(row.get("mode")).isEqualTo("independent");
        assertThat((String) row.get("prompt_text")).contains("加班");
        assertThat((String) row.get("input_summary")).contains("\"durationSeconds\": 60");
        assertThat((String) row.get("result")).contains("\"contentLength\": 11");
    }

    @Test
    @DisplayName("AI 中心引导流 → 落 kind=assistant_guide 行")
    void assistantGuideRecordsLineage() {
        when(ai.startTextRun(any())).thenReturn(Flux.just(new ChatChunk(
                "{\"action\":\"ask\",\"question\":\"目标平台是？\",\"inferred\":{}}")));

        client().post().uri("/api/creation-assistant/guide")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userInput", "想写一篇咖啡探店文", "platform", "wechat"))
                .exchange().expectStatus().isOk().expectBody(String.class).consumeWith(
                        result -> assertThat(result.getResponseBody()).contains("data:"));

        Map<String, Object> row = lineageRow("assistant_guide");
        assertThat(row.get("mode")).isEqualTo("independent");
        assertThat((String) row.get("prompt_text")).contains("想写一篇咖啡探店文");
        assertThat((String) row.get("input_summary")).contains("\"platform\": \"wechat\"");
    }

    @Test
    @DisplayName("朋友圈独立模式 → 落 kind=moments_copy 行（result 含 copy 全文）")
    void momentsIndependentRecordsLineage() {
        // GL-P3-AI-001 尾巴清偿后独立朋友圈走执行环：桩环出口返回已解析结果
        when(frozenText.executeIndependent(any(), any(), anyInt(), any(), any()))
                .thenReturn(Mono.just(new com.grassland.intelligence.ai.run.FrozenTextExecutionService.Traced<>(
                        new com.grassland.intelligence.moments.MomentsGenerationService.MomentsResult(
                                "开业八折，欢迎来坐坐", List.of(), List.of()),
                        null, "qwen", "qwen-plus", 1, false)));

        client().post().uri("/api/moments-generation/generate")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("topic", "新店开业", "style", "event"))
                .exchange().expectStatus().isOk().expectBody(String.class).consumeWith(
                        result -> assertThat(result.getResponseBody()).contains("data: [DONE]"));

        Map<String, Object> row = lineageRow("moments_copy");
        assertThat(row.get("mode")).isEqualTo("independent");
        assertThat((String) row.get("result")).contains("开业八折，欢迎来坐坐");
        assertThat((String) row.get("input_summary")).contains("\"style\": \"event\"");
    }

    private Map<String, Object> lineageRow(String kind) {
        Map<String, Object> row = db.sql("""
                        SELECT kind, mode, resolution, provider, model, prompt_text,
                               input_summary::text AS input_summary, result::text AS result
                        FROM creation_generation WHERE owner_account_id = :owner AND kind = :kind
                        """).bind("owner", ACCOUNT).bind("kind", kind)
                .map(r -> Map.<String, Object>of(
                        "kind", r.get("kind", String.class),
                        "mode", r.get("mode", String.class),
                        "resolution", r.get("resolution", String.class),
                        "provider", r.get("provider", String.class),
                        "model", r.get("model", String.class),
                        "prompt_text", r.get("prompt_text", String.class),
                        "input_summary", r.get("input_summary", String.class),
                        "result", r.get("result", String.class)))
                .one().block();
        assertThat(row).as("kind=%s 应落 creation_generation", kind).isNotNull();
        return row;
    }
}
