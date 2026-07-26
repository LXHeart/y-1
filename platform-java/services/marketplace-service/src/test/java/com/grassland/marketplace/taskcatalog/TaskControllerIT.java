package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * task-catalog 端到端（草场 Epic 4 Slice 4A + 4B 发布限额/org 归属）。继承 {@link MarketplaceItSupport}。
 *
 * <p>4B 新增三道发布闸门：① org 归属（body.organizationId 须等于 caller.organizationId，不等/null→403）；
 * ② tier（DRAFT/null→403）；③ 按 org tier 的 maxActiveTasks 限额（超出→409）。
 * ⚠️ 既有 happy path 须用 4 参 sign（带 org + tier=basic_publish），否则 null tier 触发新闸门 403（回归）。
 */
class TaskControllerIT extends MarketplaceItSupport {

    @Test
    void merchantPublishesTaskAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(org, "爆款任务", "douyin", null))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.ownerAccountId").isEqualTo(merchant)
                .jsonPath("$.data.organizationId").isEqualTo(org)
                .jsonPath("$.data.status").isEqualTo("published")
                .jsonPath("$.data.platform").isEqualTo("douyin");

        Long count = db.sql("SELECT COUNT(*)::int AS c FROM marketplace_outbox"
                        + " WHERE event_type = 'TaskPublished' AND payload->>'organizationId' = :org")
                .bind("org", org)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void draftTierCannotPublish() {
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", org, "draft"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(org, "x", null, null))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void nullTierCannotPublish() {
        // 2 参 sign → tier=null → MerchantTier.fromDb 视作 DRAFT → 403
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(org, "x", null, null))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void orgMismatchForbidden() {
        String callerOrg = UUID.randomUUID().toString();
        String otherOrg = UUID.randomUUID().toString();
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", callerOrg, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(otherOrg, "x", null, null))  // body 声明别家 org
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void basicPublishQuotaEnforced() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        for (int i = 0; i < 5; i++) {
            publish(merchant, org, "basic_publish", "t" + i, null);  // 前 5 个均 201
        }
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(org, "第六个", null, null))
                .exchange().expectStatus().isEqualTo(409);  // 达 BASIC_PUBLISH 上限 5
    }

    @Test
    void financeTierAllowsMoreThanBasic() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        for (int i = 0; i < 6; i++) {
            publish(merchant, org, "finance_transaction", "t" + i, null);  // 第 6 个仍 201（上限 50）
        }
    }

    @Test
    void maxSlotsZeroBadRequest() {
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(org, "x", null, 0))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void nonMerchantForbidden() {
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(UUID.randomUUID().toString(), "x", null, null))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void missingAssertionUnauthorized() {
        client().post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(UUID.randomUUID().toString(), "x", null, null))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void invalidAssertionUnauthorized() {
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", "garbage.token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(UUID.randomUUID().toString(), "x", null, null))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void listPublishedTasksByOrganization() {
        String org = UUID.randomUUID().toString();
        publish(UUID.randomUUID().toString(), org, "basic_publish", "列表任务", null);
        client().get().uri("/api/tasks?organizationId=" + org)
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").value(l -> assertThat((Integer) l).isEqualTo(1));
    }

    @Test
    void detailAndNotFound() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = publish(merchant, org, "basic_publish", "详情任务", null);
        client().get().uri("/api/tasks/" + id)
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.id").isEqualTo(id);
        client().get().uri("/api/tasks/00000000-0000-0000-0000-000000000000")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isNotFound();
    }

    // ---------- D-05 硬限额执行 ----------

    @Test
    void basicTierCannotPublishBountyTask() {
        String org = UUID.randomUUID().toString();
        // BASIC_PUBLISH 可发布普通任务，但 maxTxAmountCents=0 → 资金型任务 403
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bountyBody(org, "资金型任务", 500L))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void bountyWithinCapIsAccepted() {
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bountyBody(org, "上限内赏金", 10_000_000L))  // 等于上限 → 允许
                .exchange().expectStatus().isCreated();
    }

    @Test
    void bountyOverCapConflict() {
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bountyBody(org, "超额赏金", 10_000_001L))  // 超一分 → 409
                .exchange().expectStatus().isEqualTo(409);
    }

    private static Map<String, Object> bountyBody(String org, String title, long bountyCents) {
        Map<String, Object> m = body(org, title, null, null);
        m.put("bountyCents", bountyCents);
        return m;
    }

    /**
     * 用量端点（D-05 额度的「已用」侧）：发布 2 条后 active/monthly 均为 2。
     *
     * <p>identity 的 {@code /quota} 只给上限，用量在 marketplace 这侧；前端合并为「已用 N / 上限 M」。
     */
    @Test
    void usageReportsActiveAndMonthlyCounts() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        publish(merchant, org, "basic_publish", "用量任务1", null);
        publish(merchant, org, "basic_publish", "用量任务2", null);

        client().get().uri("/api/tasks/usage?organizationId=" + org)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.organizationId").isEqualTo(org)
                .jsonPath("$.data.activeTasks").isEqualTo(2)
                .jsonPath("$.data.monthlyTasks").isEqualTo(2);
    }

    /** org 归属自查：不能查别家组织的用量（与发布闸门 1 同口径）。 */
    @Test
    void usageRejectsOtherOrg() {
        String merchant = UUID.randomUUID().toString();
        client().get().uri("/api/tasks/usage?organizationId=" + UUID.randomUUID())
                .header("X-Grassland-Identity", sign(merchant, "merchant", UUID.randomUUID().toString(), "basic_publish"))
                .exchange().expectStatus().isForbidden();
    }

    /**
     * 路由优先级回归：{@code /api/tasks/usage} 不能被 {@code /api/tasks/{id}} 详情端点抢走。
     * 若被抢走，这里会得到 404「任务不存在」而非 200 用量体。
     */
    @Test
    void usagePathNotShadowedByTaskDetail() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().get().uri("/api/tasks/usage?organizationId=" + org)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.activeTasks").isEqualTo(0);
    }

    @SuppressWarnings("unchecked")
    private String publish(String merchant, String org, String tier, String title, Integer maxSlots) {
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, tier))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(org, title, null, maxSlots))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    private static Map<String, Object> body(String org, String title, String platform, Integer maxSlots) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("organizationId", org);
        m.put("title", title);
        if (platform != null) {
            m.put("platform", platform);
        }
        if (maxSlots != null) {
            m.put("maxSlots", maxSlots);
        }
        return m;
    }
}
