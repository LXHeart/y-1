package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 价目表 admin CRUD（V52）。draft/active/retired 三态 + 单 active + 只有 draft 可改。
 *
 * <p>启动期 {@code PriceTableService.seedOnStartup} 会把内置价目播成 v1/active，故每个用例开头
 * 表里已有一张 active——清库会让 seed 不再重跑（同一进程只在 ApplicationReadyEvent 触发一次），
 * 因此这里**不清 active 行**，改为用独立 label 避开互相干扰。
 */
@DisplayName("PriceTableAdminController (admin CRUD)")
class PriceTableAdminControllerIT extends IntelligenceItSupport {

    private static final String ADMIN = "44444444-4444-4444-4444-444444444444";
    private static final String USER = "55555555-5555-5555-5555-555555555555";

    /**
     * 只删本类造的 {@code it-%} 版本，再把 seed 的 v1 复位成 active。
     *
     * <p>不能按 status 清（第一版就是这么写的、并且失败了）：{@code copyEditActivateFlow} 激活
     * {@code it-v2} 后 v1 会转 retired，下一个用例的清理就把 seed 数据一起删了。而 seed 只在
     * {@code ApplicationReadyEvent} 触发一次，删掉不会重建，后续用例便再也找不到基线。
     */
    @BeforeEach
    void resetToSeededState() {
        db.sql("DELETE FROM price_table_model WHERE version_id IN "
                + "(SELECT id FROM price_table_version WHERE label LIKE 'it-%')").then().block();
        db.sql("DELETE FROM price_table_version WHERE label LIKE 'it-%'").then().block();
        // 复位：先全部降级再抬 v1，避免与单 active 部分唯一索引冲突
        db.sql("UPDATE price_table_version SET status = 'retired' WHERE status = 'active'").then().block();
        db.sql("UPDATE price_table_version SET status = 'active' WHERE label = :label")
                .bind("label", PriceTableService.FALLBACK_LABEL).then().block();
    }

    @Test
    @DisplayName("启动期把内置价目播成 v1；其明细与硬编码逐值一致")
    void seedsBundledPricesAsActive() {
        // 按 label 定位而非「当前 active」：其它用例激活了新版本后，v1 会转 retired，
        // 但它的明细必须仍然可读且单价不变——存量 Run 就是按它结算的。
        String seededId = versionIdByLabel(PriceTableService.FALLBACK_LABEL);
        assertThat(seededId).isNotNull();

        client().get().uri("/api/admin/ai/price-tables/" + seededId)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.label").isEqualTo(PriceTableService.FALLBACK_LABEL)
                .jsonPath("$.models[?(@.modelId=='qwen-plus')].centsPer1kInputTokens").isEqualTo(3)
                .jsonPath("$.models[?(@.modelId=='qwen-plus')].centsPer1kOutputTokens").isEqualTo(6)
                .jsonPath("$.models[?(@.modelId=='sandbox-speech-v1')].centsPer1kInputTokens").isEqualTo(0);
    }

    @Test
    @DisplayName("复制 active 成 draft → 改单价 → 激活；旧 active 转 retired 而非删除")
    void copyEditActivateFlow() {
        String activeId = activeVersionId();

        client().post().uri("/api/admin/ai/price-tables")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"label":"it-v2","note":"调价","copyFromVersionId":"%s"}
                        """.formatted(activeId))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.status").isEqualTo("draft");

        String draftId = versionIdByLabel("it-v2");

        // 复制把明细带过来了
        client().get().uri("/api/admin/ai/price-tables/" + draftId)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.models[?(@.modelId=='qwen-plus')]").exists();

        // draft 可改
        client().put().uri("/api/admin/ai/price-tables/" + draftId + "/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"models":[{"modelId":"qwen-plus","capability":"text","provider":"qwen",
                                    "centsPer1kInputTokens":7,"centsPer1kOutputTokens":14}]}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.models.length()").isEqualTo(1)
                .jsonPath("$.models[0].centsPer1kInputTokens").isEqualTo(7);

        client().post().uri("/api/admin/ai/price-tables/" + draftId + "/activate")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("active");

        // 旧 active 转 retired，不是被删——存量 Run 要按它复现账
        assertThat(statusOf(activeId)).isEqualTo("retired");
    }

    @Test
    @DisplayName("active 不可改单价 → 409（单价冻结，避免篡改历史账）")
    void onlyDraftCanBeEdited() {
        String activeId = activeVersionId();

        client().put().uri("/api/admin/ai/price-tables/" + activeId + "/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"models":[{"modelId":"qwen-plus","capability":"text","provider":"qwen",
                                    "centsPer1kInputTokens":999}]}
                        """)
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("active 不可删；draft 可删（明细随 CASCADE）")
    void deleteOnlyDrafts() {
        String activeId = activeVersionId();
        client().delete().uri("/api/admin/ai/price-tables/" + activeId)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isEqualTo(409);

        createDraft("it-v-del");
        String draftId = versionIdByLabel("it-v-del");
        client().delete().uri("/api/admin/ai/price-tables/" + draftId)
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .exchange().expectStatus().isNoContent();
    }

    @Test
    @DisplayName("label 重复 → 409；非法 label 与负单价 → 400")
    void rejectsInvalidInput() {
        createDraft("it-v-dup");
        client().post().uri("/api/admin/ai/price-tables")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"label":"it-v-dup"}
                        """)
                .exchange().expectStatus().isEqualTo(409);

        client().post().uri("/api/admin/ai/price-tables")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"label":"bad label with spaces"}
                        """)
                .exchange().expectStatus().isBadRequest();

        String draftId = versionIdByLabel("it-v-dup");
        client().put().uri("/api/admin/ai/price-tables/" + draftId + "/models")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"models":[{"modelId":"qwen-plus","capability":"text","provider":"qwen",
                                    "centsPer1kInputTokens":-1}]}
                        """)
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("鉴权：非 admin → 403；缺断言 → 401")
    void adminGate() {
        client().get().uri("/api/admin/ai/price-tables")
                .header("X-Grassland-Identity", signWithRole(USER, null, null, "user"))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/api/admin/ai/price-tables")
                .exchange().expectStatus().isUnauthorized();
    }

    private void createDraft(String label) {
        client().post().uri("/api/admin/ai/price-tables")
                .header("X-Grassland-Identity", signAdmin(ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"label":"%s"}
                        """.formatted(label))
                .exchange().expectStatus().isCreated();
    }

    private String activeVersionId() {
        return db.sql("SELECT id::text AS id FROM price_table_version WHERE status = 'active'")
                .map((row, meta) -> row.get("id", String.class)).one().block();
    }

    private String versionIdByLabel(String label) {
        return db.sql("SELECT id::text AS id FROM price_table_version WHERE label = :label")
                .bind("label", label)
                .map((row, meta) -> row.get("id", String.class)).one().block();
    }

    private String statusOf(String id) {
        return db.sql("SELECT status FROM price_table_version WHERE id = CAST(:id AS uuid)")
                .bind("id", id)
                .map((row, meta) -> row.get("status", String.class)).one().block();
    }
}
