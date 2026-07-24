package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * task-catalog 端到端（草场 Epic 4 Slice 4A）。继承 {@link MarketplaceItSupport}（注入 signer + db）。
 *
 * <p>覆盖：merchant 断言发布→201（owner=caller、published、outbox TaskPublished）、非 merchant→403、
 * 无断言/失效断言→401、列大厅（按 org）、详情 200 / 不存在 404。org/account 用随机 UUID（跨服务无 FK）。
 */
class TaskControllerIT extends MarketplaceItSupport {

    @Test
    void merchantPublishesTaskAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"organizationId\":\"" + org + "\",\"title\":\"爆款任务\",\"platform\":\"douyin\"}")
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
    void nonMerchantForbidden() {
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"organizationId\":\"" + UUID.randomUUID() + "\",\"title\":\"x\"}")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void missingAssertionUnauthorized() {
        client().post().uri("/api/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"organizationId\":\"" + UUID.randomUUID() + "\",\"title\":\"x\"}")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void invalidAssertionUnauthorized() {
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", "garbage.token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"organizationId\":\"" + UUID.randomUUID() + "\",\"title\":\"x\"}")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void listPublishedTasksByOrganization() {
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"organizationId\":\"" + org + "\",\"title\":\"列表任务\"}")
                .exchange().expectStatus().isCreated();
        client().get().uri("/api/tasks?organizationId=" + org)
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").value(l -> assertThat((Integer) l).isEqualTo(1));
    }

    @Test
    void detailAndNotFound() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = publish(merchant, org, "详情任务");
        client().get().uri("/api/tasks/" + id)
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.id").isEqualTo(id);
        client().get().uri("/api/tasks/00000000-0000-0000-0000-000000000000")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isNotFound();
    }

    @SuppressWarnings("unchecked")
    private String publish(String merchant, String org, String title) {
        Map<String, Object> body = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"organizationId\":\"" + org + "\",\"title\":\"" + title + "\"}")
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) body.get("data")).get("id");
    }
}
