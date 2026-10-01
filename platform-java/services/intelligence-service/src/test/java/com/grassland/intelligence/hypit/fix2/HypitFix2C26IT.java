package com.grassland.intelligence.hypit.fix2;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge.PrepareRequest;
import com.grassland.intelligence.hypit.variant.HypitVariantService;
import com.grassland.intelligence.hypit.variant.HypitVariantWorker;
import com.grassland.intelligence.security.IntelligenceException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * C107F2-26（107-fix-2 / F13、F28 / §6.12）：变体生命周期显式映射、重试/取消语义与 variant_count 预算闸。
 *
 * <ul>
 * <li>TC-F2-26-01 旧 worker switch 只认 succeeded/failed/cancelled，而真实 Build
 * lifecycle 是 submitting/active/execution_decided/result_pending/finished/
 * submission_incomplete——恒不匹配（F13 根因）。显式映射：finished+complete+结果就绪 →
 * succeeded；finished+cancelled/submission_incomplete+cancelled → cancelled；
 * 中间态继续观察，绝不提前成功。</li>
 * <li>TC-F2-26-03 只重试失败项：attempt 恰 +1、回 queued、build 解绑；同键重复重试被
 * 服务层状态闸拒（hypit_state_conflict）不再增；成功项 build 不变；取消只作用指定项。</li>
 * <li>TC-F2-26-04 variant_count=1 的 grant 并发两项 prepare → 恰一项获准 （Provider
 * 执行不超预算）。</li>
 * </ul>
 */
@TestPropertySource(properties = "hypit.enabled=true")
class HypitFix2C26IT extends IntelligenceItSupport {

	private static final String OWNER = "eeeeeeee-0000-4000-8000-00000000260a";

	@Autowired
	HypitVariantWorker worker;

	@Autowired
	HypitVariantService variantService;

	@Autowired
	HypitBuildRepository buildRepo;

	@Autowired
	HypitExternalExecutionBridge bridge;

	@Autowired
	DatabaseClient db;

	private UUID projectId;

	@BeforeEach
	void seed() {
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision, version)"
				+ " VALUES (CAST(:id AS uuid), :owner, gen_random_uuid(), 'fix2-c26', 'clone', 'ready', 1, 1)")
				.bind("id", projectId.toString()).bind("owner", OWNER).then().block(Duration.ofSeconds(10));
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_execution WHERE grant_id IN (SELECT id FROM hypit_execution_grant"
				+ " WHERE account_id = :o)").bind("o", OWNER).then()
				.then(db.sql("DELETE FROM hypit_execution_grant WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_output WHERE build_id IN (SELECT id FROM hypit_build"
						+ " WHERE project_id IN (SELECT id FROM hypit_project WHERE account_id = :o))").bind("o", OWNER)
						.then())
				.then(db.sql("DELETE FROM hypit_variant WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_build WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_job WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
				.block(Duration.ofSeconds(20));
	}

	/**
	 * 直插 job（variant batch 外键）+ 变体 + 命令 + build（+可选已归档输出）， lifecycle/outcome
	 * 按参数绑定；engineBuildId 保持 NULL——build.converge 就地 读取已存事实，不触远程。
	 */
	private UUID seedVariantWithBuild(String variantState, String lifecycle, String outcome, boolean withOutputs) {
		UUID jobId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_job(id, project_id, account_id, kind, state) VALUES (CAST(:id AS uuid),"
				+ " CAST(:p AS uuid), :owner, 'variant.batch', 'succeeded')").bind("id", jobId.toString())
				.bind("p", projectId.toString()).bind("owner", OWNER).then().block(Duration.ofSeconds(10));
		UUID variantId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_variant(id, project_id, batch_job_id, ordinal, base_revision, parameters_json,"
				+ " run_file, state, attempt) VALUES (CAST(:id AS uuid), CAST(:p AS uuid), CAST(:j AS uuid), 1,"
				+ " 1, CAST('{}' AS jsonb), 'main.svrun', :state, 1)").bind("id", variantId.toString())
				.bind("p", projectId.toString()).bind("j", jobId.toString()).bind("state", variantState).then()
				.block(Duration.ofSeconds(10));
		UUID buildId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_command(id, account_id, project_id, target_key, action, request_id,"
				+ " payload_hash, payload_json, state) VALUES (CAST(:id AS uuid), :owner, CAST(:p AS uuid),"
				+ " 'it', 'build.submit', gen_random_uuid(), :hash, CAST('{}' AS jsonb), 'succeeded')")
				.bind("id", buildId.toString()).bind("owner", OWNER).bind("p", projectId.toString())
				.bind("hash", "b".repeat(64)).then().block(Duration.ofSeconds(10));
		var spec = db
				.sql("INSERT INTO hypit_build(id, command_id, project_id, revision, plan_id, run_file,"
						+ " lifecycle, outcome, finished_at) VALUES (CAST(:id AS uuid), CAST(:id AS uuid),"
						+ " CAST(:p AS uuid), 1, NULL, 'main.svrun', :lifecycle, :outcome,"
						+ " CASE WHEN :lifecycle IN ('finished','submission_incomplete') THEN now() END)")
				.bind("id", buildId.toString()).bind("p", projectId.toString()).bind("lifecycle", lifecycle);
		spec = outcome == null ? spec.bindNull("outcome", String.class) : spec.bind("outcome", outcome);
		spec.then().block(Duration.ofSeconds(10));
		db.sql("UPDATE hypit_variant SET build_id = CAST(:b AS uuid) WHERE id = CAST(:id AS uuid)")
				.bind("b", buildId.toString()).bind("id", variantId.toString()).then().block(Duration.ofSeconds(10));
		if (withOutputs) {
			// 结果就绪：结果归档非空（syncOutputs 引擎侧失败回落读 hypit_output——直插一条已归档输出）。
			db.sql("INSERT INTO hypit_output(id, build_id, output_name, kind, media_type, value_summary,"
					+ " archive_state) VALUES (gen_random_uuid(), CAST(:b AS uuid), 'final.mp4', 'resource',"
					+ " 'video/mp4', CAST('{}' AS jsonb), 'archived')").bind("b", buildId.toString()).then()
					.block(Duration.ofSeconds(10));
		}
		return variantId;
	}

	private HypitBuildRepository.BuildRow buildRow(UUID buildId) {
		return buildRepo.findById(buildId).block(Duration.ofSeconds(10));
	}

	// ── TC-F2-26-01：显式终态映射（F13：旧 switch 恒不匹配真实 lifecycle） ──────
	@Test
	@DisplayName("TC-F2-26-01 finished+complete+结果就绪→succeeded；中间态观察；cancelled 映射")
	void explicitLifecycleMapping() {
		// ① finished + complete + 结果就绪 → succeeded。
		UUID ready = seedVariantWithBuild("running", "finished", "complete", true);
		worker.runOnce().blockLast(Duration.ofSeconds(30));
		assertThat(variantState(ready)).isEqualTo("succeeded");

		// ② finished + complete 但结果未归档 → 不得提前成功（保持 running 观察）。
		UUID notReady = seedVariantWithBuild("running", "finished", "complete", false);
		worker.runOnce().blockLast(Duration.ofSeconds(30));
		assertThat(variantState(notReady)).as("resultReady=false keeps observing").isEqualTo("running");

		// ③ execution_decided/result_pending 中间态 → 继续观察。
		UUID intermediate = seedVariantWithBuild("running", "execution_decided", null, false);
		UUID pending = seedVariantWithBuild("running", "result_pending", null, false);
		worker.runOnce().blockLast(Duration.ofSeconds(30));
		assertThat(variantState(intermediate)).as("intermediate state keeps observing").isEqualTo("running");
		assertThat(variantState(pending)).as("result_pending keeps observing").isEqualTo("running");

		// ④ finished+cancelled → cancelled；submission_incomplete+cancelled → cancelled；
		// submission_incomplete+failed → failed。
		UUID cancelledBuild = seedVariantWithBuild("running", "finished", "cancelled", true);
		worker.runOnce().blockLast(Duration.ofSeconds(30));
		assertThat(variantState(cancelledBuild)).isEqualTo("cancelled");
		UUID cancelledSubmit = seedVariantWithBuild("running", "submission_incomplete", "cancelled", false);
		worker.runOnce().blockLast(Duration.ofSeconds(30));
		assertThat(variantState(cancelledSubmit)).isEqualTo("cancelled");
		UUID failedSubmit = seedVariantWithBuild("running", "submission_incomplete", "failed", false);
		worker.runOnce().blockLast(Duration.ofSeconds(30));
		assertThat(variantState(failedSubmit)).isEqualTo("failed");
	}

	// ── TC-F2-26-03：只重试失败项；同键重复不增 attempt；取消只作用指定项 ──────
	@Test
	@DisplayName("TC-F2-26-03 重试失败项 attempt 恰+1；重复被状态闸拒；取消只作用指定项")
	void retryOnlyFailedAndCancelScopesToItem() {
		UUID succeeded = seedVariantWithBuild("succeeded", "finished", "complete", true);
		UUID failed = seedVariantWithBuild("failed", "finished", "failed", false);
		UUID sibling = seedVariantWithBuild("running", "active", null, false);
		UUID succeededBuildId = variantBuildId(succeeded);

		// 重试失败项：attempt +1、回 queued、build 解绑。
		variantService.retryVariant(OWNER, projectId, failed).block(Duration.ofSeconds(10));
		assertThat(variantState(failed)).isEqualTo("queued");
		assertThat(variantAttempt(failed)).isEqualTo(2);
		assertThat(variantBuildId(failed)).isNull();

		// 同键重复重试：已 queued（非 failed/cancelled）→ 服务层状态闸 409，attempt 不再增。
		Throwable repeat = catchThrowable(
				() -> variantService.retryVariant(OWNER, projectId, failed).block(Duration.ofSeconds(10)));
		assertThat(repeat).isInstanceOf(IntelligenceException.class);
		assertThat(((IntelligenceException) repeat).code()).isEqualTo("hypit_state_conflict");
		assertThat(variantAttempt(failed)).as("repeated retry does not grow attempt").isEqualTo(2);

		// 成功项不动：state/build 原样（不自动重做）。
		assertThat(variantState(succeeded)).isEqualTo("succeeded");
		assertThat(variantBuildId(succeeded)).as("succeeded build untouched").isEqualTo(succeededBuildId);

		// 取消只作用指定项：engineBuildId 未落 → build 就地 submission_incomplete，
		// sibling 变体 cancelled，成功项保留。
		variantService.cancelVariant(OWNER, projectId, sibling, UUID.randomUUID()).block(Duration.ofSeconds(10));
		assertThat(variantState(sibling)).isEqualTo("cancelled");
		assertThat(variantState(succeeded)).isEqualTo("succeeded");
		// 兄弟 build 真实推进到取消终态（不是只改变体文字）。
		HypitBuildRepository.BuildRow siblingBuild = buildRow(variantBuildId(sibling));
		assertThat(siblingBuild).isNotNull();
		assertThat(siblingBuild.lifecycle()).isEqualTo("submission_incomplete");
		assertThat(siblingBuild.outcome()).isEqualTo("cancelled");
	}

	// ── TC-F2-26-04：variant_count=1 并发两项 → 恰一项获准 ─────────────────────
	@Test
	@DisplayName("TC-F2-26-04 variant_count=1 并发 prepare：恰一项获准，另一项 capacity_exceeded")
	void concurrentPreparesRespectVariantCountBudget() {
		String grantId = seedGrantWithVariantCount(1);
		List<Mono<Boolean>> attempts = java.util.stream.IntStream.range(0, 2)
				.mapToObj(index -> Mono.fromCallable(() -> index)
						.subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
						.flatMap(ordinal -> bridge.prepare(new PrepareRequest(UUID.randomUUID(), projectId, null, null,
								"need-" + ordinal, "media", "model-x", "endpoint-main", "hash-" + UUID.randomUUID(),
								UUID.fromString(grantId), new BigDecimal("1.000000"), List.of("media.generate")))
								.map(permit -> true).onErrorResume(IntelligenceException.class, error -> {
									if ("hypit_capacity_exceeded".equals(error.code())) {
										return Mono.just(false);
									}
									return Mono.error(error);
								})))
				.toList();
		List<Boolean> outcomes = Flux.merge(attempts).collectList().block(Duration.ofSeconds(30));
		Long rows = db.sql("SELECT COUNT(*) AS n FROM hypit_execution WHERE grant_id = CAST(:g AS uuid)")
				.bind("g", grantId).map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(rows).as("exactly one prepare accepted under variant_count=1").isEqualTo(1L);
		assertThat(outcomes).as("one accepted, one capacity-exceeded").containsExactlyInAnyOrder(true, false);
	}

	private String seedGrantWithVariantCount(int variantCount) {
		UUID grantId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_execution_grant(id, project_id, account_id, scope_hash, scope_json, currency,"
				+ " expires_at, max_cost, allow_unknown, variant_count) VALUES (CAST(:id AS uuid),"
				+ " CAST(:p AS uuid), :owner, :scope, CAST('{\"targets\":[\"media.generate\"]}' AS jsonb), 'USD',"
				+ " now() + interval '1 hour', 10.000000, false, :variantCount)").bind("id", grantId.toString())
				.bind("p", projectId.toString()).bind("owner", OWNER).bind("scope", "c".repeat(64))
				.bind("variantCount", variantCount).then().block(Duration.ofSeconds(10));
		return grantId.toString();
	}

	private String variantState(UUID variantId) {
		String state = db.sql("SELECT state FROM hypit_variant WHERE id = CAST(:id AS uuid)")
				.bind("id", variantId.toString()).map((row, meta) -> row.get("state", String.class)).one()
				.block(Duration.ofSeconds(10));
		return state == null ? "" : state;
	}

	private long variantAttempt(UUID variantId) {
		Long attempt = db.sql("SELECT attempt FROM hypit_variant WHERE id = CAST(:id AS uuid)")
				.bind("id", variantId.toString()).map((row, meta) -> row.get("attempt", Long.class)).one()
				.block(Duration.ofSeconds(10));
		return attempt == null ? -1 : attempt;
	}

	private UUID variantBuildId(UUID variantId) {
		// COALESCE 防映射器返 null（重试解绑后 build_id 为空是合法态）。
		String buildId = db
				.sql("SELECT COALESCE(build_id::text, '') AS b FROM hypit_variant" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", variantId.toString()).map((row, meta) -> row.get("b", String.class)).one()
				.block(Duration.ofSeconds(10));
		return buildId == null || buildId.isEmpty() ? null : UUID.fromString(buildId);
	}

	private static Throwable catchThrowable(java.util.function.Supplier<?> supplier) {
		try {
			supplier.get();
			return null;
		} catch (Throwable error) {
			return error;
		}
	}
}
