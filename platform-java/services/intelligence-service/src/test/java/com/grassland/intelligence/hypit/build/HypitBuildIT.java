package com.grassland.intelligence.hypit.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.build.HypitBuildRepository.BuildRow;
import com.grassland.intelligence.hypit.build.HypitBuildService.SubmitView;
import com.grassland.intelligence.hypit.build.HypitPlanRepository.PlanRow;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * 持久 Build Java 面语义（任务书 #107-2 C107-09 / TC107-09-01～04 的持久层部分）。
 *
 * <p>真 PostgreSQL：提交幂等（同 requestId 回原 Build/job，不同载荷 409）、grant 门禁
 * （远程 page 计费计划必须携带有效授权）、前进式 lifecycle/outcome（终态不被迟到观察翻转、
 * 终帧事件恰好一次）、未达引擎的取消幂等（submission_incomplete+cancelled 零副作用重复）、
 * 引擎不可达时 GET 回落已存事实、owner 隔离，以及 observer 的过期租约重排与 attempt 预算。
 * 引擎侧语义（固定 engineBuildId、容量闸、事件 delta）由 B/tests/runtime 覆盖，本 IT 不桩引擎。
 */
@TestPropertySource(properties = { "hypit.enabled=true" })
class HypitBuildIT extends IntelligenceItSupport {

	private static final String OWNER = "dddddddd-0000-4000-8000-00000000000d";
	private static final String OTHER = "eeeeeeee-0000-4000-8000-00000000000e";
	private static final String PROFILE_HASH =
			com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge.sha256Hex("{\"profile\":null}");

	@Autowired
	HypitBuildService buildService;

	@Autowired
	HypitBuildObserver observer;

	@Autowired
	HypitBuildRepository builds;

	@Autowired
	HypitPlanRepository plans;

	@Autowired
	DatabaseClient db;

	private UUID projectId;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> "it-hypit-internal-token-0123456789abcdef");
	}

	@BeforeEach
	void seed() {
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'build-it', 'clone', 'ready', 3)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
	}

	/** 共享容器跨类污染防线（105F 教训）：类结束也自清，不留行挡别的类删 plan。 */
	@org.junit.jupiter.api.AfterEach
	void sweep() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_job_event WHERE job_id IN"
				+ " (SELECT id FROM hypit_job WHERE account_id IN (:owner, :other))")
				.bind("owner", OWNER).bind("other", OTHER).then()
				.then(db.sql("DELETE FROM hypit_job WHERE account_id IN (:owner, :other)")
						.bind("owner", OWNER).bind("other", OTHER).then())
				.then(db.sql("DELETE FROM hypit_output WHERE build_id IN (SELECT b.id FROM hypit_build b"
						+ " JOIN hypit_project p ON p.id = b.project_id WHERE p.account_id IN (:owner, :other))")
						.bind("owner", OWNER).bind("other", OTHER).then())
				.then(db.sql("DELETE FROM hypit_build WHERE project_id IN"
						+ " (SELECT id FROM hypit_project WHERE account_id IN (:owner, :other))")
						.bind("owner", OWNER).bind("other", OTHER).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id IN (:owner, :other)")
						.bind("owner", OWNER).bind("other", OTHER).then())
				.then(db.sql("DELETE FROM hypit_execution WHERE grant_id IN"
						+ " (SELECT id FROM hypit_execution_grant WHERE account_id IN (:owner, :other))")
						.bind("owner", OWNER).bind("other", OTHER).then())
				.then(db.sql("DELETE FROM hypit_execution_grant WHERE account_id IN (:owner, :other)")
						.bind("owner", OWNER).bind("other", OTHER).then())
				.then(db.sql("DELETE FROM hypit_pricing_snapshot WHERE plan_id IN (SELECT id FROM hypit_plan"
						+ " WHERE project_id IN (SELECT id FROM hypit_project WHERE account_id IN (:owner, :other)))")
						.bind("owner", OWNER).bind("other", OTHER).then())
				.then(db.sql("DELETE FROM hypit_plan WHERE project_id IN"
						+ " (SELECT id FROM hypit_project WHERE account_id IN (:owner, :other))")
						.bind("owner", OWNER).bind("other", OTHER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id IN (:owner, :other)")
						.bind("owner", OWNER).bind("other", OTHER).then())
				.block(Duration.ofSeconds(20));
	}

	private int planSeq = 0;

	private PlanRow planFor(String planJson) {
		String suffix = String.valueOf(++planSeq);
		String hash = "c".repeat(64 - suffix.length()) + suffix;
		return plans.insertPlan(UUID.randomUUID(), projectId, 3, "main.svrun", hash, PROFILE_HASH, planJson)
				.block(Duration.ofSeconds(10));
	}

	private SubmitView submit(PlanRow plan, UUID requestId, UUID grantId, String title) {
		return buildService.submit(OWNER, projectId, requestId, plan.id(), grantId, title)
				.block(Duration.ofSeconds(20));
	}

	@FunctionalInterface
	private interface FailingCall {

		Object run();
	}

	private static IntelligenceException expectError(FailingCall call) {
		try {
			call.run();
		} catch (IntelligenceException error) {
			return error;
		}
		throw new AssertionError("expected IntelligenceException but call succeeded");
	}

	private List<Map<String, Object>> eventsOf(UUID jobId) {
		return db.sql("SELECT type, payload::text AS payload FROM hypit_job_event WHERE job_id = CAST(:id AS uuid)"
				+ " ORDER BY sequence").bind("id", jobId.toString())
				.map((row, meta) -> Map.<String, Object>of("type", row.get("type", String.class), "payload",
						row.get("payload", String.class)))
				.all().collectList().block(Duration.ofSeconds(10));
	}

	// ---------- 09.1/09.2：公共 Build 先于引擎存在 + 提交幂等 ----------

	@Test
	void submitCreatesBuildBeforeEngineAndIsIdempotentPerRequestId() {
		PlanRow plan = planFor("{\"targets\":[\"final.video\"],\"providers\":[],\"missingCapabilities\":[]}");
		UUID requestId = UUID.randomUUID();
		SubmitView first = submit(plan, requestId, null, "第一支片");
		assertThat(first.replayed()).isFalse();
		BuildRow build = first.build();
		assertThat(build.lifecycle()).isEqualTo("submitting");
		assertThat(build.outcome()).as("提交时 outcome 未定").isNull();
		assertThat(build.engineBuildId()).as("引擎 id 由 worker 观察回填").isNull();
		assertThat(build.revision()).isEqualTo(3);
		assertThat(first.job().kind()).isEqualTo("hypit.build");
		assertThat(first.job().state()).isEqualTo("queued");
		assertThat(buildService.jobFor(build).block(Duration.ofSeconds(10)).id())
				.as("job 与 build 经 command_id 关联").isEqualTo(first.job().id());

		SubmitView replay = submit(plan, requestId, null, "第一支片");
		assertThat(replay.replayed()).isTrue();
		assertThat(replay.build().id()).isEqualTo(build.id());
		assertThat(replay.job().id()).isEqualTo(first.job().id());

		// TC107-09-01：库里只有一个公共 Build（不因重复 HTTP 产生新 engine 提交意图）。
		Long buildsCount = db.sql("SELECT count(*) AS n FROM hypit_build WHERE project_id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((r, m) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(buildsCount).isEqualTo(1);
		assertThat(eventsOf(first.job().id())).extracting(event -> event.get("type")).containsExactly("snapshot");
	}

	@Test
	void sameRequestIdWithDifferentPayloadConflicts() {
		PlanRow plan = planFor("{\"targets\":[\"final.video\"],\"providers\":[],\"missingCapabilities\":[]}");
		UUID requestId = UUID.randomUUID();
		submit(plan, requestId, null, "甲");
		IntelligenceException error = expectError(() -> submit(plan, requestId, null, "乙"));
		assertThat(error.status()).isEqualTo(409);
		assertThat(error.code()).isEqualTo("hypit_idempotency_conflict");
	}

	// ---------- grant 门禁（K12/D-06） ----------

	@Test
	void remotePricedPlanRequiresGrantAndMissingCapabilityIs422() {
		String remotePlan = "{\"targets\":[\"final.video\"],\"providers\":[{\"status\":\"resolved\","
				+ "\"pricing\":{\"kind\":\"page\"}}],\"missingCapabilities\":[]}";
		PlanRow plan = planFor(remotePlan);
		IntelligenceException missing = expectError(() -> submit(plan, UUID.randomUUID(), null, null));
		assertThat(missing.status()).isEqualTo(400);
		assertThat(missing.code()).isEqualTo("hypit_invalid_input");

		IntelligenceException unknown = expectError(() -> submit(plan, UUID.randomUUID(), UUID.randomUUID(), null));
		assertThat(unknown.status()).as("授权不存在按无效输入拒绝").isEqualTo(400);

		PlanRow incapable = planFor("{\"targets\":[],\"providers\":[],\"missingCapabilities\":[\"@hypit/sora@2#video\"]}");
		IntelligenceException unsupported = expectError(() -> submit(incapable, UUID.randomUUID(), null, null));
		assertThat(unsupported.status()).isEqualTo(422);
		assertThat(unsupported.code()).isEqualTo("hypit_unsupported_capability");

		// 全本地计划无需 grant（缺 grant 不报错、带 grant 也不拒绝——本卡不校验多余授权）。
		PlanRow local = planFor("{\"targets\":[\"final.video\"],\"providers\":[],\"missingCapabilities\":[]}");
		assertThat(submit(local, UUID.randomUUID(), null, null).replayed()).isFalse();
	}

	// ---------- 09.4/09.6：前进式状态 + 事件投影 ----------

	private static Map<String, Object> observation(String lifecycle, String outcome, String engineId,
			String... outputNames) {
		Map<String, Object> map = new HashMap<>();
		map.put("found", true);
		map.put("lifecycle", lifecycle);
		if (outcome != null) {
			map.put("outcome", outcome);
		}
		if (engineId != null) {
			map.put("engineBuildId", engineId);
		}
		if (outputNames.length > 0) {
			map.put("outputNames", List.of(outputNames));
		}
		return map;
	}

	@Test
	void observationIsForwardOnlyAndTerminalEventProjectsExactlyOnce() {
		PlanRow plan = planFor("{\"targets\":[\"final.video\"],\"providers\":[],\"missingCapabilities\":[]}");
		SubmitView view = submit(plan, UUID.randomUUID(), null, null);
		UUID jobId = view.job().id();
		BuildRow build = view.build();

		BuildRow active = buildService.applyObservation(build,
				observation("active", null, "bld_it_fixed_1")).block(Duration.ofSeconds(10));
		assertThat(active.lifecycle()).isEqualTo("active");
		assertThat(active.engineBuildId()).isEqualTo("bld_it_fixed_1");

		BuildRow pending = buildService.applyObservation(active,
				observation("result_pending", null, "bld_it_fixed_1", "final.video")).block(Duration.ofSeconds(10));
		assertThat(pending.lifecycle()).isEqualTo("result_pending");

		Map<String, Object> finishedObs = observation("finished", "complete", "bld_it_fixed_1", "final.video");
		finishedObs.put("finishedAt", java.time.Instant.now().toString());
		BuildRow done = buildService.applyObservation(pending, finishedObs).block(Duration.ofSeconds(10));
		assertThat(done.lifecycle()).isEqualTo("finished");
		assertThat(done.outcome()).isEqualTo("complete");
		assertThat(done.finishedAt()).isNotNull();

		JobRow job = buildService.jobFor(done).block(Duration.ofSeconds(10));
		assertThat(job.state()).as("succeeded 只代表观察操作完成").isEqualTo("succeeded");
		List<String> types = eventsOf(jobId).stream().map(event -> (String) event.get("type")).toList();
		assertThat(types).as("snapshot+两帧 progress+一次 terminal").containsExactly("snapshot", "progress",
				"progress", "terminal");

		// TC107-09-04：取消/迟到观察不得翻转终态；重复终帧零事件。
		BuildRow late = buildService.applyObservation(done, observation("submission_incomplete", "cancelled",
				"bld_it_fixed_1")).block(Duration.ofSeconds(10));
		assertThat(late.outcome()).isEqualTo("complete");
		assertThat(late.lifecycle()).isEqualTo("finished");
		BuildRow repeat = buildService.applyObservation(done, observation("finished", "complete", "bld_it_fixed_1",
				"final.video")).block(Duration.ofSeconds(10));
		assertThat(repeat.outcome()).isEqualTo("complete");
		assertThat(eventsOf(jobId)).hasSize(4);

		// 终态后的取消是幂等 no-op（返回现状，不报错也不再写库）。
		BuildRow cancelledLate = buildService.cancel(OWNER, done.id(), UUID.randomUUID(), null)
				.block(Duration.ofSeconds(10));
		assertThat(cancelledLate.outcome()).isEqualTo("complete");
		assertThat(eventsOf(jobId)).hasSize(4);
	}

	@Test
	void failedOutcomeKeepsFinishedLifecycleWithOutputs() {
		PlanRow plan = planFor("{\"targets\":[\"final.video\"],\"providers\":[],\"missingCapabilities\":[]}");
		SubmitView view = submit(plan, UUID.randomUUID(), null, null);
		BuildRow build = view.build();
		BuildRow active = buildService.applyObservation(build,
				observation("active", null, "bld_it_fail_1")).block(Duration.ofSeconds(10));
		BuildRow failed = buildService.applyObservation(active,
				observation("finished", "failed", "bld_it_fail_1", "final.video")).block(Duration.ofSeconds(10));
		assertThat(failed.outcome()).isEqualTo("failed");
		assertThat(failed.engineBuildId()).as("失败的 Build 保留已产出的事实").isEqualTo("bld_it_fail_1");
		JobRow job = buildService.jobFor(failed).block(Duration.ofSeconds(10));
		assertThat(job.state()).isEqualTo("succeeded");
		String terminal = eventsOf(view.job().id()).stream()
				.filter(event -> "terminal".equals(event.get("type"))).findFirst().orElseThrow().get("payload")
				.toString();
		assertThat(terminal).as("终帧详情必须带 Build outcome").contains("\"failed\"");
	}

	// ---------- 09.7：未达引擎的取消幂等 ----------

	@Test
	void cancelBeforeEngineSubmissionIsIdempotentAndIncomplete() {
		PlanRow plan = planFor("{\"targets\":[\"final.video\"],\"providers\":[],\"missingCapabilities\":[]}");
		SubmitView view = submit(plan, UUID.randomUUID(), null, null);
		BuildRow cancelled = buildService.cancel(OWNER, view.build().id(), UUID.randomUUID(), "用户撤回")
				.block(Duration.ofSeconds(10));
		assertThat(cancelled.lifecycle()).isEqualTo("submission_incomplete");
		assertThat(cancelled.outcome()).isEqualTo("cancelled");
		JobRow job = buildService.jobFor(cancelled).block(Duration.ofSeconds(10));
		assertThat(job.state()).isEqualTo("cancelled");
		List<String> types = eventsOf(view.job().id()).stream().map(event -> (String) event.get("type")).toList();
		assertThat(types).containsExactly("snapshot", "terminal");

		BuildRow again = buildService.cancel(OWNER, view.build().id(), UUID.randomUUID(), "再点一次")
				.block(Duration.ofSeconds(10));
		assertThat(again.lifecycle()).isEqualTo("submission_incomplete");
		assertThat(again.outcome()).isEqualTo("cancelled");
		assertThat(eventsOf(view.job().id())).as("重复取消零新事件").hasSize(2);
	}

	// ---------- 09.1/09.3：引擎不可达回落已存事实（页面关闭不等于任务取消） ----------

	@Test
	void convergeFallsBackToStoredFactsWhenEngineUnreachable() {
		PlanRow plan = planFor("{\"targets\":[\"final.video\"],\"providers\":[],\"missingCapabilities\":[]}");
		SubmitView view = submit(plan, UUID.randomUUID(), null, null);
		BuildRow withEngine = buildService.applyObservation(view.build(),
				observation("submitting", null, "bld_it_orphan_1")).block(Duration.ofSeconds(10));
		// sidecar 未部署：GET 刷新不得报错、不得篡改已存事实（提交意图仍可见）。
		BuildRow converged = buildService.ownedBuild(OWNER, withEngine.id()).block(Duration.ofSeconds(20));
		assertThat(converged.lifecycle()).isEqualTo("submitting");
		assertThat(converged.engineBuildId()).isEqualTo("bld_it_orphan_1");
		// 引擎未达时日志如实 503（不伪造「无日志」——可能只是读不到）。
		IntelligenceException logsDown = expectError(
				() -> buildService.logs(OWNER, withEngine.id(), 0, 100).block(Duration.ofSeconds(20)));
		assertThat(logsDown.status()).isEqualTo(503);
		assertThat(logsDown.code()).isEqualTo("hypit_backend_unavailable");
		// 未提交引擎的 Build：日志如实空页（没有引擎工作就没有日志来源）。
		SubmitView fresh = submit(plan, UUID.randomUUID(), null, null);
		Map<String, Object> emptyLogs = buildService.logs(OWNER, fresh.build().id(), 0, 100)
				.block(Duration.ofSeconds(20));
		assertThat(emptyLogs.get("records")).isEqualTo(List.of());
	}

	// ---------- owner 隔离 ----------

	@Test
	void otherOwnerCannotReadListOrCancel() {
		PlanRow plan = planFor("{\"targets\":[\"final.video\"],\"providers\":[],\"missingCapabilities\":[]}");
		SubmitView view = submit(plan, UUID.randomUUID(), null, null);
		assertThatThrownBy(() -> buildService.ownedBuild(OTHER, view.build().id()).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, error -> assertThat(error.status()).isEqualTo(404));
		assertThatThrownBy(() -> buildService.cancel(OTHER, view.build().id(), UUID.randomUUID(), null)
				.block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, error -> assertThat(error.status()).isEqualTo(404));
		assertThatThrownBy(() -> buildService.listOwned(OTHER, projectId, 20).block(Duration.ofSeconds(10)))
				.isInstanceOfSatisfying(IntelligenceException.class, error -> assertThat(error.status()).isEqualTo(404));
		assertThat(buildService.listOwned(OWNER, projectId, 20).block(Duration.ofSeconds(10))).hasSize(1);
	}

	// ---------- 09.8：observer 过期租约重排 + attempt 预算 ----------

	@Test
	void observerRequeuesExpiredLeaseAndFailsAfterAttemptBudget() {
		PlanRow plan = planFor("{\"targets\":[\"final.video\"],\"providers\":[],\"missingCapabilities\":[]}");
		SubmitView view = submit(plan, UUID.randomUUID(), null, null);
		UUID jobId = view.job().id();
		// 直接伪造「崩溃残局」：job 卡 running、租约已过期。
		db.sql("UPDATE hypit_job SET state = 'running', lease_owner = CAST(:owner AS uuid),"
				+ " lease_until = now() - interval '1 hour' WHERE id = CAST(:id AS uuid)")
				.bind("owner", UUID.randomUUID().toString()).bind("id", jobId.toString()).then()
				.block(Duration.ofSeconds(10));

		observer.runOnce().block(Duration.ofSeconds(60));
		JobRow afterOne = readJob(jobId);
		assertThat(afterOne.state()).as("派发失败退回 queued 重试").isIn("queued", "running");
		assertThat(afterOne.attempt()).isGreaterThanOrEqualTo(2);

		for (int i = 0; i < 12 && !"failed".equals(readJob(jobId).state()); i++) {
			observer.runOnce().block(Duration.ofSeconds(60));
		}
		JobRow exhausted = readJob(jobId);
		assertThat(exhausted.state()).as("连续派发失败按预算落 failed 留诊断").isEqualTo("failed");
		assertThat(exhausted.errorCode()).isEqualTo("hypit_backend_unavailable");
		// Build 本体不受影响：提交意图仍在，等 sidecar 恢复后由人工/后续卡处理。
		BuildRow build = builds.findById(view.build().id()).block(Duration.ofSeconds(10));
		assertThat(build.lifecycle()).isEqualTo("submitting");
	}

	private JobRow readJob(UUID jobId) {
		return db.sql("SELECT state, attempt, error_code FROM hypit_job WHERE id = CAST(:id AS uuid)")
				.bind("id", jobId.toString())
				.map((row, meta) -> new JobRow(null, null, null, null, null, row.get("state", String.class), null,
						null, null, null, null, 0, row.get("attempt", Integer.class), null, null, 0, null, null,
						row.get("error_code", String.class), null, null, null))
				.one().block(Duration.ofSeconds(10));
	}
}
