package com.grassland.intelligence.hypit.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitOutputRepository;
import com.grassland.intelligence.hypit.build.HypitOutputRepository.OutputRow;
import com.grassland.intelligence.hypit.build.HypitPlanRepository;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * Output 索引与归档状态机（任务书 #107-2 C107-10 / T10-4、T10-6 的持久层语义）。
 *
 * <p>
 * 真 PostgreSQL：indexResult 可重入（UNIQUE(build,output)，重复 insert 回读原行）、 archive
 * claim 恰一家成功（pending→archiving CAS）、completeArchive 只补空 mediaId 不覆盖已归档事实、失败落
 * failed 留诊断、跨 Build 输出历史按名筛选新→旧。 与 sidecar/对象存储交互的端到端归档由 HypitArchiveIT 覆盖。
 */
class HypitOutputIT extends IntelligenceItSupport {

	private static final String OWNER = "dddddddd-0000-4000-8000-00000000000d";

	@Autowired
	HypitOutputRepository outputs;

	@Autowired
	HypitBuildRepository builds;

	@Autowired
	HypitPlanRepository plans;

	@Autowired
	DatabaseClient db;

	private UUID projectId;

	@BeforeEach
	void seed() {
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'output-it', 'clone', 'ready', 3)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
	}

	@org.junit.jupiter.api.AfterEach
	void sweep() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
				.bind("o", OWNER).then()
				.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_output WHERE build_id IN (SELECT b.id FROM hypit_build b"
						+ " JOIN hypit_project p ON p.id = b.project_id WHERE p.account_id = :o)").bind("o", OWNER)
						.then())
				.then(db.sql("DELETE FROM hypit_build WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_plan WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
				.block(Duration.ofSeconds(20));
	}

	private UUID build(String engineBuildId) {
		UUID planId = plans
				.insertPlan(UUID.randomUUID(), projectId, 3, "main.svrun", "c".repeat(64), "p".repeat(64), "{}")
				.block(Duration.ofSeconds(10)).id();
		UUID commandId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_command(id, account_id, project_id, target_key, action, request_id,"
				+ " payload_hash, payload_json, state) VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 't',"
				+ " 'build.submit', CAST(:r AS uuid), :h, '{}', 'acknowledged')").bind("id", commandId.toString())
				.bind("o", OWNER).bind("p", projectId.toString()).bind("r", UUID.randomUUID()).bind("h", "h".repeat(64))
				.then().block(Duration.ofSeconds(10));
		return builds.insert(UUID.randomUUID(), commandId, projectId, 3L, planId, "main.svrun")
				.flatMap(row -> db
						.sql("UPDATE hypit_build SET engine_build_id = :e, lifecycle = 'finished',"
								+ " outcome = 'complete', finished_at = now() WHERE id = CAST(:id AS uuid)")
						.bind("e", engineBuildId).bind("id", row.id().toString()).then()
						.then(builds.findById(row.id())))
				.block(Duration.ofSeconds(10)).id();
	}

	@Test
	void indexIsReentrantAndNeverOverwritesArchivedMediaId() {
		UUID buildId = build("bld_20260926T000000000Z_OUTITAAAA");
		OutputRow first = outputs
				.insert(UUID.randomUUID(), buildId, "final.video", "resource", "video/mp4", 1024L, "{}")
				.block(Duration.ofSeconds(10));
		OutputRow replay = outputs
				.insert(UUID.randomUUID(), buildId, "final.video", "resource", "video/mp4", 1024L, "{}")
				.block(Duration.ofSeconds(10));
		assertThat(replay.id()).as("同 (build, output) 回读原行").isEqualTo(first.id());
		assertThat(outputs.countByBuild(buildId).block(Duration.ofSeconds(10))).isEqualTo(1L);

		assertThat(outputs.claimArchive(first.id()).block(Duration.ofSeconds(10))).isTrue();
		assertThat(outputs.claimArchive(first.id()).block(Duration.ofSeconds(10))).as("第二次 claim 失败（不启动第二个上传）")
				.isFalse();
		UUID mediaId = UUID.randomUUID();
		assertThat(outputs.completeArchive(first.id(), mediaId, "res-handle-1").block(Duration.ofSeconds(10))).isTrue();
		assertThat(outputs.completeArchive(first.id(), UUID.randomUUID(), "res-handle-2").block(Duration.ofSeconds(10)))
				.as("重复 complete 不覆盖已归档 mediaId").isTrue();
		OutputRow archived = outputs.findById(first.id()).block(Duration.ofSeconds(10));
		assertThat(archived.mediaId()).isEqualTo(mediaId);
		assertThat(archived.resourceHandle()).isEqualTo("res-handle-1");
		assertThat(archived.archiveState()).isEqualTo("archived");
	}

	@Test
	void failedArchiveKeepsDiagnosticAndHistoryFiltersByOutputName() {
		UUID buildA = build("bld_20260926T000000001Z_OUTITAAAA");
		UUID buildB = build("bld_20260926T000000002Z_OUTITAAAA");
		outputs.insert(UUID.randomUUID(), buildA, "final.video", "resource", "video/mp4", 10L, "{}")
				.block(Duration.ofSeconds(10));
		outputs.insert(UUID.randomUUID(), buildA, "poster.image", "composite", null, null, "{}")
				.block(Duration.ofSeconds(10));
		outputs.insert(UUID.randomUUID(), buildB, "final.video", "resource", "video/mp4", 12L, "{}")
				.block(Duration.ofSeconds(10));

		OutputRow failing = outputs.findByBuildAndName(buildA, "final.video").block(Duration.ofSeconds(10));
		assertThat(outputs.claimArchive(failing.id()).block(Duration.ofSeconds(10))).isTrue();
		assertThat(outputs.failArchive(failing.id(), "hypit_archive_failed").block(Duration.ofSeconds(10))).isTrue();
		assertThat(outputs.findById(failing.id()).block(Duration.ofSeconds(10)).archiveState()).isEqualTo("failed");
		assertThat(outputs.findById(failing.id()).block(Duration.ofSeconds(10)).errorCode())
				.isEqualTo("hypit_archive_failed");

		List<OutputRow> history = outputs.historyByProject(projectId, "final.video", 10).collectList()
				.block(Duration.ofSeconds(10));
		assertThat(history).as("按名筛选跨 Build 历史，新→旧").hasSize(2);
		assertThat(history.get(0).buildId()).isEqualTo(buildB);
		assertThat(outputs.historyByProject(projectId, "absent.output", 10).collectList().block(Duration.ofSeconds(10)))
				.isEmpty();
	}
}
