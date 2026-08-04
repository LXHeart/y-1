package com.grassland.intelligence.ai.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 平台模型配置 admin CRUD（GL-P3-AI-001 model-control-plane）。requireAdmin 门闩 + 版本化 + history。
 * controller 非 KEK 门控（无密钥），故本 IT 不注入 KEK。
 */
@DisplayName("PlatformModelConfigController (admin CRUD)")
class PlatformModelConfigControllerIT extends IntelligenceItSupport {

    private static final String ADMIN = "11111111-1111-1111-1111-111111111111";
    private static final String USER = "22222222-2222-2222-2222-222222222222";

    @BeforeEach
    void clean() {
        db.sql("DELETE FROM platform_model_config_history").then().block();
        db.sql("DELETE FROM platform_model_config").then().block();
    }

    @Test
    @DisplayName("admin 创建 → 201 version=1；list 可见；history 落 create 行")
    void adminCreates() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://dashscope.aliyuncs.com"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.version").isEqualTo(1)
                .jsonPath("$.capability").isEqualTo("text")
                .jsonPath("$.modelRole").isEqualTo("primary")
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.healthStatus").isEqualTo("healthy");

        client().get().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.length()").isEqualTo(1);

        assertThat(historyCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("重复创建同 (capability,modelRole) → 409")
    void duplicateCreateConflict() {
        createPrimary();
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-turbo","baseUrl":"https://dashscope.aliyuncs.com"}
                        """)
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("admin 修订 → version+1；history 落 update 行")
    void adminRevisesBumpsVersion() {
        createPrimary();
        client().put().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"provider":"qwen","model":"qwen-max","baseUrl":"https://dashscope.aliyuncs.com"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.version").isEqualTo(2)
                .jsonPath("$.model").isEqualTo("qwen-max");

        // version=1 被 disable，只剩 version=2 enabled
        client().get().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectBody().jsonPath("$.length()").isEqualTo(1);

        assertThat(historyCount()).isEqualTo(2);  // create + update
    }

    @Test
    @DisplayName("admin 禁用 → 204；其后 GET → 404")
    void adminDisables() {
        createPrimary();
        client().delete().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();
        client().get().uri("/api/admin/ai/models/text/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    @DisplayName("鉴权：非 admin → 403；缺断言 → 401")
    void adminGate() {
        // 非 admin（普通 user）
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signWithRole(USER, null, null, "user"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://x"}
                        """)
                .exchange().expectStatus().isForbidden();
        // 缺断言
        client().get().uri("/api/admin/ai/models")
                .exchange().expectStatus().isUnauthorized();
    }

    private void createPrimary() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://dashscope.aliyuncs.com"}
                        """)
                .exchange().expectStatus().isCreated();
    }

    private Long historyCount() {
        return db.sql("SELECT COUNT(*) AS n FROM platform_model_config_history")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
    }
}
