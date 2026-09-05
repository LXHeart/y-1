package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import reactor.core.publisher.Mono;

import com.grassland.marketplace.MarketplaceItSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 推荐官「我的报名」历史列表（任务书 #29+#30 Stage 2）。锁住：
 * <ul>
 * <li>self-scoped——只见本人报名（recommender 烧进 WHERE）；</li>
 * <li>keyset 分页（cursor 翻页不漏不重）；status 过滤；</li>
 * <li>join task 字段正确（标题/状态/赏金）；settledAt 取 EngagementSettled outbox；</li>
 * <li>路由挂在既有 {@code /api/tasks/**} 前缀下端点可达（edge fail-closed 不误伤）。</li>
 * </ul>
 */
class MyApplicationsControllerIT extends MarketplaceItSupport {

	@Test
	void listsOnlyOwnApplicationsWithTaskJoin() {
		String rec = UUID.randomUUID().toString();
		String other = UUID.randomUUID().toString();
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task1 = UUID.randomUUID().toString();
		String task2 = UUID.randomUUID().toString();
		String task3 = UUID.randomUUID().toString();
		seedTask(task1, merchant, org, "探店视频任务", "published", 5000L);
		seedTask(task2, merchant, org, "图文种草任务", "closed", 3000L);
		seedTask(task3, merchant, org, "别人的任务", "published", 1000L);
		String app1 = UUID.randomUUID().toString();
		String app2 = UUID.randomUUID().toString();
		String appOther = UUID.randomUUID().toString();
		seedApplication(app1, task1, rec, "accepted", 5000L, Instant.parse("2026-08-01T10:00:00Z"));
		seedApplication(app2, task2, rec, "pending", 3000L, Instant.parse("2026-08-02T10:00:00Z"));
		seedApplication(appOther, task3, other, "accepted", 1000L, Instant.parse("2026-08-03T10:00:00Z"));
		seedSettled(app1, Instant.parse("2026-08-05T10:00:00Z"));

		client().get().uri("/api/tasks/my-applications").header("X-Grassland-Identity", sign(rec, "recommender"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.items.length()").isEqualTo(2)
				// 按 created_at DESC：app2（8-02）在前，app1（8-01）在后
				.jsonPath("$.data.items[0].applicationId").isEqualTo(app2).jsonPath("$.data.items[0].taskTitle")
				.isEqualTo("图文种草任务").jsonPath("$.data.items[0].taskStatus").isEqualTo("closed")
				.jsonPath("$.data.items[0].applicationStatus").isEqualTo("pending")
				.jsonPath("$.data.items[0].bountyCents").isEqualTo(3000).jsonPath("$.data.items[1].applicationId")
				.isEqualTo(app1).jsonPath("$.data.items[1].taskTitle").isEqualTo("探店视频任务")
				.jsonPath("$.data.items[1].settledAt").isEqualTo("2026-08-05T10:00:00Z").jsonPath("$.data.hasMore")
				.isEqualTo(false);
	}

	@Test
	void paginatesWithCursor() {
		String rec = UUID.randomUUID().toString();
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		// 3 个报名，limit=2 → 第一页 2 条 + hasMore，翻页 1 条
		String[] apps = new String[3];
		for (int i = 0; i < 3; i++) {
			String task = UUID.randomUUID().toString();
			seedTask(task, merchant, org, "任务" + i, "published", 100L * (i + 1));
			apps[i] = UUID.randomUUID().toString();
			seedApplication(apps[i], task, rec, "pending", 100L * (i + 1),
					Instant.parse("2026-08-0" + (i + 1) + "T10:00:00Z"));
		}

		// 第一页（created_at DESC：apps[2], apps[1]）
		Map<String, Object> page1 = client().get()
				.uri(uri -> uri.path("/api/tasks/my-applications").queryParam("limit", "2").build())
				.header("X-Grassland-Identity", sign(rec, "recommender")).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> data1 = (Map<String, Object>) page1.get("data");
		List<Map<String, Object>> items1 = (List<Map<String, Object>>) data1.get("items");
		assertThat(items1).hasSize(2);
		assertThat(items1.get(0).get("applicationId")).isEqualTo(apps[2]);
		assertThat(items1.get(1).get("applicationId")).isEqualTo(apps[1]);
		assertThat((Boolean) data1.get("hasMore")).isTrue();
		String nextCursor = (String) data1.get("nextCursor");
		assertThat(nextCursor).isNotBlank();

		// 翻页（apps[0]）
		Map<String, Object> page2 = client().get()
				.uri(uri -> uri.path("/api/tasks/my-applications").queryParam("limit", "2")
						.queryParam("cursor", nextCursor).build())
				.header("X-Grassland-Identity", sign(rec, "recommender")).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		Map<String, Object> data2 = (Map<String, Object>) page2.get("data");
		List<Map<String, Object>> items2 = (List<Map<String, Object>>) data2.get("items");
		assertThat(items2).hasSize(1);
		assertThat(items2.get(0).get("applicationId")).isEqualTo(apps[0]);
		assertThat((Boolean) data2.get("hasMore")).isFalse();
	}

	@Test
	void filtersByStatus() {
		String rec = UUID.randomUUID().toString();
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String taskA = UUID.randomUUID().toString();
		String taskB = UUID.randomUUID().toString();
		seedTask(taskA, merchant, org, "已接受任务", "published", 500L);
		seedTask(taskB, merchant, org, "待审任务", "published", 300L);
		String appAccepted = UUID.randomUUID().toString();
		String appPending = UUID.randomUUID().toString();
		seedApplication(appAccepted, taskA, rec, "accepted", 500L, Instant.parse("2026-08-01T10:00:00Z"));
		seedApplication(appPending, taskB, rec, "pending", 300L, Instant.parse("2026-08-02T10:00:00Z"));

		client().get().uri(uri -> uri.path("/api/tasks/my-applications").queryParam("status", "accepted").build())
				.header("X-Grassland-Identity", sign(rec, "recommender")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.items.length()").isEqualTo(1).jsonPath("$.data.items[0].applicationId")
				.isEqualTo(appAccepted);

		// 非法状态 → 400
		client().get().uri(uri -> uri.path("/api/tasks/my-applications").queryParam("status", "not_a_status").build())
				.header("X-Grassland-Identity", sign(rec, "recommender")).exchange().expectStatus().isBadRequest();
	}

	/** 任务书 #77 卡 D：status 逗号多值、settled 布尔过滤、itemBody 新列 platform/storeName/city。 */
	@Test
	void filtersByMultiStatusSettledAndCarriesStoreColumns() {
		String rec = UUID.randomUUID().toString();
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String store = UUID.randomUUID().toString();
		String taskPending = UUID.randomUUID().toString();
		String taskReserving = UUID.randomUUID().toString();
		String taskAccepted = UUID.randomUUID().toString();
		String taskSettled = UUID.randomUUID().toString();
		// platform 落 douyin / store_id 落 store，供新列断言
		seedTaskWithPlatform(taskPending, merchant, org, "待处理任务", "published", 100L, "douyin", store);
		seedTask(taskReserving, merchant, org, "预留任务", "published", 200L);
		seedTask(taskAccepted, merchant, org, "履约任务", "published", 300L);
		seedTask(taskSettled, merchant, org, "已结算任务", "published", 400L);
		String appPending = UUID.randomUUID().toString();
		String appReserving = UUID.randomUUID().toString();
		String appAccepted = UUID.randomUUID().toString();
		String appSettled = UUID.randomUUID().toString();
		seedApplication(appPending, taskPending, rec, "pending", 100L, Instant.parse("2026-08-01T10:00:00Z"));
		seedApplication(appReserving, taskReserving, rec, "reserving", 200L, Instant.parse("2026-08-02T10:00:00Z"));
		seedApplication(appAccepted, taskAccepted, rec, "accepted", 300L, Instant.parse("2026-08-03T10:00:00Z"));
		seedApplication(appSettled, taskSettled, rec, "accepted", 400L, Instant.parse("2026-08-04T10:00:00Z"));
		seedSettled(appSettled, Instant.parse("2026-08-06T10:00:00Z"));

		// 多值：pending,reserving 合并（互斥单值时代的 OR 语义）
		client().get()
				.uri(uri -> uri.path("/api/tasks/my-applications").queryParam("status", "pending,reserving").build())
				.header("X-Grassland-Identity", sign(rec, "recommender")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.items.length()").isEqualTo(2).jsonPath("$.data.items[0].applicationId")
				.isEqualTo(appReserving).jsonPath("$.data.items[1].applicationId").isEqualTo(appPending);

		// 多值含非法值 → 400（任一非法即拒）
		client().get()
				.uri(uri -> uri.path("/api/tasks/my-applications").queryParam("status", "pending,not_a_status").build())
				.header("X-Grassland-Identity", sign(rec, "recommender")).exchange().expectStatus().isBadRequest();

		// settled=true =「完成」口径（settledAt 非空）
		client().get().uri(uri -> uri.path("/api/tasks/my-applications").queryParam("settled", "true").build())
				.header("X-Grassland-Identity", sign(rec, "recommender")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.items.length()").isEqualTo(1).jsonPath("$.data.items[0].applicationId")
				.isEqualTo(appSettled);

		// settled=false = 未结算（含 pending/reserving/accepted 在途）
		client().get().uri(uri -> uri.path("/api/tasks/my-applications").queryParam("settled", "false").build())
				.header("X-Grassland-Identity", sign(rec, "recommender")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.items.length()").isEqualTo(3);

		// 报名成功口径：accepted + settled=false（settling 在途不算完成）
		client().get()
				.uri(uri -> uri.path("/api/tasks/my-applications").queryParam("status", "accepted")
						.queryParam("settled", "false").build())
				.header("X-Grassland-Identity", sign(rec, "recommender")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.items.length()").isEqualTo(1).jsonPath("$.data.items[0].applicationId")
				.isEqualTo(appAccepted);

		// 新列：platform 来自 task；storeName/city 经 identity 批量增强
		when(storeAuthorization.publicProfiles(any())).thenReturn(Mono.just(
				List.of(new com.grassland.marketplace.security.IdentityStoreAuthorizationClient.StorePublicProfile(
						store, "云朵火锅店", "{\"city\":\"上海\",\"address\":\"南京西路 1 号\"}", null, null, null, List.of(),
						List.of(), null, null, null, List.of(), null, List.of(), List.of(), List.of()))));
		client().get().uri(uri -> uri.path("/api/tasks/my-applications").queryParam("status", "pending").build())
				.header("X-Grassland-Identity", sign(rec, "recommender")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.items[0].platform").isEqualTo("douyin").jsonPath("$.data.items[0].storeName")
				.isEqualTo("云朵火锅店").jsonPath("$.data.items[0].city").isEqualTo("上海");
	}

	/** self-scoped + 身份闸门：未登录 401，非推荐官 403，商家看自己（无报名）空列表。 */
	@Test
	void guardAndSelfScope() {
		String rec = UUID.randomUUID().toString();
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = UUID.randomUUID().toString();
		seedTask(task, merchant, org, "任务", "published", 100L);
		seedApplication(UUID.randomUUID().toString(), task, rec, "pending", 100L,
				Instant.parse("2026-08-01T10:00:00Z"));

		// 未登录
		client().get().uri("/api/tasks/my-applications").exchange().expectStatus().isUnauthorized();
		// 商家身份（非推荐官）→ 403
		client().get().uri("/api/tasks/my-applications")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish")).exchange()
				.expectStatus().isForbidden();
		// 另一个推荐官看不到 rec 的报名
		client().get().uri("/api/tasks/my-applications")
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.items.length()").isEqualTo(0);
	}

	// ---------- helpers ----------

	private void seedTask(String taskId, String owner, String org, String title, String status, Long bountyCents) {
		var spec = db.sql("""
				INSERT INTO task(id, owner_account_id, organization_id, title, status, bounty_cents)
				VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), :title, :status, :bounty)
				""").bind("id", taskId).bind("owner", owner).bind("org", org).bind("title", title).bind("status",
				status);
		spec = bountyCents == null ? spec.bindNull("bounty", Long.class) : spec.bind("bounty", bountyCents);
		spec.then().block();
	}

	/** 带 platform/store_id 的种子（#77 卡 D 新列断言用）。 */
	private void seedTaskWithPlatform(String taskId, String owner, String org, String title, String status,
			Long bountyCents, String platform, String storeId) {
		db.sql("""
				INSERT INTO task(id, owner_account_id, organization_id, title, status, bounty_cents, platform, store_id)
				VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), CAST(:org AS uuid), :title, :status, :bounty,
				    :platform, CAST(:store AS uuid))
				""").bind("id", taskId).bind("owner", owner).bind("org", org).bind("title", title)
				.bind("status", status).bind("bounty", bountyCents).bind("platform", platform).bind("store", storeId)
				.then().block();
	}

	private void seedApplication(String appId, String taskId, String rec, String status, long bountyCents,
			Instant createdAt) {
		// accepted/reserving/refunded 触发
		// ck_application_reputation_snapshot_required：须整份声誉快照。
		boolean needsSnapshot = List.of("accepted", "reserving", "refunded").contains(status);
		String sql = needsSnapshot
				? """
						INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents,
						    reputation_level_at_accept, reputation_policy_version_at_accept,
						    settlement_delay_days_at_accept, commission_bonus_bps_at_accept, premium_support_at_accept,
						    created_at, updated_at)
						VALUES (CAST(:id AS uuid), CAST(:task AS uuid), CAST(:rec AS uuid), :status, :bounty,
						    3, 1, 0, 0, false, :ts, :ts)
						"""
				: """
						INSERT INTO task_application(id, task_id, recommender_account_id, status, bounty_cents, created_at, updated_at)
						VALUES (CAST(:id AS uuid), CAST(:task AS uuid), CAST(:rec AS uuid), :status, :bounty, :ts, :ts)
						""";
		db.sql(sql).bind("id", appId).bind("task", taskId).bind("rec", rec).bind("status", status)
				.bind("bounty", bountyCents).bind("ts", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)).then()
				.block();
	}

	/**
	 * 落一条 EngagementSettled outbox（aggregate_id = applicationId），供 settledAt 读取。
	 */
	private void seedSettled(String appId, Instant at) {
		db.sql("""
				INSERT INTO marketplace_outbox(event_id, event_type, aggregate_type, aggregate_id, payload, created_at)
				VALUES (:eventId, 'EngagementSettled', 'TaskApplication', :appId, CAST(:payload AS json), :ts)
				""").bind("eventId", UUID.randomUUID().toString()).bind("appId", appId).bind("payload", "{}")
				.bind("ts", OffsetDateTime.ofInstant(at, ZoneOffset.UTC)).then().block();
	}
}
