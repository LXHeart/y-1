package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.LinkedHashMap;
import java.util.List;
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

    // ---------- GL-P1-TASK-001 Stage 1：生命周期 + 可见性 + 乐观锁 ----------

    /** immediate-publish 回归：仍 201 published，且带 version=1、publishedAt、task_version 快照行。 */
    @Test
    void immediatePublishProducesVersionAndSnapshot() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = publish(merchant, org, "basic_publish", "即时发布", null);

        client().get().uri("/api/tasks/" + id)
                .header("X-Grassland-Identity", sign(merchant, "merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("published")
                .jsonPath("$.data.version").isEqualTo(1)
                .jsonPath("$.data.publishedAt").isNotEmpty();

        Integer versions = db.sql("SELECT COUNT(*)::int AS c FROM task_version WHERE task_id = CAST(:id AS uuid)")
                .bind("id", id).map(r -> r.get("c", Integer.class)).one().block();
        assertThat(versions).isEqualTo(1);
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

        // 发布后 PUT 应被拒（非 draft → 409）。
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
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 1))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("closed");
        assertThat(outboxType(toClose, "TaskClosed")).isEqualTo(1);

        String toCancel = publish(merchant, org, "basic_publish", "消", null);
        client().post().uri("/api/tasks/" + toCancel + "/cancel")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 1))
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

    /** 草稿发布后落 task_version 快照（含编辑后的字段）。 */
    @Test
    void publishingDraftRecordsImmutableSnapshot() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = createDraft(merchant, org, "basic_publish", "原标题");
        client().put().uri("/api/tasks/" + id)
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 0, "title", "终标题", "bountyCents", 0))
                .exchange().expectStatus().isOk();
        client().post().uri("/api/tasks/" + id + "/publish")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", 1))
                .exchange().expectStatus().isOk();

        String snapTitle = db.sql("SELECT title FROM task_version WHERE task_id = CAST(:id AS uuid) ORDER BY version DESC LIMIT 1")
                .bind("id", id).map(r -> r.get("title", String.class)).one().block();
        assertThat(snapTitle).isEqualTo("终标题");
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
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
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
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
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
}
