package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.saga.ReserveResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 任务书 #27 批量接受/拒绝 IT。锁定逐项独立、部分成功语义： 混合结果（已处理/不存在/名额满按顺序）、1–50 上限、非 owner 403、
 * Idempotency-Key 重放不重复接受、资金型批量项 reserving + 真 Saga 激活， 以及
 * {@code findAutoAcceptEnabled} 的 SQL 资格谓词（published/截止/开关）。
 */
class BatchApplicationControllerIT extends MarketplaceItSupport {

	private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

	/** finance 出站边界替身：真 Saga 跑通，仅 finance HTTP mock（镜像 ApplicationControllerIT）。 */
	@MockitoSpyBean
	private FinanceEscrowClient financeClient;

	@Autowired
	private TaskRepository taskRepo;

	// ---------- batch-accept ----------

	@Test
	void batchAcceptMixedOutcomesPerItemIndependent() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = publishTask(merchant, org, null);
		String appA = apply(UUID.randomUUID().toString(), task);
		String appB = apply(UUID.randomUUID().toString(), task);
		reject(merchant, task, appB); // 预置：已处理

		List<Map<String, Object>> results = batchAccept(merchant, org, task, List.of(appA, appB, ZERO_UUID), null);

		assertThat(results).hasSize(3);
		assertThat(results.get(0)).containsEntry("applicationId", appA).containsEntry("outcome", "accepted");
		// #26 D12：无上限任务接受成功也不关闭 → taskClosed=false
		assertThat(results.get(0)).containsEntry("taskClosed", false);
		assertThat(results.get(1)).containsEntry("applicationId", appB).containsEntry("outcome", "failed")
				.containsEntry("reason", "该报名已处理");
		assertThat(results.get(2)).containsEntry("applicationId", ZERO_UUID).containsEntry("outcome", "failed")
				.containsEntry("reason", "报名不存在");

		assertThat(appStatus(appA)).isEqualTo("accepted");
		assertThat(appStatus(appB)).isEqualTo("rejected");
		assertThat(outboxCount("ApplicationAccepted", task)).isEqualTo(1);
	}

	@Test
	void batchAcceptClaimsSlotsInRequestOrder() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = publishTask(merchant, org, 1); // 名额=1
		String first = apply(UUID.randomUUID().toString(), task);
		String second = apply(UUID.randomUUID().toString(), task);

		List<Map<String, Object>> results = batchAccept(merchant, org, task, List.of(first, second), null);

		assertThat(results.get(0)).containsEntry("applicationId", first).containsEntry("outcome", "accepted");
		// #26 D12：最后一名额接受成功同事务关闭 → 该项结果带 taskClosed=true；满员失败项 false
		assertThat(results.get(0)).containsEntry("taskClosed", true);
		assertThat(results.get(1)).containsEntry("applicationId", second).containsEntry("outcome", "failed")
				.containsEntry("reason", "名额已满");
		assertThat(results.get(1)).containsEntry("taskClosed", false);
		assertThat(acceptedCount(task)).isEqualTo(1);
	}

	@Test
	void batchAcceptMonetaryItemReturnsReservingAndSagaActivates() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = publishTaskBounty(merchant, org, null, 500L);
		String app = apply(UUID.randomUUID().toString(), task);
		when(financeClient.reserve(eq(org), eq(app), eq(500L), anyString()))
				.thenReturn(Mono.just(ReserveResult.reserved(500L)));

		List<Map<String, Object>> results = batchAccept(merchant, org, task, List.of(app), null);

		assertThat(results.get(0)).containsEntry("applicationId", app).containsEntry("outcome", "reserving");
		assertThat((String) results.get(0).get("commandId")).isNotBlank();

		awaitReservation(merchant, task, app, "accepted");
		assertThat(appStatus(app)).isEqualTo("accepted");
	}

	@Test
	void batchAcceptReplaysIdempotencyKeyWithoutDoubleAcceptance() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = publishTask(merchant, org, 1);
		String app = apply(UUID.randomUUID().toString(), task);
		String key = "batch-" + UUID.randomUUID();

		List<Map<String, Object>> first = batchAccept(merchant, org, task, List.of(app), key);
		List<Map<String, Object>> replay = batchAccept(merchant, org, task, List.of(app), key);

		assertThat(first.get(0)).containsEntry("outcome", "accepted");
		assertThat(replay.get(0)).containsEntry("outcome", "accepted");
		assertThat(acceptedCount(task)).isEqualTo(1);
		assertThat(outboxCount("ApplicationAccepted", task)).isEqualTo(1);
	}

	@Test
	void batchAcceptNonOwnerForbidden() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = publishTask(merchant, org, null);
		String app = apply(UUID.randomUUID().toString(), task);

		client().post().uri("/api/tasks/" + task + "/applications/batch-accept")
				.header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("applicationIds", List.of(app))).exchange()
				.expectStatus().isForbidden();
	}

	@Test
	void batchAcceptValidatesSizeBounds() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = publishTask(merchant, org, null);

		client().post().uri("/api/tasks/" + task + "/applications/batch-accept")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("applicationIds", List.of())).exchange()
				.expectStatus().isBadRequest();

		List<String> tooMany = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			tooMany.add(UUID.randomUUID().toString());
		}
		client().post().uri("/api/tasks/" + task + "/applications/batch-accept")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("applicationIds", tooMany)).exchange()
				.expectStatus().isBadRequest();
	}

	// ---------- batch-reject ----------

	@Test
	void batchRejectMixedOutcomes() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String task = publishTask(merchant, org, null);
		String appA = apply(UUID.randomUUID().toString(), task);
		String appB = apply(UUID.randomUUID().toString(), task);
		accept(merchant, task, appB); // 预置：已接受

		List<Map<String, Object>> results = batchReject(merchant, org, task, List.of(appA, appB, ZERO_UUID));

		assertThat(results).hasSize(3);
		assertThat(results.get(0)).containsEntry("applicationId", appA).containsEntry("outcome", "rejected");
		assertThat(results.get(1)).containsEntry("applicationId", appB).containsEntry("outcome", "failed")
				.containsEntry("reason", "该报名已处理");
		assertThat(results.get(2)).containsEntry("applicationId", ZERO_UUID).containsEntry("outcome", "failed")
				.containsEntry("reason", "报名不存在");

		assertThat(appStatus(appA)).isEqualTo("rejected");
		assertThat(appStatus(appB)).isEqualTo("accepted");
		assertThat(outboxCount("ApplicationRejected", task)).isEqualTo(1);
	}

	// ---------- findAutoAcceptEnabled SQL 资格谓词 ----------

	@Test
	void findAutoAcceptEnabledOnlyScansEligibleTasks() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();

		String eligible = publishTask(merchant, org, null);
		setAutoAccept(eligible, 4);

		String deadlinePassed = publishTaskWithDeadline(merchant, org, Instant.now().minusSeconds(60));
		setAutoAccept(deadlinePassed, 4);

		String closed = publishTask(merchant, org, null);
		setAutoAccept(closed, 4);
		db.sql("UPDATE task SET status = 'closed' WHERE id = CAST(:id AS uuid)").bind("id", closed).then().block();

		String noThreshold = publishTask(merchant, org, null); // 未配置门槛：不扫描

		List<String> scanned = taskRepo.findAutoAcceptEnabled(200).map(Task::id).collectList().block();
		assertThat(scanned).contains(eligible).doesNotContain(deadlinePassed, closed, noThreshold);
	}

	// ---------- helpers（镜像 ApplicationControllerIT 同名助手） ----------

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> batchAccept(String merchant, String org, String task, List<String> applicationIds,
			String idempotencyKey) {
		var spec = client().post().uri("/api/tasks/" + task + "/applications/batch-accept")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("applicationIds", applicationIds));
		if (idempotencyKey != null) {
			spec = spec.header("Idempotency-Key", idempotencyKey);
		}
		Map<String, Object> resp = spec.exchange().expectStatus().isOk().expectBody(Map.class).returnResult()
				.getResponseBody();
		return (List<Map<String, Object>>) ((Map<String, Object>) resp.get("data")).get("results");
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> batchReject(String merchant, String org, String task,
			List<String> applicationIds) {
		Map<String, Object> resp = client().post().uri("/api/tasks/" + task + "/applications/batch-reject")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("applicationIds", applicationIds)).exchange()
				.expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
		return (List<Map<String, Object>>) ((Map<String, Object>) resp.get("data")).get("results");
	}

	@SuppressWarnings("unchecked")
	private String apply(String recommender, String task) {
		Map<String, Object> resp = client().post().uri("/api/tasks/" + task + "/applications")
				.header("X-Grassland-Identity", sign(recommender, "recommender"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "申请")).exchange().expectStatus()
				.isCreated().expectBody(Map.class).returnResult().getResponseBody();
		return (String) ((Map<String, Object>) resp.get("data")).get("id");
	}

	private void accept(String merchant, String task, String app) {
		client().post().uri("/api/tasks/" + task + "/applications/" + app + "/accept")
				.header("X-Grassland-Identity", sign(merchant, "merchant")).exchange().expectStatus().isOk();
	}

	private void reject(String merchant, String task, String app) {
		client().post().uri("/api/tasks/" + task + "/applications/" + app + "/reject")
				.header("X-Grassland-Identity", sign(merchant, "merchant")).exchange().expectStatus().isOk();
	}

	@SuppressWarnings("unchecked")
	private String publishTask(String merchant, String org, Integer maxSlots) {
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("organizationId", org);
		b.put("title", "批量任务");
		b.put("platform", "xiaohongshu"); // 任务书 #77 卡 B：三字段必填造数
		b.put("storeId", UUID.randomUUID().toString());
		b.put("applicationDeadline", java.time.Instant.now().plusSeconds(3600).toString());
		if (maxSlots != null) {
			b.put("maxSlots", maxSlots);
		}
		Map<String, Object> resp = client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(b).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
		markPublished(taskId);
		return stripStoreScope(taskId);
	}

	@SuppressWarnings("unchecked")
	private String publishTaskBounty(String merchant, String org, Integer maxSlots, Long bountyCents) {
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("organizationId", org);
		b.put("title", "批量赏金任务");
		b.put("platform", "xiaohongshu"); // 任务书 #77 卡 B：三字段必填造数
		b.put("storeId", UUID.randomUUID().toString());
		b.put("applicationDeadline", java.time.Instant.now().plusSeconds(3600).toString());
		if (maxSlots != null) {
			b.put("maxSlots", maxSlots);
		}
		if (bountyCents != null) {
			b.put("bountyCents", bountyCents);
		}
		Map<String, Object> resp = client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "finance_transaction"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(b).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
		markPublished(taskId);
		return stripStoreScope(taskId);
	}

	@SuppressWarnings("unchecked")
	private String publishTaskWithDeadline(String merchant, String org, Instant applicationDeadline) {
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("organizationId", org);
		b.put("title", "截止任务");
		b.put("platform", "xiaohongshu"); // 任务书 #77 卡 B：三字段必填造数
		b.put("storeId", UUID.randomUUID().toString());
		b.put("applicationDeadline", java.time.Instant.now().plusSeconds(3600).toString());
		b.put("applicationDeadline", Instant.now().plusSeconds(3600).toString());
		Map<String, Object> resp = client().post().uri("/api/tasks")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(b).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
		db.sql("UPDATE task SET application_deadline = :dl WHERE id = CAST(:id AS uuid)")
				.bind("dl", applicationDeadline.atOffset(java.time.ZoneOffset.UTC)).bind("id", taskId).then().block();
		markPublished(taskId);
		return stripStoreScope(taskId);
	}

	private void setAutoAccept(String taskId, Integer level) {
		db.sql("UPDATE task SET auto_accept_min_level = :lv WHERE id = CAST(:id AS uuid)").bind("lv", level)
				.bind("id", taskId).then().block();
	}

	private void markPublished(String taskId) {
		db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now()) "
				+ "WHERE id = CAST(:id AS uuid)").bind("id", taskId).then().block();
	}

	private void awaitReservation(String merchant, String task, String app, String expected) {
		long deadline = System.currentTimeMillis() + 30_000L;
		String status = null;
		while (System.currentTimeMillis() < deadline) {
			status = pollReservationStatus(merchant, task, app);
			if (expected.equals(status)) {
				return;
			}
			try {
				Thread.sleep(100L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new AssertionError("reservation did not reach " + expected + " (last=" + status + ")");
	}

	@SuppressWarnings("unchecked")
	private String pollReservationStatus(String merchant, String task, String app) {
		Map<String, Object> resp = client().get().uri("/api/tasks/" + task + "/applications/" + app + "/reservation")
				.header("X-Grassland-Identity", sign(merchant, "merchant")).exchange().expectStatus().isOk()
				.expectBody(Map.class).returnResult().getResponseBody();
		return (String) ((Map<String, Object>) resp.get("data")).get("status");
	}

	private String appStatus(String app) {
		return db.sql("SELECT status FROM task_application WHERE id = CAST(:id AS uuid)").bind("id", app)
				.map(r -> r.get("status", String.class)).one().block();
	}

	private long outboxCount(String eventType, String taskId) {
		return db
				.sql("SELECT COUNT(*)::int AS c FROM marketplace_outbox"
						+ " WHERE event_type = :et AND payload->>'taskId' = :tid")
				.bind("et", eventType).bind("tid", taskId).map(r -> r.get("c", Integer.class)).one().block()
				.longValue();
	}

	private int acceptedCount(String taskId) {
		return db
				.sql("SELECT COUNT(*)::int AS c FROM task_application"
						+ " WHERE task_id = CAST(:tid AS uuid) AND status = 'accepted'")
				.bind("tid", taskId).map(r -> r.get("c", Integer.class)).one().block();
	}
}
