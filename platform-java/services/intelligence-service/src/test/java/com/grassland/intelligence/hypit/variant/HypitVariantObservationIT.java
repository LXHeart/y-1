package com.grassland.intelligence.hypit.variant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitBuildRepository.BuildRow;
import com.grassland.intelligence.hypit.build.HypitBuildService;
import com.grassland.intelligence.hypit.build.HypitResultService;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Real PostgreSQL claims/fences with controlled observation delivery; no
 * wall-clock sleeps.
 */
@TestPropertySource(properties = {"hypit.enabled=true", "hypit.variant-worker.poll-ms=3600000"})
class HypitVariantObservationIT extends IntelligenceItSupport {
	private static final String OWNER = "aaaaaaaa-1073-4000-8000-000000001006";
	@Autowired
	HypitVariantRepository variantRepo;
	@Autowired
	HypitBuildRepository buildRepo;
	@Autowired
	HypitBuildService builds;
	@Autowired
	HypitResultService results;
	@Autowired
	HypitVariantWorker worker;
	private UUID projectId;

	@ParameterizedTest
	@CsvSource({"queued,submitting,,false,queued", "running,active,,false,running",
			"running,execution_decided,complete,true,running", "running,result_pending,complete,true,running",
			"running,finished,complete,false,running", "queued,finished,complete,true,succeeded",
			"running,finished,complete,true,succeeded", "running,finished,failed,true,failed",
			"running,finished,cancelled,true,cancelled", "running,submission_incomplete,failed,false,failed",
			"running,submission_incomplete,cancelled,false,cancelled", "running,finished,,false,running"})
	void onlyCompletedBuildWithAvailableOutputCanSucceed(String state, String lifecycle, String outcome,
			boolean outputs, String expected) {
		UUID id = seedVariantWithBuild(state, lifecycle, outcome, outputs);
		worker.runOnce().collectList().block(Duration.ofSeconds(15));
		var after = variantRepo.findById(id).block();
		assertThat(after.state()).isEqualTo(expected);
		assertThat(after.attempt()).isEqualTo(1);
		var observation = observationState(id);
		assertThat(observation.get("observation_token")).isNull();
		assertThat(observation.get("observation_failures"))
				.isEqualTo("finished".equals(lifecycle) && outcome == null ? 1 : 0);
	}

	@Test
	void delayedPreviousAttemptCannotOverwriteRunningRetry() throws Exception {
		assertDelayedObservationCannotChangeCurrentClaim(true);
	}

	@Test
	void lateExpiredLeaseCannotOverwriteNewObservationInTheSameAttempt() throws Exception {
		assertDelayedObservationCannotChangeCurrentClaim(false);
	}

	private void assertDelayedObservationCannotChangeCurrentClaim(boolean retry) throws Exception {
		UUID id = seedVariantWithBuild("running", "finished", retry ? "failed" : "complete", !retry);
		var original = variantRepo.findById(id).block();
		var delayed = Sinks.<BuildRow>one();
		var started = new CompletableFuture<Void>();
		var firstCycle = delayedWorker(original.buildId(), delayed, started).runOnce().collectList().toFuture();
		try {
			started.get(5, TimeUnit.SECONDS);
			var firstToken = observationState(id).get("observation_token");
			UUID currentBuild = original.buildId();
			if (retry) {
				assertThat(variantRepo.cancel(id).block().state()).isEqualTo("cancelled");
				assertThat(variantRepo.retry(id).block().attempt()).isEqualTo(2);
				UUID other = seedVariantWithBuild("succeeded", "finished", "complete", true);
				currentBuild = variantRepo.findById(other).block().buildId();
				variantRepo.markRunning(id, currentBuild).block();
			} else {
				db.sql("UPDATE hypit_variant SET observation_lease_until = now() - interval '1 second'"
						+ " WHERE id = CAST(:id AS uuid)").bind("id", id.toString()).then().block();
			}
			// Keep the NEW observation running while the old one returns. A terminal-state
			// guard alone cannot pass these assertions; the claim must actually be fenced.
			var currentResponse = Sinks.<BuildRow>one();
			var currentStarted = new CompletableFuture<Void>();
			var currentCycle = delayedWorker(currentBuild, currentResponse, currentStarted).runOnce().collectList()
					.toFuture();
			try {
				currentStarted.get(5, TimeUnit.SECONDS);
				var inFlight = variantRepo.findById(id).block();
				var observation = observationState(id);
				assertThat(inFlight.state()).isEqualTo("running");
				assertThat(inFlight.attempt()).isEqualTo(retry ? 2 : 1);
				assertThat(inFlight.buildId()).isEqualTo(currentBuild);
				assertThat(observation.get("observation_token")).isNotNull().isNotEqualTo(firstToken);
				if (retry) {
					delayed.tryEmitValue(buildRepo.findById(original.buildId()).block());
				} else {
					delayed.tryEmitError(new IllegalStateException("late_transport_failure"));
				}
				firstCycle.get(5, TimeUnit.SECONDS);
				assertThat(variantRepo.findById(id).block()).isEqualTo(inFlight);
				assertThat(observationState(id)).isEqualTo(observation);

				currentResponse.tryEmitValue(buildRepo.findById(currentBuild).block());
				currentCycle.get(5, TimeUnit.SECONDS);
				assertThat(variantRepo.findById(id).block().state()).isEqualTo("succeeded");
				assertThat(observationState(id).get("observation_failures")).isEqualTo(0);
			} finally {
				currentCycle.cancel(true);
			}
		} finally {
			firstCycle.cancel(true);
		}
	}

	@Test
	void observationFailureBacksOffWithoutRetryingBuildAndNextCycleRecovers() {
		UUID id = seedVariantWithBuild("running", "finished", "complete", true);
		var before = variantRepo.findById(id).block();
		var failingBuilds = mock(HypitBuildService.class);
		when(failingBuilds.converge(any())).thenAnswer(call -> {
			BuildRow build = call.getArgument(0);
			return build.id().equals(before.buildId())
					? Mono.error(new IllegalStateException("temporary_transport_failure"))
					: builds.converge(build);
		});
		new HypitVariantWorker(variantRepo, failingBuilds, buildRepo, results).runOnce().collectList()
				.block(Duration.ofSeconds(15));
		assertThat(variantRepo.findById(id).block()).isEqualTo(before);
		var failed = observationState(id);
		assertThat(failed).containsEntry("observation_failures", 1).containsEntry("last_observation_error",
				"IllegalStateException");
		assertThat(failed.get("observation_token")).isNull();
		assertThat(db
				.sql("SELECT next_poll_at > last_observed_at AS delayed FROM hypit_variant"
						+ " WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString()).map(row -> row.get("delayed", Boolean.class)).one().block()).isTrue();
		// Make only the persisted poll clock due; do not create a new attempt or build.
		db.sql("UPDATE hypit_variant SET next_poll_at = now() - interval '1 second'" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", id.toString()).then().block();
		worker.runOnce().collectList().block(Duration.ofSeconds(15));
		var recovered = variantRepo.findById(id).block();
		assertThat(recovered.state()).isEqualTo("succeeded");
		assertThat(recovered.buildId()).isEqualTo(before.buildId());
		assertThat(recovered.attempt()).isEqualTo(before.attempt());
		assertThat(observationState(id)).containsEntry("observation_failures", 0);
		assertThat(observationState(id).get("last_observation_error")).isNull();
	}

	private HypitVariantWorker delayedWorker(UUID buildId, Sinks.One<BuildRow> response,
			CompletableFuture<Void> started) {
		var delayedBuilds = mock(HypitBuildService.class);
		when(delayedBuilds.converge(any())).thenAnswer(call -> {
			BuildRow build = call.getArgument(0);
			if (!build.id().equals(buildId))
				return builds.converge(build);
			started.complete(null);
			return response.asMono();
		});
		return new HypitVariantWorker(variantRepo, delayedBuilds, buildRepo, results);
	}

	private Map<String, Object> observationState(UUID id) {
		return db.sql("SELECT observation_token, observation_lease_until, observation_failures,"
				+ " last_observation_error, last_observed_at, next_poll_at FROM hypit_variant"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", id.toString()).fetch().one().block();
	}
	@BeforeEach
	void seed() {
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision, version)"
				+ " VALUES (CAST(:id AS uuid), :owner, gen_random_uuid(), 'fix2-c26', 'clone', 'ready', 1, 1)")
				.bind("id", projectId.toString()).bind("owner", OWNER).then().block(Duration.ofSeconds(10));
	}

	@AfterEach
	void cleanup() {
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

}
