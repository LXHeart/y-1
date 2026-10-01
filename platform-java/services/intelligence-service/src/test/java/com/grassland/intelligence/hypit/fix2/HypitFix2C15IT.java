package com.grassland.intelligence.hypit.fix2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.hypit.agent.HypitAgentStepService;
import com.grassland.intelligence.hypit.agent.HypitAgentToolRegistry;
import com.grassland.intelligence.hypit.agent.HypitAgentToolRegistry.ToolCall;
import com.grassland.intelligence.hypit.agent.HypitAgentWorker;
import com.grassland.intelligence.hypit.build.HypitPlanRepository;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository.ActionRow;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * C107F2-15（107-fix-2 §7.4 / RULE-09 / F23）：Agent 租约、操作幂等、取消及 resume。
 *
 * <p>
 * 真 PostgreSQL；planner 走 QWEN 桩。时间条件用 SQL 直改租约时间戳推进（§12.2 固定时钟），不真实等待。
 *
 * <ul>
 * <li>TC-F2-15-01 执行持续 70 秒、两 worker：90s 租约+15s 续租步进下第二 worker 不夺权（46s 处仍在
 * 续租）；A 卡死未续租越过后才接管续跑，planner 恰 1 次（不重复规划）。</li>
 * <li>TC-F2-15-02 prepared 已落、下游已接受（command+build 在库）、动作回执未落：重启接管沿同一
 * operationId/幂等键重放，不重复副作用（build/command 行数不增）。</li>
 * <li>TC-F2-15-03 最终动作完成但 terminal CAS 前暂停，发 cancel 再释放：收口 cancelled 不被
 * succeeded 覆盖，terminal 序列恰一条。</li>
 * <li>TC-F2-15-04 waiting_input 缺 assetId，补合法 ID resume 两次同 requestId：只恢复一次、
 * blockedReason 解除、新规划轮次继续，scope 不扩大。</li>
 * </ul>
 */
@TestPropertySource(properties = {"hypit.enabled=true", "hypit.agent-worker.enabled=false"})
class HypitFix2C15IT extends IntelligenceItSupport {

	private static final String OWNER = "eeeeeeee-0000-4000-8000-00000000015a";
	private static final ObjectMapper JSON = new ObjectMapper();

	@MockitoBean
	private CreditsClient credits;

	@Autowired
	HypitAgentWorker worker;

	@Autowired
	HypitAgentStepService steps;

	@Autowired
	HypitAgentToolRegistry registry;

	@Autowired
	HypitJobRepository jobs;

	@Autowired
	HypitJobActionRepository jobActions;

	@Autowired
	HypitPlanRepository plans;

	@Autowired
	DatabaseClient db;

	private UUID projectId;

	@BeforeEach
	void seed() {
		reset(credits);
		CreditsStubs.stubDefaults(credits);
		QWEN.resetAll();
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'fix2-c15', 'clone', 'ready', 2)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
		attachPlatformTextRow();
	}

	@AfterEach
	void sweep() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_job_action WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
				.bind("o", OWNER).then()
				.then(db.sql(
						"DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
						.bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_output WHERE build_id IN (SELECT b.id FROM hypit_build b"
						+ " JOIN hypit_project p ON p.id = b.project_id WHERE p.account_id = :o)").bind("o", OWNER)
						.then())
				.then(db.sql("DELETE FROM hypit_build WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_plan WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
				.block(Duration.ofSeconds(20));
	}

	/** 平台 text 行指向 QWEN（planner 走执行环）。 */
	private void attachPlatformTextRow() {
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
				+ " (SELECT id FROM platform_model_config WHERE base_url = :baseUrl)").bind("baseUrl", QWEN.baseUrl())
				.then()
				.then(db.sql("DELETE FROM platform_model_config WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.block(Duration.ofSeconds(10));
		attachPlatformTextCredential();
	}

	private void stubPlannerContent(String content) {
		QWEN.stubFor(post(urlEqualTo("/chat/completions"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"choices\":[{\"message\":{\"content\":" + JSON.valueToTree(content).toString()
								+ "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")));
	}

	private UUID insertJob(String state, String checkpointJson, String blockedReason) {
		UUID jobId = UUID.randomUUID();
		var statement = db
				.sql("INSERT INTO hypit_job(id, account_id, project_id, kind, state, blocked_reason,"
						+ " checkpoint_json) VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 'hypit.agent', :state,"
						+ " :blocked, CAST(:cp AS jsonb))")
				.bind("id", jobId.toString()).bind("o", OWNER).bind("p", projectId.toString()).bind("state", state)
				.bind("cp", checkpointJson);
		statement = blockedReason == null
				? statement.bindNull("blocked", String.class)
				: statement.bind("blocked", blockedReason);
		statement.then().block(Duration.ofSeconds(10));
		return jobId;
	}

	private JobRow awaitTerminal(UUID jobId, String state) throws InterruptedException {
		JobRow job = null;
		for (int i = 0; i < 80; i++) {
			job = jobs.findById(jobId).block(Duration.ofSeconds(10));
			if (job != null && state.equals(job.state())) {
				return job;
			}
			Thread.sleep(250);
		}
		return job;
	}

	private Map<String, Object> checkpointOf(UUID jobId) {
		return db.sql("SELECT checkpoint_json::text AS cp FROM hypit_job WHERE id = CAST(:j AS uuid)")
				.bind("j", jobId.toString()).map((row, meta) -> row.get("cp", String.class)).one()
				.map(com.grassland.intelligence.hypit.project.HypitJson::read).block(Duration.ofSeconds(10));
	}

	private JobRow jobRow(UUID jobId) {
		return jobs.findById(jobId).block(Duration.ofSeconds(10));
	}

	private int countEvents(UUID jobId, String type) {
		Long count = db
				.sql("SELECT COUNT(*) AS n FROM hypit_job_event WHERE job_id = CAST(:j AS uuid)"
						+ " AND type = CAST(:t AS text)")
				.bind("j", jobId.toString()).bind("t", type).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		return count == null ? 0 : count.intValue();
	}

	private List<String> terminalEventStates(UUID jobId) {
		return db
				.sql("SELECT payload->>'state' AS state FROM hypit_job_event"
						+ " WHERE job_id = CAST(:j AS uuid) AND type = 'terminal' ORDER BY sequence")
				.bind("j", jobId.toString()).map((row, meta) -> row.get("state", String.class)).all().collectList()
				.block(Duration.ofSeconds(10));
	}

	private long scalar(String sql) {
		Long count = db.sql(sql).map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	// ── TC-F2-15-01：长执行与双 worker（90s 租约/15s 续租，时钟用 SQL 推进） ──
	@Test
	@DisplayName("TC-F2-15-01 执行 70s 两 worker：续租期不夺权；卡死越界后接管，planner 恰 1 次")
	void longRunningJobKeepsSingleExecutionRightAndPlansOnce() throws Exception {
		// 真实规划段：jobA 排队被认领，完整跑通 plan→执行→收口（planner 真调用恰 1 次）。
		stubPlannerContent(
				"{\"actions\":[{\"kind\":\"knowledge.search\",\"input\":{\"query\":\"render\",\"limit\":2}}]}");
		UUID warmupJob = insertJob("queued", """
				{"intent":"analyze","brief":"预热","baseRevision":2,
				 "scope":{"allowedTools":["knowledge.search","knowledge.read","build.status"]},"actions":[]}
				""".replace("\n", " "), null);
		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow warmup = awaitTerminal(warmupJob, "succeeded");
		assertThat(warmup).as("A 正常链先真跑一次（planner 真调用）").isNotNull();
		assertThat(warmup.state()).isEqualTo("succeeded");

		// A 完成 plan（1 条 read 已冻结进 checkpoint）后卡死——动作未执行、未终态。
		UUID jobId = insertJob("running", """
				{"intent":"analyze","brief":"读取文档","baseRevision":2,"round":1,
				 "scope":{"allowedTools":["knowledge.search","knowledge.read","build.status"]},
				 "schemaVersion":2,"actionIndex":0,"actionSlot":0,"totalActions":1,"executedActions":0,
				 "fixRounds":0,"replans":0,"phase":"executing","blockedReason":null,
				 "observations":[],
				 "actions":[{"kind":"knowledge.read","input":{"path":"references/production/browser-capture.md"}}]}
				""".replace("\n", " "), null);
		UUID ownerA = UUID.randomUUID();
		db.sql("UPDATE hypit_job SET lease_owner = CAST(:o AS uuid), lease_until = now() + interval '90 seconds'"
				+ " WHERE id = CAST(:j AS uuid)").bind("o", ownerA.toString()).bind("j", jobId.toString()).then()
				.block(Duration.ofSeconds(10));

		// 46 秒处（旧 45s 阈值已过、A 仍在 15s 步进续租，租约余量未来）：B 不得夺权。
		db.sql("UPDATE hypit_job SET lease_until = now() + interval '44 seconds' WHERE id = CAST(:j AS uuid)")
				.bind("j", jobId.toString()).then().block(Duration.ofSeconds(10));
		int bTouched = worker.runOnce().block(Duration.ofSeconds(30));
		assertThat(bTouched).as("B 一无所获（租约未过期不重排、running 不认领）").isZero();
		assertThat(jobRow(jobId).state()).isEqualTo("running");
		assertThat(jobRow(jobId).leaseOwner()).isEqualTo(ownerA);

		// 模拟 A 卡死 70s 未续租（lease_until 落到过去）：B 接管续跑——不重新规划，沿 checkpoint 续跑。
		db.sql("UPDATE hypit_job SET lease_until = now() - interval '70 seconds' WHERE id = CAST(:j AS uuid)")
				.bind("j", jobId.toString()).then().block(Duration.ofSeconds(10));
		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow job = awaitTerminal(jobId, "succeeded");
		assertThat(job).as("B 接管后沿既有计划收口（不重新规划）").isNotNull();

		assertThat(QWEN.countRequestsMatching(postRequestedFor(urlEqualTo("/chat/completions")).build()).getCount())
				.as("planner 恰 1 次（B 接管沿冻结计划，不重复规划）").isEqualTo(1);
		List<ActionRow> actions = jobActions.findByJob(jobId).collectList().block(Duration.ofSeconds(10));
		assertThat(actions).as("B 沿 A 的计划执行（恰 1 条动作）").hasSize(1);
		assertThat(actions.get(0).state()).isEqualTo("succeeded");
		assertThat(jobRow(jobId).leaseOwner()).as("终态清租约").isNull();
	}

	// ── TC-F2-15-02：prepared 落库、下游已接受、回执未落 → 接管重放不双副作用 ──
	@Test
	@DisplayName("TC-F2-15-02 prepared+下游已接受：接管沿同幂等键重放，build/command 行数不增")
	void preparedActionTakeoverReplaysSameOperationWithoutDuplicateSideEffects() throws Exception {
		UUID planId = plans
				.insertPlan(UUID.randomUUID(), projectId, 2, "main.svrun", "c".repeat(64), "p".repeat(64), "{}")
				.block(Duration.ofSeconds(10)).id();
		String inputJson = "{\"planId\":\"" + planId + "\"}";
		UUID jobId = insertJob("queued", """
				{"scope":{"allowedTools":["build.submit","build.status"]},"actions":[
				 {"kind":"build.submit","input":{"planId":"%s"}}]}
				""".formatted(planId).replace("\n", " "), null);

		// 崩溃现场：动作行已 prepared（副作用前持久），工具层幂等键已把 command+build 落库（下游接受），
		// 但动作回执未落。prepared 行的 inputHash 与 canonical 序一致（planId 单键）。
		String canonical = "{\"planId\":\"" + planId + "\"}";
		String hash = com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge.sha256Hex(canonical);
		UUID operationId = HypitAgentStepService.operationIdOf(jobId, 0, hash);
		jobActions
				.insertPrepared(UUID.randomUUID(), jobId, 0, "tool", hash,
						"{\"tool\":\"build.submit\",\"input\":" + inputJson + "}", operationId)
				.block(Duration.ofSeconds(10));
		var precondition = registry
				.dispatch(
						new com.grassland.intelligence.hypit.agent.HypitAgentScope(false,
								java.util.Set.of("build.submit")),
						"build.submit", new ToolCall(jobId, projectId, OWNER, 2L, Map.of("planId", planId.toString())))
				.block(Duration.ofSeconds(30));
		assertThat(precondition.ok())
				.as("下游已接受前置：真实提交链成功（" + precondition.errorCode() + " " + precondition.errorMessage() + "）").isTrue();

		long buildsByProject = scalar(
				"SELECT COUNT(*) AS n FROM hypit_build WHERE project_id = CAST('" + projectId + "' AS uuid)");
		assertThat(buildsByProject).as("下游已接受（build 在库）").isEqualTo(1);

		// 重启接管：同 operationId 重放 → command 幂等回执 → 不产生第二个 build。
		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow job = awaitTerminal(jobId, "succeeded");
		assertThat(job).as("重放收敛后收口").isNotNull();

		assertThat(scalar("SELECT COUNT(*) AS n FROM hypit_build WHERE project_id = CAST('" + projectId + "' AS uuid)"))
				.as("不重复副作用：build 行数不增").isEqualTo(1);
		assertThat(scalar("SELECT COUNT(*) AS n FROM hypit_command WHERE account_id = '" + OWNER
				+ "' AND action = 'build.submit'")).as("command 恰一条").isEqualTo(1);
		List<ActionRow> actions = jobActions.findByJob(jobId).collectList().block(Duration.ofSeconds(10));
		assertThat(actions).hasSize(1);
		assertThat(actions.get(0).state()).as("prepared 经 CAS 收敛 succeeded").isEqualTo("succeeded");
		assertThat(actions.get(0).resultJson()).contains("buildId");
	}

	// ── TC-F2-15-03：最终动作完成、terminal CAS 前暂停 → cancel 优先 ──
	@Test
	@DisplayName("TC-F2-15-03 动作完成未收口时 cancel：收 cancelled 不被 succeeded 覆盖，terminal 恰一条")
	void cancelDuringTerminalWindowWinsOverLateSucceeded() throws Exception {
		UUID jobId = insertJob("queued", """
				{"scope":{"allowedTools":["knowledge.read"]},"actions":[
				 {"kind":"knowledge.read","input":{"path":"references/production/browser-capture.md"}}],
				 "stepIndex":0}
				""".replace("\n", " "), null);
		// 最终动作已完成（动作行 succeeded），但 job 尚未收口；用户发 cancel（cancel_requested_at 已置位）。
		String inputJson = "{\"path\":\"references/production/browser-capture.md\"}";
		String hash = com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge.sha256Hex(inputJson);
		db.sql("INSERT INTO hypit_job_action(id, job_id, step_index, kind, state, input_hash, input_json,"
				+ " result_json) VALUES (CAST(:a AS uuid), CAST(:j AS uuid), 0, 'tool', 'succeeded', :hash,"
				+ " CAST(:in AS jsonb), CAST('{\"tool\":\"knowledge.read\",\"document\":\"done\"}' AS jsonb))")
				.bind("a", UUID.randomUUID().toString()).bind("j", jobId.toString()).bind("hash", hash)
				.bind("in", "{\"tool\":\"knowledge.read\",\"input\":" + inputJson + "}").then()
				.block(Duration.ofSeconds(10));
		jobs.markCancelRequested(jobId).block(Duration.ofSeconds(10));

		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow job = awaitTerminal(jobId, "cancelled");
		assertThat(job).as("cancel 优先于迟到的 succeeded").isNotNull();
		assertThat(job.state()).isEqualTo("cancelled");
		assertThat(terminalEventStates(jobId)).as("terminal 序列唯一（不被 succeeded 覆盖成双终态）").containsExactly("cancelled");
		assertThat(jobActions.findByJob(jobId).next().block(Duration.ofSeconds(10)).state()).as("已完成的动作行不被回滚")
				.isEqualTo("succeeded");
	}

	// ── TC-F2-15-04：waiting_input 补料 resume 两次同 requestId ──
	@Test
	@DisplayName("TC-F2-15-04 resume×2 同 requestId：只恢复一次、blockedReason 解除、新规划轮、scope 不扩")
	void waitingInputResumeIsIdempotentAndStartsNewPlanningRound() throws Exception {
		stubPlannerContent(
				"{\"actions\":[{\"kind\":\"knowledge.search\",\"input\":{\"query\":\"asset\",\"limit\":3}}]}");
		UUID jobId = insertJob("running",
				"""
						{"intent":"analyze","brief":"分析素材","baseRevision":2,"inputs":{},
						 "scope":{"allowedTools":["knowledge.search","knowledge.read","build.status"]},
						 "schemaVersion":2,"round":1,"actionIndex":1,"actionSlot":1,"totalActions":1,"executedActions":1,
						 "fixRounds":2,"replans":0,"phase":"waiting_input","blockedReason":"缺资料：assetId 未提供",
						 "observations":[{"actionId":"%s","state":"failed","result":{"tool":"knowledge.read","code":"hypit_not_found"}}],
						 "actions":[]}
						"""
						.formatted(UUID.randomUUID()).replace("\n", " "),
				"缺资料：assetId 未提供");

		String requestId = UUID.randomUUID().toString();
		var resume = Map.of("requestId", requestId, "action", "resume", "input",
				Map.of("assetId", "11111111-1111-4111-8111-0000000000f1", "allowedTools", List.of("mutation.apply")));
		for (int i = 0; i < 2; i++) {
			client().post().uri("/api/hypit/jobs/" + jobId + "/actions")
					.header("X-Grassland-Identity", sign(OWNER, null)).contentType(MediaType.APPLICATION_JSON)
					.bodyValue(resume).exchange().expectStatus().isOk().expectBody().jsonPath("$.data.state")
					.isEqualTo("queued").jsonPath("$.data.accepted").isEqualTo("resume");
		}
		assertThat(countEvents(jobId, "checkpoint")).as("同 requestId 连点只恢复一次（resumed 事件 1 条）").isEqualTo(1);

		Map<String, Object> checkpoint = checkpointOf(jobId);
		assertThat(String.valueOf(checkpoint.get("blockedReason"))).as("具体 blockedReason 已解除").isEqualTo("null");
		assertThat(checkpoint.get("actions")).as("进入新规划轮次（actions 清空待规划）").isEqualTo(List.of());
		@SuppressWarnings("unchecked")
		Map<String, Object> scope = (Map<String, Object>) checkpoint.get("scope");
		@SuppressWarnings("unchecked")
		List<String> allowed = (List<String>) scope.get("allowedTools");
		assertThat(allowed).as("RULE-F04：resume 不扩 scope（mutation.apply 不进 allowedTools）")
				.containsExactly("knowledge.search", "knowledge.read", "build.status");
		@SuppressWarnings("unchecked")
		Map<String, Object> inputs = (Map<String, Object>) checkpoint.get("inputs");
		assertThat(String.valueOf(inputs.get("assetId"))).as("新输入已消费并入").isNotBlank();

		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow job = awaitTerminal(jobId, "succeeded");
		assertThat(job).as("消费新输入后新规划轮收口").isNotNull();
	}
}
