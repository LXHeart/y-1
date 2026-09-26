package com.grassland.intelligence.hypit.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.job.HypitCommandRepository.Accepted;
import com.grassland.intelligence.hypit.job.HypitCommandRepository.CommandRow;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;

/**
 * 幂等命令/租约/事件序列（任务书 #107-1 C107-04 / TC107-04-01 / K06.1 / 04.9）。
 *
 * <p>
 * 真 PostgreSQL（Testcontainers）验证 UNIQUE(account_id,action,request_id)、hash 冲突
 * 409、 FOR UPDATE SKIP LOCKED 认领唯一性、租约 owner 绑定续租、事件 sequence 无洞不重。
 */
class HypitCommandIT extends IntelligenceItSupport {

	private static final String OWNER_A = "aaaaaaaa-0000-4000-8000-00000000000a";
	private static final String OWNER_B = "aaaaaaaa-0000-4000-8000-00000000000b";
	private static final String JOB_PREFIX = "bbbbbbbb-";

	@Autowired
	HypitCommandRepository commands;

	@Autowired
	HypitJobRepository jobs;

	@Autowired
	HypitJobEventRepository events;

	@BeforeEach
	void clean() {
		db.sql("DELETE FROM hypit_job_event WHERE job_id::text LIKE 'bbbbbbbb-%'").then()
				.then(db.sql("DELETE FROM hypit_job WHERE account_id IN (:a, :b)").bind("a", OWNER_A).bind("b", OWNER_B)
						.then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id IN (:a, :b)").bind("a", OWNER_A)
						.bind("b", OWNER_B).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id IN (:a, :b)").bind("a", OWNER_A)
						.bind("b", OWNER_B).then())
				.block(java.time.Duration.ofSeconds(10));
	}

	@Test
	void sameRequestSameBodyYieldsSingleRowAndDifferentBodyConflicts() {
		UUID requestId = UUID.randomUUID();
		String hash = "1".repeat(64);
		Accepted first = commands
				.insert(OWNER_A, "project.create", requestId, "project", hash, "{\"canonical\":\"a\"}", null).block();
		assertThat(first).isNotNull();
		assertThat(first.existing()).isFalse();

		// 并发同键同 body：ON CONFLICT 读原行。
		List<Accepted> concurrent = IntStream.range(0, 8).parallel()
				.mapToObj(i -> commands
						.insert(OWNER_A, "project.create", requestId, "project", hash, "{\"canonical\":\"a\"}", null)
						.block())
				.toList();
		assertThat(concurrent).allMatch(Accepted::existing);
		assertThat(concurrent).allMatch(accepted -> accepted.row().id().equals(first.row().id()));
		Long count = db.sql("SELECT COUNT(*) AS c FROM hypit_command WHERE account_id = :owner").bind("owner", OWNER_A)
				.map((row, meta) -> row.get("c", Long.class)).one().block();
		assertThat(count).isEqualTo(1L);

		// 同键不同 body：service 侧映射 409；repository 层保留原行不动。
		Accepted divergent = commands
				.insert(OWNER_A, "project.create", requestId, "project", "2".repeat(64), "{\"canonical\":\"b\"}", null)
				.block();
		assertThat(divergent.existing()).isTrue();
		assertThat(divergent.row().payloadHash()).isEqualTo(hash);
		IntelligenceException conflict = HypitCommandRepository.conflict(divergent.row());
		assertThat(conflict.status()).isEqualTo(409);
		assertThat(conflict.code()).isEqualTo("hypit_idempotency_conflict");

		// 不同 owner 同 requestId：独立资源（幂等键含 account）。
		Accepted otherOwner = commands
				.insert(OWNER_B, "project.create", requestId, "project", hash, "{\"canonical\":\"a\"}", null).block();
		assertThat(otherOwner.existing()).isFalse();
		assertThat(otherOwner.row().id()).isNotEqualTo(first.row().id());
	}

	@Test
	void claimIsExclusiveAndLeaseRenewalIsOwnerBound() {
		CommandRow command = commands.insert(OWNER_A, "job.x", UUID.randomUUID(), "job", "3".repeat(64), "{}", null)
				.block().row();
		UUID projectId = UUID.randomUUID();
		insertProject(projectId, "claim-test");
		HypitJobRepository.JobRow job = jobs
				.insert(new HypitJobRepository.JobRow(UUID.fromString(JOB_PREFIX + "0000-4000-8000-000000000001"),
						command.id(), projectId, OWNER_A, "job.x", "queued", "pending", null, null, null, null, 0, 1,
						null, null, 1, null, null, null, null, null, null))
				.block();

		UUID worker1 = UUID.randomUUID();
		UUID worker2 = UUID.randomUUID();
		List<HypitJobRepository.JobRow> firstClaims = jobs
				.claimDueList(worker1, Instant.now().plus(30, ChronoUnit.SECONDS), 10).block();
		List<HypitJobRepository.JobRow> secondClaims = jobs
				.claimDueList(worker2, Instant.now().plus(30, ChronoUnit.SECONDS), 10).block();
		assertThat(firstClaims).isNotNull();
		assertThat(secondClaims).isNotNull();
		assertThat(firstClaims.stream().filter(row -> row.id().equals(job.id())).count()).isEqualTo(1);
		assertThat(secondClaims.stream().filter(row -> row.id().equals(job.id())).count()).isZero();

		// 只有当前 lease owner 能续租。
		Long renewedByOwner = jobs.renewLease(job.id(), worker1, Instant.now().plus(30, ChronoUnit.SECONDS)).block();
		Long renewedByImpostor = jobs.renewLease(job.id(), worker2, Instant.now().plus(30, ChronoUnit.SECONDS)).block();
		assertThat(renewedByOwner).isEqualTo(1L);
		assertThat(renewedByImpostor).isEqualTo(0L);
	}

	@Test
	void jobEventSequencesAreGaplessAndUniqueUnderConcurrency() {
		UUID jobId = UUID.fromString(JOB_PREFIX + "0000-4000-8000-000000000002");
		CommandRow command = commands.insert(OWNER_A, "job.y", UUID.randomUUID(), "job", "4".repeat(64), "{}", null)
				.block().row();
		jobs.insert(new HypitJobRepository.JobRow(jobId, command.id(), null, OWNER_A, "job.y", "running", "work", null,
				null, null, null, 0, 1, null, null, 1, null, null, null, null, null, null)).block();

		// 32 个并发追加：PK(job_id,sequence) + 撞号有界重算 → 1..32 无洞不重。
		Flux.range(0, 32).flatMap(i -> events.append(jobId, "progress", "{\"i\":" + i + "}"), 8)
				.blockLast(java.time.Duration.ofSeconds(30));

		List<Long> sequences = events.listAfter(jobId, 0).map(HypitJobEventRepository.EventRow::sequence).collectList()
				.block(java.time.Duration.ofSeconds(10));
		assertThat(sequences).isNotNull();
		assertThat(sequences).hasSize(32);
		assertThat(sequences)
				.containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(1L, 32L).boxed().toList());

		assertThat(events.lastSequence(jobId).block()).isEqualTo(32L);
		assertThat(HypitJobService.parseCursor("evt-" + jobId + "-32")).isEqualTo(32L);
		assertThat(HypitJobService.parseCursor("garbage")).isZero();
	}

	@Test
	void activeWorkDetectionDrivesDeleteGuard() {
		UUID projectId = UUID.randomUUID();
		insertProject(projectId, "guard-test");
		assertThat(jobs.hasActiveWork(projectId).block()).isFalse();

		CommandRow command = commands
				.insert(OWNER_A, "job.z", UUID.randomUUID(), "job", "5".repeat(64), "{}", projectId).block().row();
		HypitJobRepository.JobRow inserted = jobs
				.insert(new HypitJobRepository.JobRow(UUID.fromString(JOB_PREFIX + "0000-4000-8000-000000000003"),
						command.id(), projectId, OWNER_A, "job.z", "queued", "pending", null, null, null, null, 0, 1,
						null, null, 1, null, null, null, null, null, null))
				.block();
		assertThat(jobs.hasActiveWork(projectId).block()).isTrue();

		// 终态后不再阻塞删除。
		jobs.updateState(inserted.id(), "succeeded", null, null).block();
		assertThat(jobs.hasActiveWork(projectId).block()).isFalse();
	}

	private void insertProject(UUID projectId, String title) {
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:workspace AS uuid), :title, 'brief', 'ready')")
				.bind("id", projectId.toString()).bind("owner", OWNER_A).bind("workspace", UUID.randomUUID().toString())
				.bind("title", title).then().block(java.time.Duration.ofSeconds(10));
	}
}
