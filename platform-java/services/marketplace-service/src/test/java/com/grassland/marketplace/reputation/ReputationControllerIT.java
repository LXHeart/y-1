package com.grassland.marketplace.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

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

    /** 配置 finance mock：release/reserve/capture 全返回成功（幂等）——非资金型任务不走 finance，但 cancel 路径会调用 release。 */
    @BeforeEach
    void setUpFinanceMock() {
        when(financeClient.release(anyString(), anyString())).thenReturn(Mono.empty());
        when(financeClient.reserve(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Mono.empty());
        when(financeClient.capture(anyString(), anyString())).thenReturn(Mono.empty());
    }

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
                .jsonPath("$.data.merchantCancelledCount").isEqualTo(0)
                .jsonPath("$.data.rejectedCount").isEqualTo(0)
                .jsonPath("$.data.withdrawnCount").isEqualTo(0)
                .jsonPath("$.data.terminalCount").isEqualTo(1)
                .jsonPath("$.data.completionRate").isEqualTo(1.0)
                .jsonPath("$.data.ratingCount").isEqualTo(1)
                .jsonPath("$.data.averageScore").isEqualTo(5.0)
                .jsonPath("$.data.lastActiveAt").exists()
                .jsonPath("$.data.inactiveDowngraded").isEqualTo(false)
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
                .jsonPath("$.data.merchantCancelledCount").isEqualTo(0)
                .jsonPath("$.data.rejectedCount").isEqualTo(0)
                .jsonPath("$.data.withdrawnCount").isEqualTo(0)
                .jsonPath("$.data.terminalCount").isEqualTo(0)
                .jsonPath("$.data.completionRate").isEqualTo(0.0)
                .jsonPath("$.data.averageScore").doesNotExist()   // 无评分 → null（不是 0）
                .jsonPath("$.data.lastActiveAt").doesNotExist()
                .jsonPath("$.data.inactiveDowngraded").isEqualTo(false)
                .jsonPath("$.data.level").isEqualTo("Lv1");
    }

    @Test
    @DisplayName("商家或系统动作不能刷新推荐官的 30 天活跃时间")
    void merchantDecisionDoesNotRefreshRecommenderActivity() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);
        String app = apply(rec, task);
        db.sql("""
                UPDATE task_application
                SET created_at = now() - interval '40 days',
                    status = 'accepted', decided_at = now(), updated_at = now(),
                    reputation_level_at_accept = 1,
                    reputation_policy_version_at_accept = 1,
                    settlement_delay_days_at_accept = 2,
                    commission_bonus_bps_at_accept = 0,
                    premium_support_at_accept = false
                WHERE id = CAST(:id AS uuid)
                """).bind("id", app).fetch().rowsUpdated().block();

        client().get().uri("/api/reputation/" + rec)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.lastActiveAt").value(value -> assertThat(Instant.parse((String) value))
                        .isBefore(Instant.now().minusSeconds(39L * 24 * 60 * 60)));
    }

    @Test
    @DisplayName("进行中的已接单 engagement 计入完成率分母")
    void inProgressAcceptedEngagementAffectsCompletionRate() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String taskDone = publishTask(merchant, org);
        applyAcceptSubmitConfirm(merchant, org, rec, taskDone);
        String taskOpen = publishTask(merchant, org);
        accept(merchant, org, taskOpen, apply(rec, taskOpen));  // 接了没完成，仍在进行中

        client().get().uri("/api/reputation/" + rec)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.acceptedCount").isEqualTo(2)  // 当前 accepted 仍为 2
                .jsonPath("$.data.completedCount").isEqualTo(1)
                .jsonPath("$.data.terminalCount").isEqualTo(1)
                .jsonPath("$.data.completionRate").isEqualTo(0.5);  // 1 完成 / 2 已接单
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

    @Test
    @DisplayName("商家取消 engagement → merchantCancelledCount 增量，completionRate 正确计算")
    void merchantCancelEngagementCountsCorrectly() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);

        // 接受任务（但未提交凭证）
        String app = apply(rec, task);
        accept(merchant, org, task, app);

        // 商家取消任务
        client().post().uri("/api/tasks/" + task + "/cancel")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", taskVersion(task)))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.refundedCount").isEqualTo(1);

        // 验证声誉指标
        client().get().uri("/api/reputation/" + rec)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.acceptedCount").isEqualTo(1)  // 累计接单包含后续被商家取消的单
                .jsonPath("$.data.completedCount").isEqualTo(0)  // 未完成
                .jsonPath("$.data.merchantCancelledCount").isEqualTo(1)  // 商家取消计数
                .jsonPath("$.data.rejectedCount").isEqualTo(0)
                .jsonPath("$.data.withdrawnCount").isEqualTo(0)
                .jsonPath("$.data.terminalCount").isEqualTo(1)  // 1 个终态（商家取消）
                .jsonPath("$.data.completionRate").isEqualTo(0.0);  // 商家取消从责任分母排除
    }

    @Test
    @DisplayName("完成 + 商家取消混合 → completionRate 只计终态")
    void mixedCompletedAndCancelledCalculatesCorrectly() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();

        // 第一个任务：完成
        String task1 = publishTask(merchant, org);
        applyAcceptSubmitConfirm(merchant, org, rec, task1);

        // 第二个任务：商家取消
        String task2 = publishTask(merchant, org);
        String app2 = apply(rec, task2);
        accept(merchant, org, task2, app2);
        client().post().uri("/api/tasks/" + task2 + "/cancel")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", taskVersion(task2)))
                .exchange().expectStatus().isOk();

        // 验证声誉指标
        client().get().uri("/api/reputation/" + rec)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.acceptedCount").isEqualTo(2)
                .jsonPath("$.data.completedCount").isEqualTo(1)
                .jsonPath("$.data.merchantCancelledCount").isEqualTo(1)
                .jsonPath("$.data.terminalCount").isEqualTo(2)  // 1 完成 + 1 取消
                .jsonPath("$.data.completionRate").isEqualTo(1.0);  // 商家取消不降低推荐官完成率
    }

    @Test
    @DisplayName("推荐官撤销 → withdrawnCount 增量")
    void recommenderWithdrawsCountsCorrectly() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);

        String app = apply(rec, task);

        // 推荐官撤销报名（必须在 accept 之前，withdraw 只支持 pending 状态）
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/withdraw")
                .header("X-Grassland-Identity", sign(rec, "recommender"))
                .exchange().expectStatus().isOk();

        client().get().uri("/api/reputation/" + rec)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.acceptedCount").isEqualTo(0)
                .jsonPath("$.data.completedCount").isEqualTo(0)
                .jsonPath("$.data.merchantCancelledCount").isEqualTo(0)
                .jsonPath("$.data.rejectedCount").isEqualTo(0)
                .jsonPath("$.data.withdrawnCount").isEqualTo(1)
                .jsonPath("$.data.terminalCount").isEqualTo(1)
                .jsonPath("$.data.completionRate").isEqualTo(0.0);
    }

    @Test
    @DisplayName("商家拒绝报名 → rejectedCount 增量")
    void merchantRejectsApplicationCountsCorrectly() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String rec = UUID.randomUUID().toString();
        String task = publishTask(merchant, org);

        String app = apply(rec, task);

        // 商家拒绝
        client().post().uri("/api/tasks/" + task + "/applications/" + app + "/reject")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("note", "不符合要求"))
                .exchange().expectStatus().isOk();

        client().get().uri("/api/reputation/" + rec)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.acceptedCount").isEqualTo(0)
                .jsonPath("$.data.completedCount").isEqualTo(0)
                .jsonPath("$.data.merchantCancelledCount").isEqualTo(0)
                .jsonPath("$.data.rejectedCount").isEqualTo(1)
                .jsonPath("$.data.withdrawnCount").isEqualTo(0)
                .jsonPath("$.data.terminalCount").isEqualTo(1)
                .jsonPath("$.data.completionRate").isEqualTo(0.0);
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
        Map<String, Object> task = (Map<String, Object>) resp.get("data");
        String id = (String) task.get("id");
        int version = ((Number) task.get("version")).intValue();
        client().post().uri("/api/admin/tasks/" + id + "/review/approve")
                .header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", version))
                .exchange().expectStatus().isOk();
        return id;
    }

    private long outboxCount(String eventType) {
        return db.sql("SELECT COUNT(*)::int AS c FROM marketplace_outbox WHERE event_type = :et")
                .bind("et", eventType).map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    private int taskVersion(String taskId) {
        return db.sql("SELECT version FROM task WHERE id = CAST(:id AS uuid)")
                .bind("id", taskId).map(row -> row.get("version", Integer.class)).one().block();
    }
}
