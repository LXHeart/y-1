package com.grassland.finance.aicredits;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.finance.FinanceItSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 积分包 SKU 管理端点（AI 套餐 v1 Slice A）。锁定：
 * FINANCE 角色门禁、创建即 v1、调价出新版本并切 current 指针（旧 version 不可变）、
 * 状态机迁移约束、非管理员 403。
 */
class CreditsPackageAdminControllerIT extends FinanceItSupport {

    private static final String BASE = "/api/admin/credits-packages";

    @BeforeEach
    void cleanPackages() {
        db.sql("DELETE FROM credits_purchase_order").then().block();
        db.sql("UPDATE credits_package SET current_version_id = NULL").then().block();
        db.sql("DELETE FROM credits_package_version").then().block();
        db.sql("DELETE FROM credits_package").then().block();
    }

    @Test
    @DisplayName("FINANCE 角色创建积分包：v1 快照 + draft 状态 + current 指针")
    void adminCreatesPackageWithVersionOne() {
        client().post().uri(BASE)
                .header("X-Grassland-Identity", signRole("pkg-admin-1", "finance"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "体验包",
                        "description", "新用户体验",
                        "priceCents", 990,
                        "creditsAmount", 10))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.name").isEqualTo("体验包")
                .jsonPath("$.data.status").isEqualTo("draft")
                .jsonPath("$.data.version").isEqualTo(1)
                .jsonPath("$.data.priceCents").isEqualTo(990)
                .jsonPath("$.data.creditsAmount").isEqualTo(10);

        Long versions = db.sql("SELECT count(*) AS n FROM credits_package_version")
                .map(row -> row.get("n", Long.class)).one().block();
        assertThat(versions).isEqualTo(1);
        Long dangling = db.sql("""
                        SELECT count(*) AS n FROM credits_package
                        WHERE current_version_id IS NULL OR current_version_id NOT IN
                              (SELECT id FROM credits_package_version)
                        """)
                .map(row -> row.get("n", Long.class)).one().block();
        assertThat(dangling).isZero();
    }

    @Test
    @DisplayName("调价创建 v2 并切换 current；v1 行保持不可变")
    void priceChangeCreatesNewVersionAndSwitchesCurrent() {
        String packageId = createPackage("创作包", 4900, 60);

        client().put().uri(BASE + "/" + packageId)
                .header("X-Grassland-Identity", signRole("pkg-admin-2", "finance"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("priceCents", 3900, "creditsAmount", 50, "note", "促销调价"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.version").isEqualTo(2)
                .jsonPath("$.data.priceCents").isEqualTo(3900)
                .jsonPath("$.data.creditsAmount").isEqualTo(50);

        List<Long> prices = db.sql("""
                        SELECT price_cents AS p FROM credits_package_version
                        WHERE package_id = :packageId::uuid ORDER BY version
                        """)
                .bind("packageId", packageId)
                .map(row -> row.get("p", Long.class)).all().collectList().block();
        assertThat(prices).containsExactly(4900L, 3900L);
    }

    @Test
    @DisplayName("状态机：draft→active→retired→active 合法；retired→draft 拒绝 409")
    void statusTransitionsEnforced() {
        String packageId = createPackage("工作室包", 19900, 280);

        setStatus(packageId, "active").expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("active");
        setStatus(packageId, "retired").expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("retired");
        setStatus(packageId, "active").expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("active");
        setStatus(packageId, "draft").expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("非 FINANCE/PLATFORM_ADMIN 角色 → 403；无断言 → 401")
    void nonFinanceRoleRejected() {
        client().post().uri(BASE)
                .header("X-Grassland-Identity", signRole("pkg-user-1", "content_reviewer"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "越权包", "priceCents", 100, "creditsAmount", 1))
                .exchange().expectStatus().isForbidden();

        client().get().uri(BASE).exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("列表只读端点返回全部状态与当前版本")
    void listReturnsAllPackagesWithCurrentVersion() {
        String active = createPackage("在售包", 990, 10);
        setStatus(active, "active").expectStatus().isOk();

        client().get().uri(BASE)
                .header("X-Grassland-Identity", signRole("pkg-admin-3", "platform_admin"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].name").isEqualTo("在售包")
                .jsonPath("$.data[0].status").isEqualTo("active")
                .jsonPath("$.data[0].version").isEqualTo(1);
    }

    // ---------------- helpers ----------------

    private String createPackage(String name, long priceCents, int creditsAmount) {
        return java.util.Objects.requireNonNull(client().post().uri(BASE)
                        .header("X-Grassland-Identity", signRole("pkg-admin-seed", "finance"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("name", name, "priceCents", priceCents, "creditsAmount", creditsAmount))
                        .exchange().expectStatus().isOk()
                        .expectBody(Map.class)
                        .returnResult().getResponseBody())
                .get("data") instanceof Map<?, ?> data ? data.get("id").toString() : null;
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec setStatus(
            String packageId, String status) {
        return client().put().uri(BASE + "/" + packageId + "/status")
                .header("X-Grassland-Identity", signRole("pkg-admin-seed", "finance"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("status", status))
                .exchange();
    }
}
