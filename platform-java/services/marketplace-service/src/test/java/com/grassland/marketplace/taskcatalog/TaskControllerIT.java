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
