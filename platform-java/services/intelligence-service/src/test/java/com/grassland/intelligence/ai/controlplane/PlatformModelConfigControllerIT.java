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
        db.sql("DELETE FROM platform_model_concurrency_slot").then().block();
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
    @DisplayName("配置并发上限时创建对应数据库槽位")
    void createsConcurrencySlots() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://dashscope.aliyuncs.com","maxConcurrency":2}
                        """)
                .exchange().expectStatus().isCreated();

        Long slots = db.sql("SELECT COUNT(*) AS n FROM platform_model_concurrency_slot")
                .map((row, meta) -> row.get("n", Long.class)).one().block();
        assertThat(slots).isEqualTo(2);
    }

    @Test
    @DisplayName("拒绝异常大的并发上限")
    void rejectsExcessiveConcurrencyLimit() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://dashscope.aliyuncs.com","maxConcurrency":1001}
                        """)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("平台凭据只允许发往受信 Qwen origin")
    void rejectsUntrustedProviderDestination() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"openai-compatible",
                         "model":"gpt-4","baseUrl":"https://attacker.example/v1"}
                        """)
                .exchange().expectStatus().isBadRequest();

        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"qwen",
                         "model":"qwen-plus","baseUrl":"https://attacker.example/v1"}
                        """)
                .exchange().expectStatus().isBadRequest();

        assertThat(historyCount()).isZero();
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

    /**
     * 任务书 #47 S0：seeder 用 provider=sandbox 种 voice/retrieval/image_edit 三行
     * （{@code PlatformModelConfigSeeder.seedSandboxCapability}），但两个 Request 的
     * provider 正则曾是 {@code qwen|openai-compatible} —— admin 对这些行的任何 PUT 都 400，
     * 即运营改不动、也看不出「该能力其实跑在沙箱假数据上」。
     * {@code PlatformProviderPolicy.validate} 本就支持 sandbox，仅 bean validation 失同步。
     */
    @Test
    @DisplayName("sandbox provider 可创建与修订（正则与 PlatformProviderPolicy 对齐）")
    void adminManagesSandboxCapability() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"image_edit","modelRole":"primary","provider":"sandbox",
                         "model":"sandbox-matting-v1","baseUrl":"https://sandbox.invalid"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.provider").isEqualTo("sandbox");

        // 修订：S0 之前这一步是 400（正则拒 sandbox），运营无法改动 seeder 种下的行
        client().put().uri("/api/admin/ai/models/image_edit/primary")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"provider":"sandbox","model":"sandbox-matting-v2",
                         "baseUrl":"https://sandbox.invalid"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.version").isEqualTo(2)
                .jsonPath("$.model").isEqualTo("sandbox-matting-v2");
    }

    @Test
    @DisplayName("sandbox provider 仍被钉死在内置地址（放宽正则未开洞）")
    void sandboxStillPinnedToBuiltInAddress() {
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"image_edit","modelRole":"primary","provider":"sandbox",
                         "model":"sandbox-matting-v1","baseUrl":"https://attacker.example/v1"}
                        """)
                .exchange().expectStatus().isBadRequest();

        // 未知 provider 仍被 bean validation 拒绝
        client().post().uri("/api/admin/ai/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"capability":"text","modelRole":"primary","provider":"anthropic",
                         "model":"claude","baseUrl":"https://sandbox.invalid"}
                        """)
                .exchange().expectStatus().isBadRequest();

        assertThat(historyCount()).isZero();
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
