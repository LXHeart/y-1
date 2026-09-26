package com.grassland.intelligence.hypit.agent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

/**
 * Agent 任务面 API 全环（任务书 #107-fix-1 C107F-04 / W24 / TC-F04-01～05、08）：
 * POST/GET agent-jobs、POST jobs/{id}/actions（resume/cancel 状态机与幂等）、worker 崩溃恢复。
 *
 * <p>
 * LLM 执行环以 WireMock 桩（沿 SmokeControllerIT 手法：自种子平台 text 行指向 QWEN，CreditsClient
 * 打桩放行）；调度 worker 静默（agent-worker.enabled=false）——planner 场景下 @Scheduled 抢跑会在
 * stub 未配置时消费 job 并误判 planner_failed，测试一律直调 runOnce() 驱动确定性断言。
 * hypit_job_action 断言以 J 侧持久行为准（B sidecar 不在 IT 内，层属注明：工具执行面经真实
 * dispatcher，knowledge 检索走真实索引）。
 */
@TestPropertySource(properties = {"hypit.agent-worker.enabled=false",
		"hypit.operator-account-ids=ffffffff-0000-4000-8000-00000000000f"})
class HypitAgentJobIT extends IntelligenceItSupport {

	private static final String OWNER = "dddddddd-0000-4000-8000-00000000000d";
	private static final String OTHER = "eeeeeeee-0000-4000-8000-00000000000e";
	private static final String OPERATOR = "ffffffff-0000-4000-8000-00000000000f";

	private static final String PLAN_JSON = """
			{"actions":[
			  {"kind":"knowledge.search","input":{"query":"render","limit":5}},
			  {"kind":"knowledge.read","input":{"path":"references/production/browser-capture.md"}}
			]}
			""";

	@MockitoBean
	private CreditsClient credits;

	@Autowired
	HypitAgentWorker worker;

	@Autowired
	HypitJobRepository jobs;

	@Autowired
	HypitJobActionRepository jobActions;

	@Autowired
	DatabaseClient db;

	private UUID projectId;

	private UUID assetId;

	@BeforeEach
	void seed() {
		reset(credits);
		CreditsStubs.stubDefaults(credits);
		QWEN.resetAll();
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'agent-job-it', 'clone', 'ready', 2)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
		assetId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_asset(id, project_id, resource_handle, role, origin_kind, sha256, mime_type,"
				+ " size_bytes, status) VALUES (CAST(:id AS uuid), CAST(:p AS uuid), 'it://reference.mp4',"
				+ " 'reference', 'upload', :sha, 'video/mp4', 1024, 'ready')")
				.bind("id", assetId.toString()).bind("p", projectId.toString()).bind("sha", "a".repeat(64)).then()
				.block(Duration.ofSeconds(10));
		seedPlatformTextRow();
	}

	@org.junit.jupiter.api.AfterEach
	void sweep() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_job_action WHERE job_id IN"
				+ " (SELECT id FROM hypit_job WHERE account_id IN (:o, :x, :f))")
				.bind("o", OWNER).bind("x", OTHER).bind("f", OPERATOR).then()
				.then(db.sql("DELETE FROM hypit_job_event WHERE job_id IN"
						+ " (SELECT id FROM hypit_job WHERE account_id IN (:o, :x, :f))")
						.bind("o", OWNER).bind("x", OTHER).bind("f", OPERATOR).then())
				.then(db.sql("DELETE FROM hypit_job WHERE account_id IN (:o, :x, :f)")
						.bind("o", OWNER).bind("x", OTHER).bind("f", OPERATOR).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id IN (:o, :x, :f)")
						.bind("o", OWNER).bind("x", OTHER).bind("f", OPERATOR).then())
				.then(db.sql("DELETE FROM hypit_asset WHERE project_id = CAST(:p AS uuid)")
						.bind("p", projectId == null ? UUID.randomUUID().toString() : projectId.toString()).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id IN (:o, :x, :f)")
						.bind("o", OWNER).bind("x", OTHER).bind("f", OPERATOR).then())
				.block(Duration.ofSeconds(20));
	}

	/**
	 * 平台 text/primary 行指向 QWEN 且挂带密凭据（任务书 #58 决策 E）。先按目的地自清（跨类隔离），
	 * 再交给基类 attachPlatformTextCredential 建凭据并建行/补挂。
	 */
	private void seedPlatformTextRow() {
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
				+ " (SELECT id FROM platform_model_config WHERE base_url = :baseUrl)")
				.bind("baseUrl", QWEN.baseUrl()).then()
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
						.withBody("{\"choices\":[{\"message\":{\"content\":" + jsonString(content) + "}}],"
								+ "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")));
	}

	private static String jsonString(String value) {
		return com.fasterxml.jackson.databind.json.JsonMapper.builder().build().valueToTree(value).toString();
	}

	private org.springframework.test.web.reactive.server.WebTestClient.BodyContentSpec postAgentJob(Object body,
			String account) {
		return client().post().uri("/api/hypit/projects/" + projectId + "/agent-jobs")
				.header("X-Grassland-Identity", sign(account, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectBody();
	}

	private record Created(String jobId, String state) {
	}

	/** 请求体构造（scope 可为 null——API-F04 契约形态，Map.of 不收 null 值）。 */
	private Map<String, Object> jobBody(String requestId, String intent, String brief, List<UUID> assets,
			Long baseRevision, Map<String, Object> scope) {
		Map<String, Object> body = new java.util.LinkedHashMap<>();
		body.put("requestId", requestId);
		body.put("intent", intent);
		body.put("brief", brief);
		body.put("assetIds", assets);
		body.put("baseRevision", baseRevision == null ? 1 : baseRevision);
		body.put("scope", scope);
		return body;
	}

	private Created createAndRead(String requestId, String intent, String brief, List<UUID> assets, Long baseRevision,
			Map<String, Object> scope) {
		byte[] raw = client().post().uri("/api/hypit/projects/" + projectId + "/agent-jobs")
				.header("X-Grassland-Identity", sign(OWNER, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(jobBody(requestId, intent, brief, assets, baseRevision, scope))
				.exchange().expectStatus().isAccepted().expectBody().jsonPath("$.success").isEqualTo(true)
				.jsonPath("$.data.jobId").exists().jsonPath("$.data.state").isEqualTo("queued").returnResult()
				.getResponseBody();
		Map<String, Object> parsed = com.grassland.intelligence.hypit.project.HypitJson
				.read(new String(raw, java.nio.charset.StandardCharsets.UTF_8));
		@SuppressWarnings("unchecked")
		Map<String, Object> data = (Map<String, Object>) parsed.get("data");
		return new Created((String) data.get("jobId"), (String) data.get("state"));
	}

	private JobRow awaitTerminal(UUID jobId, String state) throws InterruptedException {
		JobRow job = null;
		for (int i = 0; i < 40; i++) {
			job = jobs.findById(jobId).block(Duration.ofSeconds(10));
			if (job != null && state.equals(job.state())) {
				return job;
			}
			Thread.sleep(250);
		}
		return job;
	}

	/** checkpoint 相位序列（AC-F04-01 事件序断言面；terminal 单独断言）。 */
	private List<String> eventTypes(UUID jobId) {
		return db.sql("SELECT payload->>'phase' AS phase FROM hypit_job_event WHERE job_id = CAST(:j AS uuid)"
				+ " AND type = 'checkpoint' ORDER BY sequence").bind("j", jobId.toString())
				.map((row, meta) -> row.get("phase", String.class)).all().collectList()
				.block(Duration.ofSeconds(10));
	}

	private List<String> terminalEventPayloads(UUID jobId) {
		return db.sql("SELECT payload->>'state' AS state FROM hypit_job_event"
				+ " WHERE job_id = CAST(:j AS uuid) AND type = 'terminal' ORDER BY sequence")
				.bind("j", jobId.toString()).map((row, meta) -> row.get("state", String.class)).all().collectList()
				.block(Duration.ofSeconds(10));
	}

	private int countEvents(UUID jobId, String type) {
		Long count = db.sql("SELECT COUNT(*) AS n FROM hypit_job_event WHERE job_id = CAST(:j AS uuid)"
				+ " AND type = CAST(:t AS text)").bind("j", jobId.toString()).bind("t", type)
				.map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		return count == null ? 0 : count.intValue();
	}

	private long countCommands() {
		Long count = db.sql("SELECT COUNT(*) AS n FROM hypit_command WHERE account_id = :o").bind("o", OWNER)
				.map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	/** 直插 hypit_job（老式直驱形态：无 command、checkpoint 预置）。 */
	private UUID insertJob(String state, String checkpointJson) {
		UUID jobId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_job(id, account_id, project_id, kind, state, checkpoint_json)"
				+ " VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 'hypit.agent', :state, CAST(:cp AS jsonb))")
				.bind("id", jobId.toString()).bind("o", OWNER).bind("p", projectId.toString()).bind("state", state)
				.bind("cp", checkpointJson).then().block(Duration.ofSeconds(10));
		return jobId;
	}

	private Map<String, Object> checkpointOf(UUID jobId) {
		return db.sql("SELECT checkpoint_json::text AS cp FROM hypit_job WHERE id = CAST(:j AS uuid)")
				.bind("j", jobId.toString()).map((row, meta) -> row.get("cp", String.class)).one()
				.map(com.grassland.intelligence.hypit.project.HypitJson::read).block(Duration.ofSeconds(10));
	}

	// ------------------------------------------------------------------
	// TC-F04-01：agent-jobs 全环（桩 LLM）
	// ------------------------------------------------------------------

	@Test
	void plannerFullLoopRunsActionsAndSucceeds() throws InterruptedException {
		stubPlannerContent(PLAN_JSON);
		Created created = createAndRead("11111111-1111-4111-8111-000000000001", "analyze",
				"检索渲染知识并读浏览器采集文档", List.of(assetId), 2L, null);
		UUID jobId = UUID.fromString(created.jobId());

		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow job = awaitTerminal(jobId, "succeeded");
		assertThat(job).as("job 应收口 succeeded").isNotNull();

		// 事件序（AC-F04-01）：queued → planning → executing →（terminal: succeeded）
		assertThat(eventTypes(jobId)).containsExactly("queued", "planning", "executing");
		assertThat(terminalEventPayloads(jobId)).containsExactly("succeeded");

		// 动作行两步齐（J 侧持久面；knowledge 检索走真实索引）
		List<HypitJobActionRepository.ActionRow> actions = jobActions.findByJob(jobId).collectList()
				.block(Duration.ofSeconds(10));
		assertThat(actions).hasSize(2);
		assertThat(actions.get(0).state()).isEqualTo("succeeded");
		assertThat(actions.get(0).resultJson()).contains("hits");
		assertThat(actions.get(1).state()).isEqualTo("succeeded");

		// ai_run 绑定（planner 经执行环记账，不旁路）
		Map<String, Object> checkpoint = checkpointOf(jobId);
		assertThat(String.valueOf(checkpoint.get("plannerRunId"))).isNotEqualTo("null");
		UUID runId = UUID.fromString(String.valueOf(checkpoint.get("plannerRunId")));
		Long runs = db.sql("SELECT COUNT(*) AS n FROM ai_run WHERE id = CAST(:r AS uuid)").bind("r", runId.toString())
				.map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(runs).isEqualTo(1);

		// GET 列表见该 job（intent/stepIndex/maxSteps 契约字段）
		client().get().uri("/api/hypit/projects/" + projectId + "/agent-jobs?limit=20")
				.header("X-Grassland-Identity", sign(OWNER, null)).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.success").isEqualTo(true).jsonPath("$.data.items[0].jobId")
				.isEqualTo(created.jobId()).jsonPath("$.data.items[0].intent").isEqualTo("analyze")
				.jsonPath("$.data.items[0].state").isEqualTo("succeeded").jsonPath("$.data.items[0].maxSteps")
				.isEqualTo(40).jsonPath("$.data.nextCursor").isEqualTo(null);
	}

	@Test
	void createValidatesInputAndStaysZeroSideEffect() {
		// intent 非法 → 400 且零 command（RULE-F01）
		client().post().uri("/api/hypit/projects/" + projectId + "/agent-jobs")
				.header("X-Grassland-Identity", sign(OWNER, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(jobBody("11111111-1111-4111-8111-0000000000a1", "hack", "x", List.of(), 1L, null))
				.exchange().expectStatus().isBadRequest().expectBody().jsonPath("$.success").isEqualTo(false);
		assertThat(countCommands()).as("intent 非法时零 command（RULE-F01）").isZero();

		// brief 空 → 400
		client().post().uri("/api/hypit/projects/" + projectId + "/agent-jobs")
				.header("X-Grassland-Identity", sign(OWNER, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(jobBody("11111111-1111-4111-8111-0000000000a2", "analyze", "  ", List.of(), 1L, null))
				.exchange().expectStatus().isBadRequest();
		// 跨工程素材 → 400
		client().post().uri("/api/hypit/projects/" + projectId + "/agent-jobs")
				.header("X-Grassland-Identity", sign(OWNER, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(jobBody("11111111-1111-4111-8111-0000000000a3", "analyze", "ok",
						List.of(UUID.randomUUID()), 1L, null))
				.exchange().expectStatus().isBadRequest();
		assertThat(countCommands()).as("全部负向零 command").isZero();

		// 他人工程 → 404（requireProjectOwner 既有语义）
		client().post().uri("/api/hypit/projects/" + projectId + "/agent-jobs")
				.header("X-Grassland-Identity", sign(OTHER, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(jobBody("11111111-1111-4111-8111-0000000000a4", "analyze", "ok", List.of(), 1L, null))
				.exchange().expectStatus().isNotFound();

		// 空列表契约（E08）
		client().get().uri("/api/hypit/projects/" + projectId + "/agent-jobs")
				.header("X-Grassland-Identity", sign(OWNER, null)).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.items").isArray().jsonPath("$.data.items.length()").isEqualTo(0)
				.jsonPath("$.data.nextCursor").isEqualTo(null);
	}

	@Test
	void createIsIdempotentByRequestId() {
		stubPlannerContent(PLAN_JSON);
		Created first = createAndRead("11111111-1111-4111-8111-000000000002", "analyze", "幂等回读",
				List.of(assetId), 2L, null);
		Created replay = createAndRead("11111111-1111-4111-8111-000000000002", "analyze", "幂等回读",
				List.of(assetId), 2L, null);
		assertThat(replay.jobId()).as("同 requestId 幂等回读同 job").isEqualTo(first.jobId());
		Long jobCount = db.sql("SELECT COUNT(*) AS n FROM hypit_job WHERE account_id = :o").bind("o", OWNER)
				.map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(jobCount).isEqualTo(1);

		// 同 requestId 异 payload → 409 hypit_idempotency_conflict
		client().post().uri("/api/hypit/projects/" + projectId + "/agent-jobs")
				.header("X-Grassland-Identity", sign(OWNER, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(jobBody("11111111-1111-4111-8111-000000000002", "analyze", "不同内容", List.of(), 1L,
						null))
				.exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code")
				.isEqualTo("hypit_idempotency_conflict");
	}

	// ------------------------------------------------------------------
	// TC-F04-02：planner 坏输出两次失败
	// ------------------------------------------------------------------

	@Test
	void plannerBadOutputTwiceFailsWithEvidence() throws InterruptedException {
		stubPlannerContent("抱歉，我无法输出 JSON 计划。");
		Created created = createAndRead("11111111-1111-4111-8111-000000000003", "analyze", "坏输出路径",
				List.of(), 2L, null);
		UUID jobId = UUID.fromString(created.jobId());

		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow job = awaitTerminal(jobId, "failed");
		assertThat(job).as("两次坏输出应 failed").isNotNull();
		assertThat(job.errorCode()).isEqualTo("hypit_planner_failed");

		Map<String, Object> checkpoint = checkpointOf(jobId);
		@SuppressWarnings("unchecked")
		List<String> outputs = (List<String>) checkpoint.get("plannerOutputs");
		assertThat(outputs).as("两次原始输出留证").hasSize(2);
		assertThat(outputs.get(0)).contains("无法输出").contains("JSON");
		assertThat(outputs.get(1)).contains("无法输出").contains("JSON");

		List<HypitJobActionRepository.ActionRow> actions = jobActions.findByJob(jobId).collectList()
				.block(Duration.ofSeconds(10));
		assertThat(actions).as("坏计划不执行任何 action").isEmpty();
	}

	// ------------------------------------------------------------------
	// TC-F04-03：scope 收敛（RULE-F03）
	// ------------------------------------------------------------------

	@Test
	void scopeNarrowingDropsUnauthorizedToolsAndRecordsDiagnosticEvent() {
		stubPlannerContent(PLAN_JSON);
		Created created = createAndRead("11111111-1111-4111-8111-000000000004", "analyze", "收敛",
				List.of(), 2L, Map.of("allowedTools", List.of("knowledge.search", "build.submit")));
		UUID jobId = UUID.fromString(created.jobId());

		// 静默剔除实现必红：diagnostic 事件必须留下 scope_narrowed 记录
		assertThat(countEvents(jobId, "diagnostic")).isEqualTo(1);
		Map<String, Object> checkpoint = checkpointOf(jobId);
		@SuppressWarnings("unchecked")
		Map<String, Object> scope = (Map<String, Object>) checkpoint.get("scope");
		@SuppressWarnings("unchecked")
		List<String> allowed = (List<String>) scope.get("allowedTools");
		assertThat(new LinkedHashSet<>(allowed)).as("越权工具被剔除（analyze 无 build.submit）")
				.containsExactly("knowledge.search");

		// 全部声明越权 → 保留 intent 最小只读集（不落零工具假集）
		Created fallback = createAndRead("11111111-1111-4111-8111-000000000005", "analyze", "全错",
				List.of(), 2L, Map.of("allowedTools", List.of("build.submit", "mutation.apply")));
		Map<String, Object> fallbackCp = checkpointOf(UUID.fromString(fallback.jobId()));
		@SuppressWarnings("unchecked")
		Map<String, Object> fallbackScope = (Map<String, Object>) fallbackCp.get("scope");
		@SuppressWarnings("unchecked")
		List<String> fallbackAllowed = (List<String>) fallbackScope.get("allowedTools");
		assertThat(new LinkedHashSet<>(fallbackAllowed)).containsExactlyInAnyOrder("knowledge.search",
				"knowledge.read", "build.status");
	}

	// ------------------------------------------------------------------
	// TC-F04-04：resume 续跑与不扩张（含连点）
	// ------------------------------------------------------------------

	@Test
	void resumeMergesInputWithoutScopeWideningAndIsIdempotent() throws InterruptedException {
		String scopeJson = "{\"allowedTools\":[\"knowledge.search\",\"knowledge.read\",\"build.status\"]}";
		UUID jobId = insertJob("running", """
				{"stepIndex":0,"phase":"waiting_input","scope":%s,"intent":"analyze","brief":"续跑",
				 "inputs":{},"actions":[
				  {"kind":"knowledge.search","input":{"query":"render","limit":3}},
				  {"kind":"knowledge.read","input":{"path":"references/production/browser-capture.md"}}]}
				""".formatted(scopeJson).replace("\n", " "));
		List<String> before = allowedToolsOf(jobId);

		// resume 连点（同 requestId）+ input 携带扩张项（E-d2）：扩张只进 inputs，scope 原集不变
		Map<String, Object> resumeBody = Map.of("requestId", "22222222-2222-4222-8222-000000000001", "action",
				"resume", "input", Map.of("allowedTools", List.of("build.submit"), "note", "改用 en 字幕"));
		var first = client().post().uri("/api/hypit/jobs/" + jobId + "/actions")
				.header("X-Grassland-Identity", sign(OWNER, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(resumeBody).exchange().expectStatus().isOk().expectBody();
		first.jsonPath("$.data.state").isEqualTo("queued").jsonPath("$.data.accepted").isEqualTo("resume");
		client().post().uri("/api/hypit/jobs/" + jobId + "/actions").header("X-Grassland-Identity", sign(OWNER, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(resumeBody).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.state").isEqualTo("queued").jsonPath("$.data.accepted")
				.isEqualTo("resume");

		assertThat(countEvents(jobId, "checkpoint")).as("连点只并入一次（resumed 相位事件仅 1 条）").isEqualTo(1);
		Map<String, Object> checkpoint = checkpointOf(jobId);
		@SuppressWarnings("unchecked")
		Map<String, Object> inputs = (Map<String, Object>) checkpoint.get("inputs");
		assertThat(String.valueOf(inputs.get("note"))).isEqualTo("改用 en 字幕");
		assertThat(new LinkedHashSet<>(allowedToolsOf(jobId))).as("RULE-F04：resume 后 allowedTools 原集不变")
				.containsExactlyInAnyOrderElementsOf(before);

		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow job = awaitTerminal(jobId, "succeeded");
		assertThat(job).as("resume 后续跑收口 succeeded").isNotNull();
		List<HypitJobActionRepository.ActionRow> actions = jobActions.findByJob(jobId).collectList()
				.block(Duration.ofSeconds(10));
		assertThat(actions).hasSize(2);
	}

	private List<String> allowedToolsOf(UUID jobId) {
		Map<String, Object> checkpoint = checkpointOf(jobId);
		Object scope = checkpoint.get("scope");
		List<String> tools = new ArrayList<>();
		if (scope instanceof Map<?, ?> map && map.get("allowedTools") instanceof List<?> list) {
			list.forEach(tool -> tools.add(String.valueOf(tool)));
		}
		return tools;
	}

	// ------------------------------------------------------------------
	// TC-F04-05：cancel 幂等 / 终态 409 / 越权 403
	// ------------------------------------------------------------------

	@Test
	void cancelIdempotentTerminalGuardsAndForbiddenCallers() {
		UUID jobId = insertJob("running",
				"{\"stepIndex\":0,\"scope\":\"read_only\",\"actions\":[{\"kind\":\"knowledge.search\",\"input\":{\"query\":\"x\"}}]}");

		// 非本人非 operator（全局路径）→ 403（§5.4 冻结）
		client().post().uri("/api/hypit/jobs/" + jobId + "/actions").header("X-Grassland-Identity", sign(OTHER, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", "33333333-3333-4333-8333-000000000001", "action", "cancel"))
				.exchange().expectStatus().isForbidden();

		// 本人 cancel → 200 cancelled
		Map<String, Object> cancelBody = Map.of("requestId", "33333333-3333-4333-8333-000000000002", "action",
				"cancel");
		client().post().uri("/api/hypit/jobs/" + jobId + "/actions").header("X-Grassland-Identity", sign(OWNER, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(cancelBody).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.state").isEqualTo("cancelled").jsonPath("$.data.accepted")
				.isEqualTo("cancel");
		// 同 requestId 连点 → 200 同态；不同 requestId 再 cancel → 200 幂等，terminal 事件仍只 1 条
		client().post().uri("/api/hypit/jobs/" + jobId + "/actions").header("X-Grassland-Identity", sign(OWNER, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(cancelBody).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.state").isEqualTo("cancelled");
		client().post().uri("/api/hypit/jobs/" + jobId + "/actions").header("X-Grassland-Identity", sign(OWNER, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", "33333333-3333-4333-8333-000000000003", "action", "cancel"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.state").isEqualTo("cancelled");
		assertThat(terminalEventPayloads(jobId)).as("重复 cancel 零新副作用（E-e 事件计数）").containsExactly("cancelled");

		// 终态 resume → 409；succeeded 上 cancel → 409（canceled 幂等例外已如上）
		client().post().uri("/api/hypit/jobs/" + jobId + "/actions").header("X-Grassland-Identity", sign(OWNER, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", "33333333-3333-4333-8333-000000000004", "action", "resume", "input",
						Map.of()))
				.exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code")
				.isEqualTo("hypit_state_conflict");

		UUID done = insertJob("succeeded", "{\"stepIndex\":1,\"scope\":\"read_only\",\"actions\":[]}");
		client().post().uri("/api/hypit/jobs/" + done + "/actions").header("X-Grassland-Identity", sign(OWNER, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", "33333333-3333-4333-8333-000000000005", "action", "cancel"))
				.exchange().expectStatus().isEqualTo(409).expectBody().jsonPath("$.code")
				.isEqualTo("hypit_state_conflict");

		// operator 可对任意任务 cancel（全局路径 operator 面）
		UUID opTarget = insertJob("queued", "{\"stepIndex\":0,\"scope\":\"read_only\",\"actions\":[]}");
		client().post().uri("/api/hypit/jobs/" + opTarget + "/actions")
				.header("X-Grassland-Identity", sign(OPERATOR, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("requestId", "33333333-3333-4333-8333-000000000006", "action", "cancel"))
				.exchange().expectStatus().isOk().expectBody().jsonPath("$.data.state").isEqualTo("cancelled");
	}

	// ------------------------------------------------------------------
	// TC-F04-08：worker 崩溃恢复（租约过期重领，已确认动作不重发）
	// ------------------------------------------------------------------

	@Test
	void workerRequeuesExpiredLeaseAndDoesNotReplayConfirmedActions() throws InterruptedException {
		UUID jobId = UUID.randomUUID();
		db.sql("""
				INSERT INTO hypit_job(id, account_id, project_id, kind, state, lease_owner, lease_until, checkpoint_json)
				VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 'hypit.agent', 'running',
				        CAST(:owner AS uuid), now() - interval '1 minute', CAST(:cp AS jsonb))
				""")
				.bind("id", jobId.toString()).bind("o", OWNER).bind("p", projectId.toString())
				.bind("owner", UUID.randomUUID().toString()).bind("cp", """
						{"stepIndex":0,"scope":"read_only","actions":[
						  {"kind":"knowledge.search","input":{"query":"render","limit":5}},
						  {"kind":"knowledge.read","input":{"path":"references/production/browser-capture.md"}}]}
						""".replace("\n", " ")).then().block(Duration.ofSeconds(10));
		// 崩溃前已确认的第一步（step 槽 0 已有行）
		db.sql("INSERT INTO hypit_job_action(id, job_id, step_index, kind, state, input_hash, result_json)"
				+ " VALUES (CAST(:a AS uuid), CAST(:j AS uuid), 0, 'tool', 'succeeded', :hash,"
				+ " CAST('{\"tool\":\"knowledge.search\",\"hits\":[]}' AS jsonb))")
				.bind("a", UUID.randomUUID().toString()).bind("j", jobId.toString()).bind("hash", "b".repeat(64))
				.then().block(Duration.ofSeconds(10));

		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow job = awaitTerminal(jobId, "succeeded");
		assertThat(job).as("租约过期重领后续跑收口").isNotNull();

		List<HypitJobActionRepository.ActionRow> actions = jobActions.findByJob(jobId).collectList()
				.block(Duration.ofSeconds(10));
		// 已确认动作不重发：step 0 仍是崩溃前那行（input_hash 原值），只新增 step 1
		assertThat(actions).as("行数不增（重放幂等）").hasSize(2);
		assertThat(actions.get(0).inputHash()).isEqualTo("b".repeat(64));
		assertThat(actions.get(1).stepIndex()).isEqualTo(1);
	}
}
