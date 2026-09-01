package com.grassland.intelligence.contentsafety;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditsClient;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 内容修复端点（任务书 #63 卡2）：SSE progress/result 两帧、定死 prompt 组装（4.1 插槽句 +
 * 显示名问题清单）、ai_run 留痕 capability=content_fix、免费分支积分零流水（CreditsClient 零交互）、
 * 未配置模型 503 显式错误、请求体校验 400。
 */
class ContentSafetyFixControllerIT extends IntelligenceItSupport {

    private static final String H = "X-Grassland-Identity";

    /** 免费分支（feature=null）不触碰积分客户端——「积分账本零流水」在本库的间接断言面。 */
    @MockitoBean
    private CreditsClient credits;

    @BeforeEach
    void cleanConfigs() {
        db.sql("DELETE FROM ai_run WHERE capability = 'content_fix'").then().block();
        db.sql("DELETE FROM platform_model_config WHERE capability = 'content_fix'").then().block();
        QWEN.resetAll();
    }

    private void seedContentFixModel() {
        db.sql("""
                        INSERT INTO platform_model_config(
                            capability, model_role, provider, model, base_url,
                            health_status, enabled, version)
                        VALUES ('content_fix','primary','qwen','qwen-plus',:baseUrl,'healthy',true,1)
                        """)
                .bind("baseUrl", QWEN.baseUrl()).then().block();
        attachPlatformCredentialTo("content_fix");
    }

    /** WireMock stub 的 JSON 用 python json.dumps 生成再贴（手写转义必错，工程铁律）。 */
    private static final String STUB_OK =
            "{\"choices\": [{\"message\": {\"content\": \"修复后的正文：本店拿铁选用云南庄园豆，适合喜欢清爽口感的你，欢迎到店品尝。\"}}], \"usage\": {\"prompt_tokens\": 180, \"completion_tokens\": 60}}";

    private static Map<String, Object> fixBody() {
        return Map.of(
                "text", "本店的拿铁是全上海最好喝的，想喝的加微信 latte888。",
                "findings", List.of(
                        Map.of("category", "absolute_claims", "match", "全上海最好喝",
                                "advice", "改为可验证的客观描述"),
                        Map.of("category", "diversion", "match", "加微信 latte888",
                                "advice", "删除联系方式")),
                "platform", "xiaohongshu");
    }

    @Test
    @DisplayName("正常修复：SSE 两帧、定死 prompt 组装、ai_run 留痕、积分零流水")
    void fixStreamsResultAndLeavesFreeRun() {
        seedContentFixModel();
        QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(STUB_OK)));

        String account = UUID.randomUUID().toString();
        client().post().uri("/api/content-safety/fix")
                .header(H, sign(account, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(fixBody())
                .exchange().expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class)
                .value(body -> {
                    assertThat(body).contains("\"type\":\"progress\"");
                    assertThat(body).contains("\"type\":\"result\"");
                    assertThat(body).contains("修复后的正文");
                    assertThat(body).contains("data: [DONE]");
                });

        // prompt 组装：user 模板 + 平台插槽句 + 显示名问题清单（4.1 定死文本）
        var requests = QWEN.findAll(postRequestedFor(urlEqualTo("/chat/completions")));
        assertThat(requests).hasSize(1);
        String requestBody = requests.getFirst().getBodyAsString();
        assertThat(requestBody)
                .contains("待修复正文")
                .contains("结尾的 # 话题标签行原样保留")
                .contains("广告法极限词")
                .contains("全上海最好喝")
                .contains("输出修复后的完整正文");

        // ai_run 留痕（capability=content_fix）；feature=null 免费分支 → CreditsClient 零交互
        Long runs = db.sql("SELECT COUNT(*)::int AS c FROM ai_run"
                        + " WHERE capability = 'content_fix' AND account_id = :a")
                .bind("a", account)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(runs).as("修复 run 留痕 ai_run").isEqualTo(1);
        verifyNoInteractions(credits);
    }

    @Test
    @DisplayName("未配置 content_fix 模型 → 503 显式错误（不静默），无 ai_run")
    void missingModelReturnsExplicit503() {
        String account = UUID.randomUUID().toString();
        client().post().uri("/api/content-safety/fix")
                .header(H, sign(account, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(fixBody())
                .exchange().expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.error").isEqualTo("修复模型未配置,请在治理台为「内容修复」能力配置模型");

        Long runs = db.sql("SELECT COUNT(*)::int AS c FROM ai_run WHERE capability = 'content_fix'")
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(runs).as("denied 不留 run").isZero();
    }

    @Test
    @DisplayName("请求校验：需登录 401；空 findings 400；超长 text 400")
    void validationRejectsBadRequests() {
        client().post().uri("/api/content-safety/fix")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(fixBody())
                .exchange().expectStatus().isUnauthorized();

        client().post().uri("/api/content-safety/fix")
                .header(H, sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "正文", "findings", List.of()))
                .exchange().expectStatus().isBadRequest();

        char[] big = new char[16_001];
        java.util.Arrays.fill(big, '好');
        client().post().uri("/api/content-safety/fix")
                .header(H, sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "text", new String(big),
                        "findings", List.of(Map.of("category", "diversion", "match", "加微信"))))
                .exchange().expectStatus().isBadRequest();
    }
}
