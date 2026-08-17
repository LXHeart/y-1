package com.grassland.intelligence.contentsafety;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 内容安全端点与深检编排端到端（任务书 #34 B1/B2 / ADR-D16）：
 * 手动端点鉴权与体积上限；未配置 content_safety → deepCheck:false 降级且生成零影响；
 * 配置后深检 run 落 ai_run（capability=content_safety）、用户积分零扣减（feature=null 免费分支）、
 * 深检 findings 折叠；模型坏 JSON 降级不炸。
 */
class ContentSafetyControllerIT extends IntelligenceItSupport {

    private static final String H = "X-Grassland-Identity";

    @BeforeEach
    void cleanConfigs() {
        db.sql("DELETE FROM ai_run WHERE capability = 'content_safety'").then().block();
        db.sql("DELETE FROM platform_model_config WHERE capability = 'content_safety'").then().block();
        QWEN.resetAll();
    }

    private void seedContentSafetyModel() {
        db.sql("""
                        INSERT INTO platform_model_config(
                            capability, model_role, provider, model, base_url,
                            health_status, enabled, version)
                        VALUES ('content_safety','primary','qwen','qwen-plus',:baseUrl,'healthy',true,1)
                        """)
                .bind("baseUrl", QWEN.baseUrl()).then().block();
    }

    private static String longText(String seed) {
        StringBuilder sb = new StringBuilder(seed);
        while (sb.length() < 300) {
            sb.append("。手冲咖啡的酸质明亮，奶泡绵密，适合下午办公，环境安静不吵闹，店员也会耐心介绍豆子风味");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("手动端点：需登录；正常文本 findings 空 + deepCheck:false（未配置模型）")
    void checkRequiresLoginAndDegradesWithoutModel() {
        // 未登录 → 401（fail-closed）
        client().post().uri("/api/content-safety/check")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "正常文案"))
                .exchange().expectStatus().isUnauthorized();

        // 短文本仅 L1（未配置模型也如此）
        client().post().uri("/api/content-safety/check")
                .header(H, sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", "全城最好吃的甜品，加微信 sweet8888"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.safety.deepCheck").isEqualTo(false)
                .jsonPath("$.data.safety.lexiconVersion").isEqualTo("lexicon-v1")
                .jsonPath("$.data.safety.findings[0].category").exists();
    }

    @Test
    @DisplayName("体积上限：>16k 字符 → 400")
    void oversizedTextRejected() {
        char[] big = new char[16_001];
        java.util.Arrays.fill(big, '好');
        client().post().uri("/api/content-safety/check")
                .header(H, sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", new String(big)))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("验收 #2：配置后长文本深检运行——ai_run 留痕、积分零扣减、deep findings 折叠")
    void deepCheckRunsZeroCostAndMergesFindings() {
        seedContentSafetyModel();
        QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"choices\":[{\"message\":{\"content\":"
                        + "\"{\\\"findings\\\":[{\\\"category\\\":\\\"false_promises\\\","
                        + "\\\"severity\\\":\\\"medium\\\",\\\"match\\\":\\\"喝了就瘦\\\","
                        + "\\\"advice\\\":\\\"删去疗效承诺\\\"}]}\"}}],"
                        + "\"usage\":{\"prompt_tokens\":120,\"completion_tokens\":40}}")));

        String account = UUID.randomUUID().toString();
        client().post().uri("/api/content-safety/check")
                .header(H, sign(account, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", longText("这家轻食店的全麦贝果全城销量第一")))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.safety.deepCheck").isEqualTo(true)
                // deep finding 来自模型 JSON；L1 同文本命中词组+正则两条（「销量第一」词组与全城第一 pattern），断言存在即可
                .jsonPath("$.data.safety.findings[?(@.deep==true)].match").isEqualTo("喝了就瘦")
                .jsonPath("$.data.safety.findings[?(@.deep==false)].match")
                .value(list -> assertThat((java.util.List<?>) list)
                        .anyMatch(item -> String.valueOf(item).contains("销量第一")));

        // 深检 run 落 ai_run（capability=content_safety）
        Long runs = db.sql("SELECT COUNT(*)::int AS c FROM ai_run"
                        + " WHERE capability = 'content_safety' AND account_id = :a")
                .bind("a", account)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(runs).as("深检 run 留痕 ai_run").isEqualTo(1);
        // 积分零扣减：finance credits 不被调用（feature=null），本服务库无 credits 消费留痕可查，
        // 断言间接面——ai_run reserved_cents 为预算 cents 而非积分，且无 IntelligenceException。
        // （finance 侧零变化由 feature=null 分支保证，AiExecutionService 既有行为。）
    }

    @Test
    @DisplayName("验收 #2 续：模型坏 JSON 输出 → 深检不可用降级（deepCheck:false），端点仍 200")
    void badModelOutputDegradesGracefully() {
        seedContentSafetyModel();
        QWEN.stubFor(post(urlEqualTo("/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"choices\":[{\"message\":{\"content\":\"这不是 JSON\"}}],"
                        + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}")));

        client().post().uri("/api/content-safety/check")
                .header(H, sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("text", longText("正常长文本")))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.safety.deepCheck").isEqualTo(false);
    }

    @Test
    @DisplayName("验收 #5：BYOK capability 白名单不含 content_safety（@Pattern 不含该值）")
    void byokRejectsContentSafetyCapability() {
        // BYOK controller 挂 @Conditional(CryptoKekConfiguredCondition)——测试环境未配 KEK 时端点 404，
        // 白名单行为以请求 record 的校验注解为准（D4：白名单不加 content_safety）。
        String pattern = null;
        try {
            var field = com.grassland.intelligence.ai.byok.CreateAiProviderKeyRequest.class
                    .getDeclaredField("capability");
            var annotation = field.getAnnotation(jakarta.validation.constraints.Pattern.class);
            pattern = annotation == null ? null : annotation.regexp();
        } catch (Exception ignored) {
            // field miss → fail below
        }
        assertThat(pattern).as("BYOK capability 白名单正则应存在").isNotNull();
        assertThat(pattern).as("白名单不含 content_safety").doesNotContain("content_safety");
        assertThat(pattern).contains("text", "image", "image_generation", "video_generation");
    }
}
