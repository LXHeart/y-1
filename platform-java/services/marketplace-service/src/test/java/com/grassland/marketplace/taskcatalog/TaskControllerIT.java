package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.security.IdentityStoreAuthorizationClient;
import com.grassland.marketplace.security.MarketplaceException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

/**
 * task-catalog 端到端（草场 Epic 4 Slice 4A + 4B 发布限额/org 归属）。继承 {@link MarketplaceItSupport}。
 *
 * <p>4B 新增三道发布闸门：① org 归属（body.organizationId 须等于 caller.organizationId，不等/null→403）；
 * ② tier（DRAFT/null→403）；③ 按 org tier 的 maxActiveTasks 限额（超出→409）。
 * ⚠️ 既有 happy path 须用 4 参 sign（带 org + tier=basic_publish），否则 null tier 触发新闸门 403（回归）。
 */
@SuppressWarnings("unchecked")
class TaskControllerIT extends MarketplaceItSupport {

    @DynamicPropertySource
    static void deterministicFullReview(DynamicPropertyRegistry registry) {
        // 抽样免审（task-review-v1）会让「≥3 通过且 0 拒绝」的商家大概率在创建时自动上架，
        // 配额/并发审核用例需要确定性的「创建即 pending_review + 人工 approve」路径。新商家行为两态一致。
        registry.add("marketplace.task-review.policy-enabled", () -> "false");
    }

    @Test
    void reviewAndFeedSearchTreatWildcardsLiterally() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> percent = createPendingTask(merchant, org, "100% 命中任务");
        Map<String, Object> ordinary = createPendingTask(merchant, org, "普通任务");

        client().get().uri(uri -> uri.path("/api/admin/tasks/review").queryParam("q", "%").build())
                .header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "content_reviewer"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].title").isEqualTo("100% 命中任务");

        approveTask(percent);
        approveTask(ordinary);
        client().get().uri(uri -> uri.path("/api/tasks/feed").queryParam("q", "普通").build())
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].title").isEqualTo("普通任务");

        client().get().uri(uri -> uri.path("/api/tasks/feed").queryParam("q", "x".repeat(101)).build())
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void storeScopedDraftPersistsAndRequiresStoreAuthorization() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String store = UUID.randomUUID().toString();
        when(storeAuthorization.authorize(merchant, org, store, "manager"))
                .thenReturn(Mono.just(storeAccess(merchant, org, store, "manager")));

        Map<String, Object> requestBody = body(org, "门店草稿", null, null);
        requestBody.put("storeId", store);
        client().post().uri("/api/tasks/draft")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .exchange().expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.data.storeId").isEqualTo(store);

        String persistedStore = db.sql("SELECT store_id::text FROM task WHERE organization_id=CAST(:org AS uuid)")
                .bind("org", org).map(row -> row.get(0, String.class)).one().block();
        assertThat(persistedStore).isEqualTo(store);

        when(storeAuthorization.authorize(merchant, org, store, "staff"))
                .thenReturn(Mono.just(storeAccess(merchant, org, store, "manager")));
        client().get().uri("/api/tasks?organizationId=" + org + "&status=draft&storeId=" + store)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].storeId").isEqualTo(store);

        // owner 主体级视角（不传 storeId）= 全量视角：含门店草稿——门店任务的管理入口在主体
        // 工作台，列表不得按资源范围隐式过滤（否则商家「丢任务」）。推荐官视角仍不泄漏非 published。
        client().get().uri("/api/tasks?organizationId=" + org + "&status=draft")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].storeId").isEqualTo(store);
        client().get().uri("/api/tasks?organizationId=" + org + "&status=draft")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.length()").isEqualTo(0);
    }

    @Test
    void independentStoreManagersCanCreateAndEditTheSameStoreDraft() {
        String managerA = UUID.randomUUID().toString();
        String managerB = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String store = UUID.randomUUID().toString();
        when(storeAuthorization.authorize(managerA, org, store, "manager"))
                .thenReturn(Mono.just(storeAccess(managerA, org, store, "manager")));
        when(storeAuthorization.authorize(managerB, org, store, "manager"))
                .thenReturn(Mono.just(storeAccess(managerB, org, store, "manager")));

        Map<String, Object> requestBody = body(org, "独立店长草稿", null, null);
        requestBody.put("storeId", store);
        @SuppressWarnings("unchecked")
        Map<String, Object> created = client().post().uri("/api/tasks/draft")
                .header("X-Grassland-Identity", sign(managerA, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> task = (Map<String, Object>) created.get("data");

        client().put().uri("/api/tasks/" + task.get("id"))
                .header("X-Grassland-Identity", sign(managerB, null))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", task.get("version"), "title", "另一位店长已修改"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.title").isEqualTo("另一位店长已修改");
    }

    @Test
    void storeStaffCannotCreateTask() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String store = UUID.randomUUID().toString();
        when(storeAuthorization.authorize(merchant, org, store, "manager"))
                .thenReturn(Mono.error(new MarketplaceException(403, "门店权限不足")));
        Map<String, Object> requestBody = body(org, "越权草稿", null, null);
        requestBody.put("storeId", store);

        client().post().uri("/api/tasks/draft")
                .header("X-Grassland-Identity", sign(merchant, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void storeIdIsCopiedIntoPublishedTaskVersion() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String store = UUID.randomUUID().toString();
        when(storeAuthorization.authorize(merchant, org, store, "manager"))
                .thenReturn(Mono.just(storeAccess(merchant, org, store, "manager")));
        Map<String, Object> requestBody = body(org, "门店上架任务", null, null);
        requestBody.put("storeId", store);
        @SuppressWarnings("unchecked")
        Map<String, Object> response = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> task = (Map<String, Object>) response.get("data");
        approveTask(task);

        String snapshotStore = db.sql("SELECT store_id::text FROM task_version WHERE task_id=CAST(:id AS uuid)")
                .bind("id", task.get("id")).map(row -> row.get(0, String.class)).one().block();
        assertThat(snapshotStore).isEqualTo(store);
    }

    @Test
    void merchantPublishesTaskAndEvent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        @SuppressWarnings("unchecked")
        Map<String, Object> response = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(org, "爆款任务", "douyin", null))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> task = (Map<String, Object>) response.get("data");
        assertThat(task.get("ownerAccountId")).isEqualTo(merchant);
        assertThat(task.get("organizationId")).isEqualTo(org);
        assertThat(task.get("status")).isEqualTo("pending_review");
        assertThat(task.get("platform")).isEqualTo("douyin");

        Long submitted = db.sql("SELECT COUNT(*)::int AS c FROM marketplace_outbox"
                        + " WHERE event_type = 'TaskSubmittedForReview' AND payload->>'organizationId' = :org")
                .bind("org", org)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
        assertThat(submitted).isEqualTo(1);

        approveTask(task);
        assertThat(outboxType((String) task.get("id"), "TaskPublished")).isEqualTo(1);
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
                .jsonPath("$.data.monthlyTasks").isEqualTo(2)
                .jsonPath("$.data.maxActiveTasks").isEqualTo(5)
                .jsonPath("$.data.remainingActiveTasks").isEqualTo(3)
                .jsonPath("$.data.maxMonthlyTasks").isEqualTo(20)
                .jsonPath("$.data.remainingMonthlyTasks").isEqualTo(18)
                .jsonPath("$.data.maxTxAmountCents").isEqualTo(0);
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
        return approveTask((Map<String, Object>) resp.get("data"));
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

    // ---------- GL-P1-TASK-001 Stage 1：生命周期 + 可见性 + 乐观锁 ----------

    /** 全审回归：创建后显式审核通过，带 published 状态、publishedAt 和一行 task_version 快照。 */
    @Test
    void approvedTaskProducesVersionAndSnapshot() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = publish(merchant, org, "basic_publish", "即时发布", null);

        client().get().uri("/api/tasks/" + id)
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("published")
                .jsonPath("$.data.version").isEqualTo(2)
                .jsonPath("$.data.publishedAt").isNotEmpty();

        Integer versions = db.sql("SELECT COUNT(*)::int AS c FROM task_version WHERE task_id = CAST(:id AS uuid)")
                .bind("id", id).map(r -> r.get("c", Integer.class)).one().block();
        assertThat(versions).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reviewQueueSupportsFiltersStatsAndAuditHistory() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String platform = "review-" + UUID.randomUUID().toString().substring(0, 8);
        Map<String, Object> response = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(org, "审核运营任务", platform, null))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> task = (Map<String, Object>) response.get("data");
        String taskId = (String) task.get("id");
        int version = ((Number) task.get("version")).intValue();
        String reviewerHeader = signWithRole(UUID.randomUUID().toString(), "content_reviewer");

        client().get().uri("/api/admin/tasks/review?status=pending_review&organizationId="
                        + org + "&platform=" + platform + "&limit=20")
                .header("X-Grassland-Identity", reviewerHeader)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].id").isEqualTo(taskId)
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.meta.total").isEqualTo(1)
                .jsonPath("$.meta.queue.pending").isNumber();

        client().get().uri("/api/admin/tasks/" + taskId + "/review/history")
                .header("X-Grassland-Identity", reviewerHeader)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].action").isEqualTo("submitted");

        client().post().uri("/api/admin/tasks/" + taskId + "/review/approve")
                .header("X-Grassland-Identity", reviewerHeader)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", version))
                .exchange().expectStatus().isOk();

        client().get().uri("/api/admin/tasks/" + taskId + "/review/history")
                .header("X-Grassland-Identity", reviewerHeader)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(2)
                .jsonPath("$.data[0].action").isEqualTo("approved")
                .jsonPath("$.data[1].action").isEqualTo("submitted");
        client().get().uri("/api/admin/tasks/review/stats")
                .header("X-Grassland-Identity", reviewerHeader)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.approvedLast24Hours").isNumber();
    }

    @Test
    @SuppressWarnings("unchecked")
    void concurrentReviewApprovalsCannotExceedOrganizationActiveQuota() throws Exception {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        when(storeAuthorization.authorize(eq(merchant), eq(org), isNull(), eq("manager")))
                .thenReturn(Mono.just(storeAccess(merchant, org, null, "manager")));
        for (int index = 0; index < 4; index++) {
            publish(merchant, org, "basic_publish", "额度占位-" + index, null);
        }
        Map<String, Object> first = createPendingTask(merchant, org, "并发审核-A");
        Map<String, Object> second = createPendingTask(merchant, org, "并发审核-B");
        String reviewer = signWithRole(UUID.randomUUID().toString(), "platform_admin");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Integer> firstStatus = new AtomicReference<>();
        AtomicReference<Integer> secondStatus = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread a = new Thread(() -> runConcurrent(ready, start, failure,
                () -> firstStatus.set(approveStatus(first, reviewer))));
        Thread b = new Thread(() -> runConcurrent(ready, start, failure,
                () -> secondStatus.set(approveStatus(second, reviewer))));
        a.start();
        b.start();
        assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        a.join(10_000);
        b.join(10_000);

        assertThat(failure.get()).isNull();
        assertThat(List.of(firstStatus.get(), secondStatus.get())).containsExactlyInAnyOrder(200, 409);
        Integer active = db.sql("SELECT COUNT(*)::int AS c FROM task"
                        + " WHERE organization_id = CAST(:org AS uuid) AND status = 'published'")
                .bind("org", org).map(row -> row.get("c", Integer.class)).one().block();
        assertThat(active).isEqualTo(5);
    }

    /** 草稿 tier 商家可建草稿（不占发布额度、不需资金权限）。 */
    @Test
    void draftTierMerchantCanCreateDraft() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        client().post().uri("/api/tasks/draft")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "draft"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(org, "草稿任务", null, null))
                .exchange().expectStatus().isCreated().expectBody()
                .jsonPath("$.data.status").isEqualTo("draft")
                .jsonPath("$.data.version").isEqualTo(0);

        // 草稿不计活跃额度（仅 published 计）。
        Integer active = db.sql("SELECT COUNT(*)::int AS c FROM task WHERE organization_id = CAST(:org AS uuid) AND status = 'published'")
                .bind("org", org).map(r -> r.get("c", Integer.class)).one().block();
        assertThat(active).isZero();
    }

    /** 编辑草稿：version +1；非 draft 态不可编辑（409）。 */
    @Test
    void editDraftBumpsVersionAndRejectedAfterPublish() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = createDraft(merchant, org, "basic_publish", "标题");

        client().put().uri("/api/tasks/" + id)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 0, "title", "改后标题"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.title").isEqualTo("改后标题")
                .jsonPath("$.data.version").isEqualTo(1);

        // 提交审核后 PUT 应被拒（非 draft → 409）。
        client().post().uri("/api/tasks/" + id + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 1))
                .exchange().expectStatus().isOk();
        client().put().uri("/api/tasks/" + id)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1, "title", "再改"))
                .exchange().expectStatus().isEqualTo(409);
    }

    /** 乐观锁：expectedVersion 不匹配 → 409，状态不变。 */
    @Test
    void publishWithStaleVersionConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = createDraft(merchant, org, "basic_publish", "锁");
        client().post().uri("/api/tasks/" + id + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 99))
                .exchange().expectStatus().isEqualTo(409);
        // 仍 draft（未迁移）
        assertThat(taskStatus(id)).isEqualTo("draft");
    }

    /** 草稿 tier 不可发布（与 immediate-publish 同闸门）。 */
    @Test
    void draftTierCannotPublishDraft() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = createDraft(merchant, org, "draft", "草稿");
        client().post().uri("/api/tasks/" + id + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "draft"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 0))
                .exchange().expectStatus().isForbidden();
    }

    /** 关闭/取消 终态迁移 + TaskClosed/TaskCancelled 事件。 */
    @Test
    void closeAndCancelEmitEventsAndTransition() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String toClose = publish(merchant, org, "basic_publish", "关", null);
        client().post().uri("/api/tasks/" + toClose + "/close")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 2))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("closed");
        assertThat(outboxType(toClose, "TaskClosed")).isEqualTo(1);

        String toCancel = publish(merchant, org, "basic_publish", "消", null);
        client().post().uri("/api/tasks/" + toCancel + "/cancel")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 2))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("cancelled");
        assertThat(outboxType(toCancel, "TaskCancelled")).isEqualTo(1);
    }

    /** 可见性：非 owner GET 草稿/取消任务 → 404（不泄露存在）。 */
    @Test
    void nonOwnerCannotSeeDraftOrCancelledDetail() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String draftId = createDraft(merchant, org, "basic_publish", "私密草稿");

        client().get().uri("/api/tasks/" + draftId)
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isNotFound();

        // owner 仍可见。
        client().get().uri("/api/tasks/" + draftId)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk();
    }

    /** 可见性：非本 org 商家查 draft status → 回落 published（防跨组织草稿泄露）。 */
    @Test
    void otherOrgMerchantCannotListDrafts() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        createDraft(merchant, org, "basic_publish", "他家草稿");

        String outsider = UUID.randomUUID().toString();
        client().get().uri("/api/tasks?organizationId=" + org + "&status=draft")
                .header("X-Grassland-Identity", sign(outsider, "merchant", UUID.randomUUID().toString(), "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").value(l -> assertThat((Integer) l).isZero());
    }

    /** 草稿提交审核并通过后落 task_version 快照（含编辑后的字段）。 */
    @Test
    @SuppressWarnings("unchecked")
    void publishingDraftRecordsImmutableSnapshot() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = createDraft(merchant, org, "basic_publish", "原标题");
        Map<String, Object> requirements = Map.of(
                "productServiceInfo", "双人招牌套餐",
                "mustInclude", List.of("门店名", "招牌菜"),
                "forbiddenContent", List.of("绝对化功效"),
                "publishStartAt", "2026-08-20T10:00:00Z",
                "publishEndAt", "2026-08-25T10:00:00Z",
                "metricRequirements", List.of("发布后 24 小时播放量"),
                "evidenceRequirements", List.of("发布链接", "后台截图"));
        client().put().uri("/api/tasks/" + id)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 0, "title", "终标题", "bountyCents", 0,
                        "requirements", requirements))
                .exchange().expectStatus().isOk();
        Map<String, Object> response = client().post().uri("/api/tasks/" + id + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 1))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> task = (Map<String, Object>) response.get("data");
        assertThat(task.get("status")).isEqualTo("pending_review");
        assertThat(task.get("version")).isEqualTo(2);
        approveTask(task);

        String snapTitle = db.sql("SELECT title FROM task_version WHERE task_id = CAST(:id AS uuid) ORDER BY version DESC LIMIT 1")
                .bind("id", id).map(r -> r.get("title", String.class)).one().block();
        assertThat(snapTitle).isEqualTo("终标题");
        String snapshotRequirements = db.sql("SELECT requirements::text FROM task_version "
                        + "WHERE task_id = CAST(:id AS uuid) ORDER BY version DESC LIMIT 1")
                .bind("id", id).map(r -> r.get("requirements", String.class)).one().block();
        assertThat(snapshotRequirements)
                .contains("双人招牌套餐", "门店名", "绝对化功效", "2026-08-20T10:00:00Z",
                        "发布后 24 小时播放量", "后台截图");
    }

    // ---------- GL-P1-TASK-001：编辑出新版本（restricted revise） ----------

    /** 修订已发布任务：version+1、新快照、outbox TaskRevised；赏金冻结（请求体不含 bountyCents → 不被触及）。 */
    @Test
    @SuppressWarnings("unchecked")
    void reviseBumpsVersionWritesSnapshotAndCanChangeBounty() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> bountyBody = body(org, "原标题", null, 3);
        bountyBody.put("bountyCents", 500); // 资金型任务
        Map<String, Object> task = (Map<String, Object>) client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(bountyBody)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("data");
        String id = approveTask(task);

        // 全字段修订：改 title + 赏金 500→800（accept/结算读 app 快照，已 accept 履约不受影响）。
        client().post().uri("/api/tasks/" + id + "/revise")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 2, "title", "修订标题", "maxSlots", 5, "bountyCents", 800))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.title").isEqualTo("修订标题")
                .jsonPath("$.data.version").isEqualTo(3)
                .jsonPath("$.data.maxSlots").isEqualTo(5)
                .jsonPath("$.data.bountyCents").isEqualTo(800); // 赏金可改

        Integer versions = db.sql("SELECT COUNT(*)::int AS c FROM task_version WHERE task_id = CAST(:id AS uuid)")
                .bind("id", id).map(r -> r.get("c", Integer.class)).one().block();
        assertThat(versions).isEqualTo(2); // v2 审核发布快照 + v3 修订快照
        assertThat(outboxType(id, "TaskRevised")).isEqualTo(1);
    }

    /** 修订赏金超 tier 单笔上限 → 409（finance_transaction 上限 ¥100000 = 10_000_000 分）。 */
    @Test
    void reviseBountyAboveTierMaxRejected() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> bountyBody = body(org, "上限测", null, null);
        bountyBody.put("bountyCents", 500);
        @SuppressWarnings("unchecked")
        Map<String, Object> task = (Map<String, Object>) client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(bountyBody)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("data");
        String id = approveTask(task);

        client().post().uri("/api/tasks/" + id + "/revise")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 2, "title", "超限", "bountyCents", 20_000_000L))
                .exchange().expectStatus().isEqualTo(409);
    }

    /** 非 owner 修订 → 403。 */
    @Test
    void reviseRejectsNonOwner() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = publish(merchant, org, "basic_publish", "非owner", null);
        client().post().uri("/api/tasks/" + id + "/revise")
                .header("X-Grassland-Identity",
                        sign(UUID.randomUUID().toString(), "merchant", UUID.randomUUID().toString(), "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 2, "title", "篡改"))
                .exchange().expectStatus().isForbidden();
    }

    /** 仅 published 可修订；draft / closed → 409。 */
    @Test
    void reviseOnlyAllowedOnPublished() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String draftId = createDraft(merchant, org, "basic_publish", "草稿");
        client().post().uri("/api/tasks/" + draftId + "/revise")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 0, "title", "x"))
                .exchange().expectStatus().isEqualTo(409);

        String closedId = publish(merchant, org, "basic_publish", "已关", null);
        client().post().uri("/api/tasks/" + closedId + "/close")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 2))
                .exchange().expectStatus().isOk();
        client().post().uri("/api/tasks/" + closedId + "/revise")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 3, "title", "x"))
                .exchange().expectStatus().isEqualTo(409);
    }

    /** 乐观锁：expectedVersion 不匹配 → 409，状态与 version 不变。 */
    @Test
    void reviseStaleVersionConflict() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = publish(merchant, org, "basic_publish", "锁", null);
        client().post().uri("/api/tasks/" + id + "/revise")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 99, "title", "x"))
                .exchange().expectStatus().isEqualTo(409);
        assertThat(taskStatus(id)).isEqualTo("published");
    }

    // ---------- GL-P1-TASK-001 Stage 2：全局任务大厅 feed ----------

    /** feed 跨组织、仅 published：我发的两条在、草稿不在、返回项全 published（单例容器数据累积，不锁总数）。 */
    @Test
    @SuppressWarnings("unchecked")
    void feedReturnsOnlyPublishedAcrossOrgs() {
        String orgA = UUID.randomUUID().toString();
        String orgB = UUID.randomUUID().toString();
        publish(UUID.randomUUID().toString(), orgA, "basic_publish", "feed-A-唯一", null);
        publish(UUID.randomUUID().toString(), orgB, "basic_publish", "feed-B-唯一", null);
        createDraft(UUID.randomUUID().toString(), orgA, "basic_publish", "feed-草稿-唯一");

        List<Map<String, Object>> items = itemsOf(client().get().uri("/api/tasks/feed?limit=50")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody());
        List<String> titles = items.stream().map(m -> (String) m.get("title")).toList();
        assertThat(titles).contains("feed-A-唯一", "feed-B-唯一").doesNotContain("feed-草稿-唯一");
        assertThat(items).allSatisfy(item -> assertThat(item.get("status")).isEqualTo("published"));
    }

    /** keyset 游标分页：用唯一 platform 隔离恰好 3 条，limit=2 → 第 1 页 2 条 hasMore，第 2 页 1 条无重叠。 */
    @Test
    @SuppressWarnings("unchecked")
    void feedPaginatesByCursorWithoutOverlap() {
        String platform = "pg" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);  // ≤ varchar(32)
        String org = UUID.randomUUID().toString();
        for (int i = 0; i < 3; i++) {
            publishPlatform(UUID.randomUUID().toString(), org, "t" + i, platform);
        }

        Map<String, Object> firstBody = feedPage(null, 2, platform);
        List<Map<String, Object>> firstItems = itemsOf(firstBody);
        Map<String, Object> firstData = dataOf(firstBody);
        assertThat(firstItems).hasSize(2);
        assertThat(firstData.get("hasMore")).isEqualTo(true);
        String nextCursor = (String) firstData.get("nextCursor");
        assertThat(nextCursor).isNotBlank();

        Map<String, Object> secondBody = feedPage(nextCursor, 2, platform);
        List<Map<String, Object>> secondItems = itemsOf(secondBody);
        Map<String, Object> secondData = dataOf(secondBody);
        assertThat(secondItems).hasSize(1);
        assertThat(secondData.get("hasMore")).isEqualTo(false);
        List<String> firstIds = firstItems.stream().map(m -> (String) m.get("id")).toList();
        assertThat(secondItems.stream().map(m -> (String) m.get("id")).toList()).noneMatch(firstIds::contains);
    }

    /** 平台筛选：douyin 任务在结果、xiaohongshu 不在。 */
    @Test
    void feedFiltersByPlatform() {
        String org = UUID.randomUUID().toString();
        String douyinId = publishPlatform(UUID.randomUUID().toString(), org, "抖音", "douyin");
        String xhsId = publishPlatform(UUID.randomUUID().toString(), org, "小红书", "xiaohongshu");

        List<String> ids = itemsOf(feedPage(null, 20, "douyin")).stream().map(m -> (String) m.get("id")).toList();
        assertThat(ids).contains(douyinId).doesNotContain(xhsId);
    }

    @Test
    @SuppressWarnings("unchecked")
    void feedFiltersNearbyStoresBeforePagingAndReturnsDistance() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String nearbyStore = UUID.randomUUID().toString();
        String distantStore = UUID.randomUUID().toString();
        String platform = "near" + UUID.randomUUID().toString().replace("-", "").substring(0, 7);
        when(storeAuthorization.authorize(merchant, org, nearbyStore, "manager"))
                .thenReturn(Mono.just(storeAccess(merchant, org, nearbyStore, "manager")));
        when(storeAuthorization.authorize(merchant, org, distantStore, "manager"))
                .thenReturn(Mono.just(storeAccess(merchant, org, distantStore, "manager")));

        Map<String, Object> nearBody = body(org, "附近任务", platform, null);
        nearBody.put("storeId", nearbyStore);
        Map<String, Object> farBody = body(org, "远处任务", platform, null);
        farBody.put("storeId", distantStore);
        Map<String, Object> nearTask = (Map<String, Object>) client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(nearBody).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("data");
        Map<String, Object> farTask = (Map<String, Object>) client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(farBody).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("data");
        approveTask(nearTask);
        approveTask(farTask);
        when(storeAuthorization.nearby(31.23d, 121.47d, 5d)).thenReturn(Mono.just(List.of(
                new IdentityStoreAuthorizationClient.NearbyStore(nearbyStore, 31.231d, 121.471d, 1.24d))));

        Map<String, Object> response = client().get().uri("/api/tasks/feed?platform=" + platform
                        + "&latitude=31.23&longitude=121.47&maxDistanceKm=5&limit=1")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        List<Map<String, Object>> items = itemsOf(response);
        assertThat(items).hasSize(1);
        assertThat(items.get(0)).containsEntry("id", nearTask.get("id")).containsEntry("distanceKm", 1.2);
        assertThat(dataOf(response).get("hasMore")).isEqualTo(false);
    }

    /** 任务书 #24：feed 行携带门店公开块；页内去重后的 storeId 只批量拉一次（不逐行）。 */
    @Test
    @SuppressWarnings("unchecked")
    void feedItemsCarryStoreBlockFromBatchEnrichment() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String store = UUID.randomUUID().toString();
        String platform = "st" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        when(storeAuthorization.authorize(merchant, org, store, "manager"))
                .thenReturn(Mono.just(storeAccess(merchant, org, store, "manager")));
        String taskId = null;
        for (int i = 0; i < 2; i++) {
            Map<String, Object> b = body(org, "门店块任务" + i, platform, null);
            b.put("storeId", store);
            Map<String, Object> task = (Map<String, Object>) client().post().uri("/api/tasks")
                    .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                    .contentType(MediaType.APPLICATION_JSON).bodyValue(b).exchange().expectStatus().isCreated()
                    .expectBody(Map.class).returnResult().getResponseBody().get("data");
            taskId = approveTask(task);
        }
        when(storeAuthorization.publicProfiles(any())).thenReturn(Mono.just(List.of(
                new IdentityStoreAuthorizationClient.StorePublicProfile(
                        store, "旗舰店", "{\"city\":\"上海\",\"address\":\"南京西路 1 号\"}",
                        null, null, null, List.of("火锅"), List.of(), null, null, null,
                        List.of(), null, List.of(), List.of(), List.of()))));

        List<Map<String, Object>> items = itemsOf(feedPage(null, 20, platform));
        assertThat(items).hasSize(2).allSatisfy(item -> {
            Map<String, Object> block = (Map<String, Object>) item.get("store");
            assertThat(block).containsEntry("storeName", "旗舰店").containsEntry("city", "上海");
            assertThat((List<String>) block.get("categories")).containsExactly("火锅");
        });
        // 两条任务同一门店 → 页内去重后批量端点只调一次。
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(storeAuthorization, atLeastOnce()).publicProfiles(captor.capture());
        assertThat(captor.getAllValues()).allSatisfy(ids -> assertThat(ids).containsExactly(store));

        // 任务详情同样携带门店块。
        client().get().uri("/api/tasks/" + taskId)
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.store.storeName").isEqualTo("旗舰店")
                .jsonPath("$.data.store.city").isEqualTo("上海")
                .jsonPath("$.data.store.categories[0]").isEqualTo("火锅");
    }

    /** 任务书 #24：identity 批量拉失败时 feed 降级（无 store 键但行照常返回）。 */
    @Test
    void feedDegradesWhenStoreEnrichmentFails() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String store = UUID.randomUUID().toString();
        String platform = "dg" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        when(storeAuthorization.authorize(merchant, org, store, "manager"))
                .thenReturn(Mono.just(storeAccess(merchant, org, store, "manager")));
        Map<String, Object> b = body(org, "降级任务", platform, null);
        b.put("storeId", store);
        Map<String, Object> task = (Map<String, Object>) client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b).exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("data");
        String taskId = approveTask(task);
        when(storeAuthorization.publicProfiles(any()))
                .thenReturn(Mono.error(new IllegalStateException("identity down")));

        List<Map<String, Object>> items = itemsOf(feedPage(null, 20, platform));
        assertThat(items).extracting(item -> item.get("id")).contains(taskId);
        assertThat(items).allSatisfy(item -> assertThat(item).doesNotContainKey("store"));
    }

    @Test
    void feedRejectsPartialDistanceQuery() {
        client().get().uri("/api/tasks/feed?latitude=31.23&maxDistanceKm=5")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isBadRequest();
    }

    /** 过期 deadline 被排除；未来 deadline 仍可见（用唯一 platform 隔离累积数据）。 */
    @Test
    void feedExcludesExpiredDeadlineTasks() {
        String platform = "dl" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);  // ≤ varchar(32)
        String org = UUID.randomUUID().toString();
        String expired = publishDeadlinePlatform(UUID.randomUUID().toString(), org,
                java.time.Instant.now().minusSeconds(3600), platform);
        String open = publishDeadlinePlatform(UUID.randomUUID().toString(), org,
                java.time.Instant.now().plusSeconds(3600), platform);

        List<String> ids = itemsOf(feedPage(null, 20, platform)).stream().map(m -> (String) m.get("id")).toList();
        assertThat(ids).containsExactly(open).doesNotContain(expired);
    }

    @Test
    @SuppressWarnings("unchecked")
    void minimumLevelTaskIsHiddenFromIneligibleRecommender() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String lowLevelAccount = UUID.randomUUID().toString();
        String platform = "level" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        Map<String, Object> request = body(org, "Lv3 专属任务", platform, null);
        request.put("minRecommenderLevel", 3);

        Map<String, Object> response = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> task = (Map<String, Object>) response.get("data");
        String taskId = approveTask(task);

        client().get().uri("/api/tasks/feed?platform=" + platform)
                .header("X-Grassland-Identity", sign(lowLevelAccount, "recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(0);
        client().get().uri("/api/tasks/" + taskId)
                .header("X-Grassland-Identity", sign(lowLevelAccount, "recommender"))
                .exchange().expectStatus().isNotFound();

        client().get().uri("/api/tasks/" + taskId)
                .header("X-Grassland-Identity", sign(lowLevelAccount, "merchant"))
                .exchange().expectStatus().isNotFound();
        client().get().uri("/api/tasks?organizationId=" + org)
                .header("X-Grassland-Identity", sign(lowLevelAccount, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(0);

        client().get().uri("/api/tasks/" + taskId)
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.minRecommenderLevel").isEqualTo(3);
    }

    @Test
    void minimumLevelOutsideSupportedRangeIsRejected() {
        String org = UUID.randomUUID().toString();
        Map<String, Object> request = body(org, "非法等级", null, null);
        request.put("minRecommenderLevel", 6);
        client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(request)
                .exchange().expectStatus().isBadRequest();
    }

    /** 坏游标不报错，按首页返回（避免前端持过期游标硬失败）。 */
    @Test
    void feedIgnoresInvalidCursor() {
        String org = UUID.randomUUID().toString();
        publish(UUID.randomUUID().toString(), org, "basic_publish", "x", null);
        client().get().uri("/api/tasks/feed?cursor=not-a-valid-cursor")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isOk();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> feedPage(String cursor, int limit, String platform) {
        String uri = "/api/tasks/feed?limit=" + limit;
        if (platform != null) {
            uri += "&platform=" + platform;
        }
        if (cursor != null) {
            uri += "&cursor=" + cursor;
        }
        return client().get().uri(uri)
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
    }

    private Map<String, Object> feedPage(String cursor, int limit) {
        return feedPage(cursor, limit, null);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) ((Map<String, Object>) body.get("data")).get("items");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(Map<String, Object> body) {
        return (Map<String, Object>) body.get("data");
    }

    @SuppressWarnings("unchecked")
    private String publishPlatform(String merchant, String org, String title, String platform) {
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body(org, title, platform, null))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return approveTask((Map<String, Object>) resp.get("data"));
    }

    @SuppressWarnings("unchecked")
    private String publishDeadlinePlatform(String merchant, String org, java.time.Instant deadline, String platform) {
        Map<String, Object> b = body(org, "截止任务", platform, null);
        b.put("applicationDeadline", deadline.toString());
        Map<String, Object> resp = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(b)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return approveTask((Map<String, Object>) resp.get("data"));
    }

    private String approveTask(Map<String, Object> task) {
        String taskId = (String) task.get("id");
        int version = ((Number) task.get("version")).intValue();
        client().post().uri("/api/admin/tasks/" + taskId + "/review/approve")
                .header("X-Grassland-Identity",
                        signWithRole(UUID.randomUUID().toString(), "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", version))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("published");
        return taskId;
    }

    // ---------- 任务书 #53：信封分页 + 任务审核三态 ----------

    /** 审核队列 status=published 列已上架任务（信封形状，meta 补 total）。 */
    @Test
    void reviewQueueListsPublishedTasksInEnvelope() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = publish(merchant, org, "basic_publish", "队列内已上架", null);
        String reviewer = signWithRole(UUID.randomUUID().toString(), "content_reviewer");

        client().get().uri("/api/admin/tasks/review?status=published&organizationId=" + org)
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].id").isEqualTo(id)
                .jsonPath("$.data.items[0].status").isEqualTo("published")
                .jsonPath("$.data.total").isEqualTo(1)
                .jsonPath("$.data.limit").isEqualTo(50)
                .jsonPath("$.data.offset").isEqualTo(0)
                .jsonPath("$.meta.total").isEqualTo(1)
                .jsonPath("$.meta.queue.pending").isNumber();
    }

    /**
     * status=rejected 视图：最新决定为驳回的 draft 任务带 lastReviewNote/lastReviewedAt；
     * 重新提交（追加 submitted 记录）后移出视图。
     */
    @Test
    @SuppressWarnings("unchecked")
    void rejectedViewShowsLatestRejectionAndClearsAfterResubmission() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> pending = createPendingTask(merchant, org, "被驳回任务");
        Map<String, Object> rejected = rejectTask(pending, "标题含绝对化用语");
        String reviewer = signWithRole(UUID.randomUUID().toString(), "content_reviewer");

        client().get().uri("/api/admin/tasks/review?status=rejected&organizationId=" + org)
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].id").isEqualTo(pending.get("id"))
                .jsonPath("$.data.items[0].status").isEqualTo("draft")
                .jsonPath("$.data.items[0].lastReviewAction").isEqualTo("rejected")
                .jsonPath("$.data.items[0].lastReviewNote").isEqualTo("标题含绝对化用语")
                .jsonPath("$.data.items[0].lastReviewedAt").isNotEmpty()
                .jsonPath("$.data.total").isEqualTo(1);

        // 重新提交 → 最新记录变 submitted → 移出 rejected 视图。
        client().post().uri("/api/tasks/" + pending.get("id") + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", ((Number) rejected.get("version")).intValue()))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.status").isEqualTo("pending_review");
        client().get().uri("/api/admin/tasks/review?status=rejected&organizationId=" + org)
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(0)
                .jsonPath("$.data.total").isEqualTo(0);
    }

    /** 语义红线：历史上被驳回、现已 published 的任务不出现在 rejected 视图。 */
    @Test
    @SuppressWarnings("unchecked")
    void historicallyRejectedPublishedTaskIsAbsentFromRejectedView() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> pending = createPendingTask(merchant, org, "驳回后重新过审");
        Map<String, Object> rejected = rejectTask(pending, "封面图不清晰");
        // 重新提交并通过审核 → published。
        Map<String, Object> resubmit = (Map<String, Object>) client().post()
                .uri("/api/tasks/" + pending.get("id") + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", ((Number) rejected.get("version")).intValue()))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody().get("data");
        approveTask(resubmit);
        String reviewer = signWithRole(UUID.randomUUID().toString(), "content_reviewer");

        client().get().uri("/api/admin/tasks/review?status=rejected&organizationId=" + org)
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(0)
                .jsonPath("$.data.total").isEqualTo(0);
        // sanity：任务确实已上架，且历史驳回记录仍在审计流水里。
        client().get().uri("/api/admin/tasks/review?status=published&organizationId=" + org)
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items[0].id").isEqualTo(pending.get("id"));
        client().get().uri("/api/admin/tasks/" + pending.get("id") + "/review/history")
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(4); // submitted/rejected/submitted/approved
    }

    /** 商家端 GET /api/tasks：被驳回草稿带 lastRejectedNote/lastRejectedAt；published 任务恒 null（形状仍裸数组）。 */
    @Test
    void merchantListingExposesLastRejectedOnlyForRejectedDrafts() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> pending = createPendingTask(merchant, org, "商家驳回回显");
        rejectTask(pending, "需更换配图");
        publish(merchant, org, "basic_publish", "正常上架任务", null);
        String merchantHeader = sign(merchant, "merchant", org, "basic_publish");

        client().get().uri("/api/tasks?organizationId=" + org + "&status=draft")
                .header("X-Grassland-Identity", merchantHeader)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].id").isEqualTo(pending.get("id"))
                .jsonPath("$.data[0].lastRejectedNote").isEqualTo("需更换配图")
                .jsonPath("$.data[0].lastRejectedAt").isNotEmpty()
                .jsonPath("$.data[0].lastReviewAction").isEqualTo("rejected");

        client().get().uri("/api/tasks?organizationId=" + org)
                .header("X-Grassland-Identity", merchantHeader)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].status").isEqualTo("published")
                .jsonPath("$.data[0].lastRejectedNote").value(v -> assertThat(v).isNull())
                .jsonPath("$.data[0].lastRejectedAt").value(v -> assertThat(v).isNull());
    }

    /**
     * 问题一连带缺陷：门店级草稿被驳回后，owner 主体级列表（不传 storeId）也要回带真实驳回原因
     * ——此前 findByStore 无 review join，门店草稿的 lastRejectedNote 恒 null（前端显示「平台未填写原因」）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void ownerListingCarriesRejectedNoteForStoreScopedDraft() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String store = UUID.randomUUID().toString();
        when(storeAuthorization.authorize(merchant, org, store, "manager"))
                .thenReturn(Mono.just(storeAccess(merchant, org, store, "manager")));
        Map<String, Object> requestBody = body(org, "门店草稿被驳回", null, null);
        requestBody.put("storeId", store);
        Map<String, Object> draft = (Map<String, Object>) client().post().uri("/api/tasks/draft")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(requestBody)
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody().get("data");
        // 提交审核 → 驳回：任务回 draft 且留 task_review 记录。
        Map<String, Object> submitted = (Map<String, Object>) client()
                .post().uri("/api/tasks/" + draft.get("id") + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", ((Number) draft.get("version")).intValue()))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody().get("data");
        rejectTask(submitted, "门店任务驳回原因");

        client().get().uri("/api/tasks?organizationId=" + org + "&status=draft")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].storeId").isEqualTo(store)
                .jsonPath("$.data[0].lastRejectedNote").isEqualTo("门店任务驳回原因")
                .jsonPath("$.data[0].lastReviewAction").isEqualTo("rejected");
    }

    /** 审核队列信封：offset 取第二页、total 与筛选同口径、limit/offset 钳制边界。 */
    @Test
    void reviewQueueEnvelopePaginatesAndClamps() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        Map<String, Object> first = createPendingTask(merchant, org, "信封分页-A");
        createPendingTask(merchant, org, "信封分页-B");
        String reviewer = signWithRole(UUID.randomUUID().toString(), "content_reviewer");

        // 分页生效：limit=1 两页各一条不重叠，total 恒 2。
        client().get().uri("/api/admin/tasks/review?organizationId=" + org + "&limit=1")
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].id").isEqualTo(first.get("id")) // updated_at ASC：先提交在前
                .jsonPath("$.data.total").isEqualTo(2)
                .jsonPath("$.data.limit").isEqualTo(1)
                .jsonPath("$.data.offset").isEqualTo(0)
                .jsonPath("$.meta.total").isEqualTo(2);
        client().get().uri("/api/admin/tasks/review?organizationId=" + org + "&limit=1&offset=1")
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.items.length()").isEqualTo(1)
                .jsonPath("$.data.items[0].id").value(v -> assertThat(v).isNotEqualTo(first.get("id")))
                .jsonPath("$.data.total").isEqualTo(2)
                .jsonPath("$.data.offset").isEqualTo(1);

        // total 与筛选同口径：叠加 status 筛选后仍是 2，换 published 口径归 0。
        client().get().uri("/api/admin/tasks/review?organizationId=" + org
                        + "&status=pending_review&limit=1")
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.total").isEqualTo(2);
        client().get().uri("/api/admin/tasks/review?organizationId=" + org + "&status=published")
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.total").isEqualTo(0)
                .jsonPath("$.data.items.length()").isEqualTo(0);

        // 钳制边界：limit=0→50、limit>200→200、offset<0→0。
        client().get().uri("/api/admin/tasks/review?organizationId=" + org + "&limit=0")
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.limit").isEqualTo(50)
                .jsonPath("$.meta.limit").isEqualTo(50);
        client().get().uri("/api/admin/tasks/review?organizationId=" + org + "&limit=201")
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.limit").isEqualTo(200)
                .jsonPath("$.meta.limit").isEqualTo(200);
        client().get().uri("/api/admin/tasks/review?organizationId=" + org + "&offset=-5")
                .header("X-Grassland-Identity", reviewer)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.offset").isEqualTo(0)
                .jsonPath("$.meta.offset").isEqualTo(0);
    }

    /** 审核驳回辅助：待审核任务 → draft，返回驳回后的任务体（version 已 +1）。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> rejectTask(Map<String, Object> task, String note) {
        Map<String, Object> response = client().post()
                .uri("/api/admin/tasks/" + task.get("id") + "/review/reject")
                .header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", ((Number) task.get("version")).intValue(), "note", note))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        Map<String, Object> rejected = (Map<String, Object>) response.get("data");
        assertThat(rejected.get("status")).isEqualTo("draft");
        return rejected;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createPendingTask(String merchant, String org, String title) {
        Map<String, Object> response = client().post().uri("/api/tasks")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body(org, title, null, null))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (Map<String, Object>) response.get("data");
    }

    private int approveStatus(Map<String, Object> task, String reviewerHeader) {
        return client().post().uri("/api/admin/tasks/" + task.get("id") + "/review/approve")
                .header("X-Grassland-Identity", reviewerHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", ((Number) task.get("version")).intValue()))
                .exchange().returnResult(Void.class).getStatus().value();
    }

    private static void runConcurrent(CountDownLatch ready, CountDownLatch start,
                                      AtomicReference<Throwable> failure, Runnable action) {
        try {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrent test start timeout");
            }
            action.run();
        } catch (Throwable error) {
            failure.compareAndSet(null, error);
        }
    }

    @SuppressWarnings("unchecked")
    private String createDraft(String merchant, String org, String tier, String title) {
        Map<String, Object> resp = client().post().uri("/api/tasks/draft")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, tier))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(org, title, null, null))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    private String taskStatus(String id) {
        return db.sql("SELECT status FROM task WHERE id = CAST(:id AS uuid)")
                .bind("id", id).map(r -> r.get("status", String.class)).one().block();
    }

    private long outboxType(String taskId, String eventType) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM marketplace_outbox"
                        + " WHERE event_type = :et AND payload->>'taskId' = :tid")
                .bind("et", eventType).bind("tid", taskId)
                .map(row -> row.get("c", Long.class)).one().block();
        return c == null ? 0L : c;
    }

    private IdentityStoreAuthorizationClient.Authorization storeAccess(
            String accountId, String organizationId, String storeId, String role) {
        return new IdentityStoreAuthorizationClient.Authorization(
                true, accountId, organizationId, storeId, role, "store", "basic_publish");
    }
}
