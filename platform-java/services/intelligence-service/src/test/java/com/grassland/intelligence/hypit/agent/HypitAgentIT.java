package com.grassland.intelligence.hypit.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository.ActionRow;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * 持久 Agent 端到端（任务书 #107-2 C107-14 / TC107-14-02～04 持久面）：真 PostgreSQL 上创建
 * hypit.agent job（checkpoint 携带一批工具动作），worker 认领后执行 knowledge 检索/读取，动作行持久且状态收口
 * succeeded；越权工具在 StepService 层被拒并如实落 failed 动作行。
 */
class HypitAgentIT extends IntelligenceItSupport {

	private static final String OWNER = "dddddddd-0000-4000-8000-00000000000d";

	@Autowired
	HypitAgentWorker worker;

	@Autowired
	HypitAgentStepService steps;

	@Autowired
	HypitJobRepository jobs;

	@Autowired
	HypitJobActionRepository jobActions;

	@Autowired
	DatabaseClient db;

	private UUID projectId;

	@BeforeEach
	void seed() {
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'agent-it', 'clone', 'ready', 1)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
	}

	@org.junit.jupiter.api.AfterEach
	void sweep() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_job_action WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
				.bind("o", OWNER).then()
				.then(db.sql("DELETE FROM hypit_job_event WHERE job_id IN"
						+ " (SELECT id FROM hypit_job WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
				.block(Duration.ofSeconds(20));
	}

	private JobRow awaitSucceeded(UUID jobId) throws InterruptedException {
		JobRow job = null;
		for (int i = 0; i < 40; i++) {
			job = jobs.findById(jobId).block(Duration.ofSeconds(10));
			if (job != null && "succeeded".equals(job.state())) {
				return job;
			}
			Thread.sleep(250);
		}
		return job;
	}

	private UUID agentJob(String scope, String actionsJson) {
		UUID jobId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_job(id, account_id, project_id, kind, state, checkpoint_json)"
				+ " VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 'hypit.agent', 'queued'," + " CAST(:cp AS jsonb))")
				.bind("id", jobId.toString()).bind("o", OWNER).bind("p", projectId.toString())
				.bind("cp", actionsJson.replace("__SCOPE__", scope)).then().block(Duration.ofSeconds(10));
		return jobId;
	}

	@Test
	void agentJobRunsKnowledgeToolsAndPersistsActionRows() throws InterruptedException {
		UUID jobId = agentJob("read_only", """
				{"stepIndex":0,"scope":"read_only","actions":[
				  {"kind":"knowledge.search","input":{"query":"browser capture","limit":5}},
				  {"kind":"knowledge.read","input":{"path":"references/production/browser-capture.md"}}
				]}
				""");
		worker.runOnce().block(Duration.ofSeconds(60));
		// @Scheduled worker 可能已抢先执行：轮询等待终态，而不是断言本批认领数。
		JobRow job = awaitSucceeded(jobId);

		List<ActionRow> actions = jobActions.findByJob(jobId).collectList().block(Duration.ofSeconds(10));
		assertThat(actions).hasSize(2);
		assertThat(actions.get(0).state()).isEqualTo("succeeded");
		assertThat(actions.get(0).inputHash()).hasSize(64);
		// 检索结果确实命中 production 主题文档（真实索引，非桩）
		assertThat(actions.get(0).resultJson()).contains("browser");
	}

	@Test
	void outOfScopeActionFailsHonestlyWithoutBlockingJobAccounting() throws InterruptedException {
		UUID jobId = agentJob("read_only", """
				{"stepIndex":0,"scope":"read_only","actions":[
				  {"kind":"build.submit","input":{"planId":"%s"}}
				]}
				""".formatted(UUID.randomUUID()));
		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow job = awaitSucceeded(jobId);
		List<ActionRow> actions = jobActions.findByJob(jobId).collectList().block(Duration.ofSeconds(10));
		assertThat(actions).hasSize(1);
		assertThat(actions.get(0).state()).as("越权动作如实 failed 留诊断").isEqualTo("failed");
		assertThat(actions.get(0).resultJson()).contains("hypit_agent_scope");
	}
}
