package com.grassland.marketplace.reputation;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * 任务书 #98 C98-04：商家信用派生、展示与软排序。
 *
 * <p>
 * TC98-015 指标与标签可查且带口径版本；TC98-016 样本不足不展示标签（中性态）；TC98-017 任务详情 内嵌信用摘要；TC98-018
 * 软排序稳定（同一 created_at 组内信用高在前、异组顺序不动）；TC98-019 无硬 门槛/无自动惩罚（「关注」商家任务照常展示且可报名）。
 */
class MerchantCreditIT extends MarketplaceItSupport {

	@Test
	void tc98_015MetricsAndLabelWithPolicyVersion() {
		String org = UUID.randomUUID().toString();
		String merchant = UUID.randomUUID().toString();
		// 合作样本充足（10 accepted），零失信事实 → 良好；四指标与口径版本可查。
		String taskId = createTask(merchant, org, 20);
		for (int i = 0; i < 10; i++) {
			acceptApplication(UUID.randomUUID().toString(), merchant, org, taskId);
		}

		client().get().uri("/api/merchants/" + org + "/credit")
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.label").isEqualTo("良好")
				.jsonPath("$.data.insufficientSamples").isEqualTo(false).jsonPath("$.data.sampleCount").isEqualTo(10)
				.jsonPath("$.data.policyVersion").isEqualTo("merchant_credit_v1")
				.jsonPath("$.data.metrics.cancelRate.rateBps").isEqualTo(0)
				.jsonPath("$.data.metrics.confirmTimeoutRate.denominator").isEqualTo(0)
				.jsonPath("$.data.metrics.benefitDefaultRate.numerator").isEqualTo(0)
				.jsonPath("$.data.metrics.disputeLossRate.rateBps").isEqualTo(0)
				.jsonPath("$.data.thresholds.cancelRate.watchBps").isEqualTo(2000);

		// 发布后取消 3/4（75% ≥ watch 20%）→ 关注（SQL 置位模拟商家取消已落账）。
		for (int i = 0; i < 3; i++) {
			String cancelledId = createTask(merchant, org, 5);
			db.sql("UPDATE task SET status = 'cancelled', cancelled_at = now() WHERE id = CAST(:id AS uuid)")
					.bind("id", cancelledId).then().block();
		}
		client().get().uri("/api/merchants/" + org + "/credit")
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), null)).exchange().expectStatus()
				.isOk().expectBody().jsonPath("$.data.label").isEqualTo("关注")
				.jsonPath("$.data.metrics.cancelRate.rateBps").isEqualTo(7500);
	}

	@Test
	void tc98_016InsufficientSamplesShowsNoLabel() {
		String org = UUID.randomUUID().toString();
		String merchant = UUID.randomUUID().toString();
		String taskId = createTask(merchant, org, 5);
		for (int i = 0; i < 3; i++) {
			acceptApplication(UUID.randomUUID().toString(), merchant, org, taskId);
		}
		// 合作数 3 < 默认 10：不展示标签（label null + insufficientSamples true），不参与分档。
		client().get().uri("/api/merchants/" + org + "/credit")
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), null)).exchange().expectStatus()
				.isOk().expectBody().jsonPath("$.data.label").value(v -> assertThat((Object) v).isNull())
				.jsonPath("$.data.insufficientSamples").isEqualTo(true).jsonPath("$.data.sampleCount").isEqualTo(3);
	}

	@Test
	void tc98_017TaskDetailEmbedsCreditSummary() {
		String org = UUID.randomUUID().toString();
		String merchant = UUID.randomUUID().toString();
		String taskId = createTask(merchant, org, 5);
		acceptApplication(UUID.randomUUID().toString(), merchant, org, taskId);

		client().get().uri("/api/tasks/" + taskId)
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.merchantCredit.insufficientSamples")
				.isEqualTo(true).jsonPath("$.data.merchantCredit.policyVersion").isEqualTo("merchant_credit_v1");
	}

	@Test
	void tc98_018SoftSortWithinSameCreatedAtOnly() {
		String watchOrg = UUID.randomUUID().toString();
		String goodOrg = UUID.randomUUID().toString();
		String watchMerchant = UUID.randomUUID().toString();
		String goodMerchant = UUID.randomUUID().toString();
		// q 收敛 feed 作用域：类内共享库累积的其他用例任务不挤占 limit，断言不依赖方法执行顺序。
		String marker = "tc98018-" + UUID.randomUUID();

		// watch 商家：10 合作 + 3/10 取消（30%）；good 商家：10 合作零取消。
		String watchTask = createTask(watchMerchant, watchOrg, 20, marker);
		String goodTask = createTask(goodMerchant, goodOrg, 20, marker);
		for (int i = 0; i < 10; i++) {
			acceptApplication(UUID.randomUUID().toString(), watchMerchant, watchOrg, watchTask);
			acceptApplication(UUID.randomUUID().toString(), goodMerchant, goodOrg, goodTask);
		}
		for (int i = 0; i < 3; i++) {
			String cancelledId = createTask(watchMerchant, watchOrg, 5);
			db.sql("UPDATE task SET status = 'cancelled', cancelled_at = now() WHERE id = CAST(:id AS uuid)")
					.bind("id", cancelledId).then().block();
		}

		// 同一时刻发布的两任务：同分组 → good 在前；更早一条 watch 商家任务保持其后（异组不动）。
		String olderTask = createTask(watchMerchant, watchOrg, 5, marker);
		db.sql("UPDATE task SET created_at = now() - interval '2 hours' WHERE id = CAST(:id AS uuid)")
				.bind("id", olderTask).then().block();
		db.sql("UPDATE task SET created_at = date_trunc('second', now()) WHERE id IN (CAST(:a AS uuid), CAST(:b AS uuid))")
				.bind("a", watchTask).bind("b", goodTask).then().block();

		String viewer = sign(UUID.randomUUID().toString(), "recommender");
		@SuppressWarnings("unchecked")
		Map<String, Object> feed = (Map<String, Object>) client().get().uri("/api/tasks/feed?q=" + marker + "&limit=10")
				.header("X-Grassland-Identity", viewer).exchange().expectStatus().isOk().expectBody(Map.class)
				.returnResult().getResponseBody().get("data");
		List<String> order = ((List<Map<String, Object>>) feed.get("items")).stream()
				.map(item -> String.valueOf(item.get("id"))).toList();
		int goodIdx = order.indexOf(goodTask);
		int watchIdx = order.indexOf(watchTask);
		int olderIdx = order.indexOf(olderTask);
		assertThat(goodIdx).isGreaterThanOrEqualTo(0);
		assertThat(watchIdx).isGreaterThanOrEqualTo(0);
		assertThat(goodIdx).isLessThan(watchIdx);
		assertThat(olderIdx).isGreaterThan(Math.max(goodIdx, watchIdx));

		// 内嵌摘要随 feed 下发（软排序无新 UI，数据在）。
		client().get().uri("/api/tasks/feed?q=" + marker + "&limit=10").header("X-Grassland-Identity", viewer)
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.items[0].merchantCredit.policyVersion")
				.isEqualTo("merchant_credit_v1");
	}

	@Test
	void tc98_019NoHardGateWatchMerchantTaskStillVisibleAndApplicable() {
		String org = UUID.randomUUID().toString();
		String merchant = UUID.randomUUID().toString();
		String taskId = createTask(merchant, org, 20);
		for (int i = 0; i < 10; i++) {
			acceptApplication(UUID.randomUUID().toString(), merchant, org, taskId);
		}
		for (int i = 0; i < 3; i++) {
			String cancelledId = createTask(merchant, org, 5);
			db.sql("UPDATE task SET status = 'cancelled', cancelled_at = now() WHERE id = CAST(:id AS uuid)")
					.bind("id", cancelledId).then().block();
		}

		// 「关注」商家：任务照常在 feed 展示，报名照常受理——无硬门槛、无自动惩罚动作。
		String recommender = UUID.randomUUID().toString();
		client().get().uri("/api/tasks/feed?limit=10").header("X-Grassland-Identity", sign(recommender, "recommender"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.items[?(@.id=='" + taskId + "')].id")
				.isEqualTo(taskId);
		client().post().uri("/api/tasks/" + taskId + "/applications")
				.header("X-Grassland-Identity", sign(recommender, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "关注商家也可正常报名")).exchange()
				.expectStatus().isCreated();
	}

	// ---------- 造数 ----------

	private String createTask(String merchant, String org, int maxSlots) {
		return createTask(merchant, org, maxSlots, null);
	}

	private String createTask(String merchant, String org, int maxSlots, String titleMarker) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("organizationId", org);
		body.put("title", "图文种草-" + (titleMarker == null ? UUID.randomUUID() : titleMarker));
		body.put("platform", "xiaohongshu");
		body.put("contentForm", "image");
		body.put("storeId", UUID.randomUUID().toString());
		body.put("applicationDeadline", java.time.Instant.now().plusSeconds(3600).toString());
		body.put("maxSlots", maxSlots);
		// 零赏金（非资金型）：accept 直连 200 accepted——避免资金型 202 reserving 在途态（IT 造数惯例）。
		@SuppressWarnings("unchecked")
		Map<String, Object> task = (Map<String, Object>) client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody().get("data");
		// immediate-create 自动送审：审核通过后才可报名；审核为采样路径——被采样跳过（已直接
		// published）的任务不 approve（409 不在待审核状态）。送审递增 version，approve 乐观锁取库内现行版本。
		var statusAndVersion = db.sql("SELECT status, version FROM task WHERE id = CAST(:id AS uuid)")
				.bind("id", (String) task.get("id"))
				.map(row -> Map.entry(row.get("status", String.class), row.get("version", Integer.class))).one()
				.block();
		if ("pending_review".equals(statusAndVersion.getKey())) {
			client().post().uri("/api/admin/tasks/" + task.get("id") + "/review/approve")
					.header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "content_reviewer"))
					.contentType(MediaType.APPLICATION_JSON)
					.bodyValue(Map.of("expectedVersion", statusAndVersion.getValue())).exchange().expectStatus().isOk();
		}
		return (String) task.get("id");
	}

	private void acceptApplication(String recommender, String merchant, String org, String taskId) {
		@SuppressWarnings("unchecked")
		String appId = String
				.valueOf(((Map<String, Object>) client().post().uri("/api/tasks/" + taskId + "/applications")
						.header("X-Grassland-Identity", sign(recommender, "recommender"))
						.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "带客")).exchange()
						.expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("data"))
						.get("id"));
		client().post().uri("/api/tasks/" + taskId + "/applications/" + appId + "/accept")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish")).exchange()
				.expectStatus().isOk();
	}
}
