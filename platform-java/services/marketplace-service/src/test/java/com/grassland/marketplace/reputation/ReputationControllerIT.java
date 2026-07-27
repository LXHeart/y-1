package com.grassland.marketplace.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 评分（V6）+ 声誉指标/等级端到端。继承 {@link MarketplaceItSupport}。
 *
 * <p>走完整真实链路：发布 → 报名 → 接受 → 提交交付物 → 确认履约 → 评分 → 读声誉。
 * 非资金型任务（bounty 为空）以免 IT 依赖 finance；结算 workflow 的出站边界仍以替身隔离。
 */
class ReputationControllerIT extends MarketplaceItSupport {

    private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

    @MockitoBean
    private FinanceEscrowClient financeClient;

    @MockitoBean
    private DisputeChecker disputeChecker;

    @Test
    @DisplayName("确认履约后可评分，声誉指标随即反映该次完成")
    void rateAfterConfirmThenReputationReflectsIt() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String app = applyAcceptSubmitConfirm(merchant, org, rec, task);

        client().post().uri(ratingUri(task, app))
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("score", 5, "comment", "内容质量很好"))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.score").isEqualTo(5)
                .jsonPath("$.data.recommenderAccountId").isEqualTo(rec)
                .jsonPath("$.data.ratedByAccountId").isEqualTo(merchant);

        assertThat(outboxCount("EngagementRated")).isEqualTo(1);

        client().get().uri("/api/reputation/" + rec)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.accountId").isEqualTo(rec)
                .jsonPath("$.data.acceptedCount").isEqualTo(1)
                .jsonPath("$.data.completedCount").isEqualTo(1)
                .jsonPath("$.data.completionRate").isEqualTo(1.0)
                .jsonPath("$.data.ratingCount").isEqualTo(1)
                .jsonPath("$.data.averageScore").isEqualTo(5.0)
                // 接单→首次提交的时长（本地毫秒级 → 0 秒），有样本即非 null
                .jsonPath("$.data.averageResponseSeconds").exists()
                // 完成 1 单远不到 Lv2 的 6 单门槛
                .jsonPath("$.data.level").isEqualTo("Lv1")
                .jsonPath("$.data.levelTitle").isEqualTo("新手草友");
    }

    /** 从未接过单的人不是 404——「他还没干过活」正是商家要看到的事实。 */
    @Test
    @DisplayName("零记录的推荐官返回空指标而非 404")
    void unknownRecommenderReturnsEmptyStats() {
        String viewer = UUID.randomUUID().toString();
        client().get().uri("/api/reputation/" + UUID.randomUUID())
                .header("X-Grassland-Identity", sign(viewer, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.acceptedCount").isEqualTo(0)
                .jsonPath("$.data.completedCount").isEqualTo(0)
                .jsonPath("$.data.completionRate").isEqualTo(0.0)
                .jsonPath("$.data.averageScore").doesNotExist()   // 无评分 → null（不是 0）
                .jsonPath("$.data.level").isEqualTo("Lv1");
    }

    @Test
    @DisplayName("接单但未完成 → 完成率下降，等级不受评分缺失影响")
    void acceptedButNotConfirmedLowersCompletionRate() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String taskDone = publishTask(merchant, org);
        applyAcceptSubmitConfirm(merchant, org, rec, taskDone);
        String taskOpen = publishTask(merchant, org);
        accept(merchant, org, taskOpen, apply(rec, taskOpen));  // 接了没完成

        client().get().uri("/api/reputation/" + rec)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.acceptedCount").isEqualTo(2)
                .jsonPath("$.data.completedCount").isEqualTo(1)
                .jsonPath("$.data.completionRate").isEqualTo(0.5);
    }

    @Test
    @DisplayName("未确认履约就评分 → 409")
    void rateBeforeConfirmConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String app = apply(rec, task);
        accept(merchant, org, task, app);

        client().post().uri(ratingUri(task, app))
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("score", 4))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("重复评分 → 409（不能反复改分刷分）")
    void duplicateRatingConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String app = applyAcceptSubmitConfirm(merchant, org, rec, task);
        rate(merchant, org, task, app, 5);

        client().post().uri(ratingUri(task, app))
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("score", 1))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("越界分数 → 400；非任务 owner 评分 → 403")
    void invalidScoreAndForeignMerchantRejected() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String app = applyAcceptSubmitConfirm(merchant, org, rec, task);

        client().post().uri(ratingUri(task, app))
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("score", 6))
                .exchange().expectStatus().isBadRequest();

        client().post().uri(ratingUri(task, app))
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("score", 5))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("评分对本人推荐官可见、对不相干的人 403；未评价时 data 为 null")
    void ratingVisibility() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String app = applyAcceptSubmitConfirm(merchant, org, rec, task);

        // 未评价 → data:null（不是 404）
        client().get().uri(ratingUri(task, app))
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data").doesNotExist();

        rate(merchant, org, task, app, 4);

        client().get().uri(ratingUri(task, app))
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.score").isEqualTo(4);

        client().get().uri(ratingUri(task, app))
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("未登录 → 401；非法 accountId → 400（不落成 500）")
    void authAndInputGuards() {
        client().get().uri("/api/reputation/" + ZERO_UUID).exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/reputation/not-a-uuid")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant"))
                .exchange().expectStatus().isBadRequest();
    }

    // ---------- helpers ----------

    private static String ratingUri(String task, String app) {
        return "/api/tasks/" + task + "/applications/" + app + "/rating";
    }

    /** 报名 → 接受 → 提交交付物 → 确认履约，返回 applicationId。 */
    private String applyAcceptSubmitConfirm(String merchant, String org, String rec, String task) {
        String app = apply(rec, task);
        accept(merchant, org, task, app);
        submit(rec, task, app);
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/confirm")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
        return app;
    }

    private void rate(String merchant, String org, String task, String app, int score) {
        client().post().uri(ratingUri(task, app))
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("score", score))
                .exchange().expectStatus().isCreated();
    }

    private void accept(String merchant, String org, String task, String app) {
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk();  // 非资金型 → 4B 直连同步 200
    }

    private void submit(String rec, String task, String app) {
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/submissions")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("contentUrl", "https://example.com/post/" + app))
                .exchange().expectStatus().isCreated();
    }

    @SuppressWarnings("unchecked")
    private String apply(String rec, String task) {
        Map<String, Object> resp = client().post().uri("/api/tasks/" + task + "/applications")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    @SuppressWarnings("unchecked")
    private String publishTask(String merchant, String org) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizationId", org);
        body.put("title", "探店任务");
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    private long outboxCount(String eventType) {
        return db.sql("SELECT COUNT(*)::int AS c FROM marketplace_outbox WHERE event_type = :et")
                .bind("et", eventType).map(r -> r.get("c", Integer.class)).one().block().longValue();
    }
}
