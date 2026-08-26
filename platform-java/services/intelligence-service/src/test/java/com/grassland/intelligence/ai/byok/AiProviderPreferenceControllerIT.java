package com.grassland.intelligence.ai.byok;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 个人 BYOK 开关（任务书 #47 S5；D11–D14）。
 *
 * <p>核心是 D14「无行即 on」：从未碰过开关的账号行为与改造前逐字节一致，故存量 BYOK 用户零感知、
 * 无需任何迁移脚本。显式关闭才写行，且不动密钥密文（D12）。
 */
@DisplayName("AiProviderPreferenceController (个人 BYOK 开关)")
class AiProviderPreferenceControllerIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "66666666-6666-6666-6666-666666666666";
    private static final String OTHER = "77777777-7777-7777-7777-777777777777";

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM ai_provider_preference").then().block();
    }

    @Test
    @DisplayName("未配置：四个能力全部回 on 且 configured=false / version=0（D14）")
    void defaultsToOnWithoutRows() {
        client().get().uri("/api/ai/preferences")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(4)
                .jsonPath("$.data.items[0].capability").isEqualTo("text")
                .jsonPath("$.data.items[0].useOwnKey").isEqualTo(true)
                .jsonPath("$.data.items[0].configured").isEqualTo(false)
                .jsonPath("$.data.items[0].version").isEqualTo(0)
                .jsonPath("$.data.items[3].capability").isEqualTo("video_generation")
                .jsonPath("$.data.items[3].useOwnKey").isEqualTo(true);

        Long rows = db.sql("SELECT COUNT(*) AS n FROM ai_provider_preference")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(rows).isZero();   // 读取不该写行
    }

    @Test
    @DisplayName("关闭一个能力 → version 1 / configured=true；其它三个不受影响")
    void disableOneCapabilityOnly() {
        client().put().uri("/api/ai/preferences/text")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"useOwnKey\":false,\"expectedVersion\":0}")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.useOwnKey").isEqualTo(false)
                .jsonPath("$.data.configured").isEqualTo(true)
                .jsonPath("$.data.version").isEqualTo(1);

        client().get().uri("/api/ai/preferences")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange()
                .expectBody()
                .jsonPath("$.data.items[0].useOwnKey").isEqualTo(false)   // text 关了
                .jsonPath("$.data.items[1].useOwnKey").isEqualTo(true)    // image 不受影响
                .jsonPath("$.data.items[2].useOwnKey").isEqualTo(true)
                .jsonPath("$.data.items[3].useOwnKey").isEqualTo(true);
    }

    @Test
    @DisplayName("可逆：关掉再打开 → version 递增，不需要重贴密钥（D12）")
    void togglingIsReversible() {
        put("text", false, 0).expectStatus().isOk();
        put("text", true, 1).expectStatus().isOk()
                .expectBody().jsonPath("$.data.useOwnKey").isEqualTo(true)
                .jsonPath("$.data.version").isEqualTo(2);
    }

    @Test
    @DisplayName("乐观锁：过期 expectedVersion → 409")
    void staleVersionConflicts() {
        put("text", false, 0).expectStatus().isOk();
        put("text", true, 0).expectStatus().isEqualTo(409);      // 已是 version 1
        put("text", true, 7).expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("self-scoped：只影响调用者自己的偏好")
    void isSelfScoped() {
        put("text", false, 0).expectStatus().isOk();

        client().get().uri("/api/ai/preferences")
                .header("X-Grassland-Identity", sign(OTHER, "recommender"))
                .exchange()
                .expectBody()
                .jsonPath("$.data.items[0].useOwnKey").isEqualTo(true)    // 他人仍是默认
                .jsonPath("$.data.items[0].configured").isEqualTo(false);
    }

    @Test
    @DisplayName("入参校验：未知能力 400；缺字段 400；缺断言 401")
    void validatesInput() {
        client().put().uri("/api/ai/preferences/unknown_capability")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"useOwnKey\":false,\"expectedVersion\":0}")
                .exchange().expectStatus().isBadRequest();

        client().put().uri("/api/ai/preferences/text")
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"useOwnKey\":false}")
                .exchange().expectStatus().isBadRequest();

        client().get().uri("/api/ai/preferences")
                .exchange().expectStatus().isUnauthorized();
    }

    private WebTestClient.ResponseSpec put(String capability, boolean useOwnKey, long expectedVersion) {
        return client().put().uri("/api/ai/preferences/" + capability)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"useOwnKey\":%s,\"expectedVersion\":%d}".formatted(useOwnKey, expectedVersion))
                .exchange();
    }
}
