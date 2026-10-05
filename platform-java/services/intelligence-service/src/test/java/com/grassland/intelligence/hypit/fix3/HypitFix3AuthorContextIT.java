package com.grassland.intelligence.hypit.fix3;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.hypit.agent.HypitAgentWorker;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import java.time.Duration;
import java.util.LinkedHashMap;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * C107F3-10（任务书 107-fix-3 §12.2 TC-F3-10-01～03 / RULE-010～013 / §6.4
 * MOD-002）：可信再生成 快照与前后端恢复。
 *
 * <p>
 * 真 PostgreSQL + 真实 HTTP（WebTestClient + 身份签名，不 mock
 * Controller/JobService/Worker）； planner 走 QWEN 桩（沿 C14IT 手法：平台 text 行指向
 * QWEN、CreditsClient 放行、worker 调度静默、 直调 runOnce 确定性驱动）；mutation.apply 的 sidecar
 * 命令（workspace.read/check/apply）用 WireMock 固定回执——仅替换引擎边界，PG/命令/快照/方案持久全部真实。
 *
 * <ul>
 * <li>TC-F3-10-01 来源冻结与完整落地：API-002 受理 202 → checkpoint 冻结 version1 快照（source
 * 一致） → worker 消费（作者请求实际含分析）→ validated 写回 → clone.plan 稳定 ID
 * 持久（来源/resultRevision） → succeeded；新事务重读一致；202 受理态不是成功态。</li>
 * <li>TC-F3-10-02 幂等及旧客户端兼容：插 Y 后重放原请求同 job 同 X 快照零模型调用（重放判定先于 latest 选择）；同 ID
 * 异 body 409；旧客户端省略/null/false 三形态走旧路径、checkpoint 无新键。</li>
 * <li>TC-F3-10-03 冲突、重启与部分成功：缺分析/非 SUCCEEDED/素材非 ready/sha 不符/head 冲突 409 且
 * job/command/模型零副作用；快照 256KiB 边界恰收/+1 拒；作者已写回但 plan 未持久的崩溃 job 恢复只补 plan
 * 一次（幂等不增行）；CAS 竞争不覆盖他人 head；取消不新增 build。</li>
 * </ul>
 *
 * <p>
 * 防假阳性（§12.2）：先重新选 latest 再判幂等的实现会在 TC-F3-10-02「插 Y 重放」步 409 假冲突；只检查
 * 202/按钮禁用而无服务端快照/CAS/fencing 的实现分别被 01/03 拒绝步抓出。
 */
@TestPropertySource(properties = {"hypit.enabled=true", "hypit.agent-worker.enabled=false"})
class HypitFix3AuthorContextIT extends IntelligenceItSupport {

	/** §12.2 共享前提 F-OWN：A 固定测试 UUID。 */
	private static final String OWNER_A = "aaaaaaaa-1073-4000-8000-000000000001";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	@MockitoBean
	private CreditsClient credits;

	@Autowired
	HypitAgentWorker worker;

	@Autowired
	HypitJobRepository jobs;

	@Autowired
	DatabaseClient db;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> "fix3-c10-internal-token-0123456789abcdef");
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
	}

	@org.junit.jupiter.api.BeforeAll
	static void startSidecar() {
		SIDECAR.start();
	}

	@org.junit.jupiter.api.AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@BeforeEach
	void seed() {
		reset(credits);
		CreditsStubs.stubDefaults(credits);
		QWEN.resetAll();
		SIDECAR.resetAll();
		cleanup();
		stubSidecarWorkspace();
		attachPlatformTextRow();
	}

	@AfterEach
	void sweep() {
		disposeProviderTextRow();
		cleanup();
	}

	/** 每例独立数据命名空间：A owner 全链自清（含 system 账户的 reference.analyze/clone.plan 行）。 */
	private void cleanup() {
		db.sql("DELETE FROM hypit_job_action WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
				.bind("o", OWNER_A).then()
				.then(db.sql(
						"DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
						.bind("o", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id IN (:o, 'system')").bind("o", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_changeset WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_asset WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_build WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER_A).then())
				.block(Duration.ofSeconds(20));
	}

	/** 平台 text 行指向 QWEN（planner 走执行环）；按目的地自清后建行（EvidenceIT 同款）。 */
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

	/** 撤 QWEN text 行：NOT EXISTS 守卫要求无其它启用 text 配置，供共享容器的后续 IT 重挂。 */
	private void disposeProviderTextRow() {
		db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN"
				+ " (SELECT id FROM platform_model_config WHERE base_url = :baseUrl)").bind("baseUrl", QWEN.baseUrl())
				.then()
				.then(db.sql("DELETE FROM platform_model_config WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.then(db.sql("DELETE FROM platform_provider_credential WHERE base_url = :baseUrl")
						.bind("baseUrl", QWEN.baseUrl()).then())
				.block(Duration.ofSeconds(10));
	}

	// ── sidecar workspace 桩（mutation.apply 链） ────────────────────────────

	private void stubSidecarWorkspace() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.read"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"x\",\"state\":\"succeeded\",\"result\":{\"hash\":\""
								+ "a".repeat(64) + "\"}}")));
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.check"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"x\",\"state\":\"succeeded\",\"result\":{\"ok\":true}}")));
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.apply"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
						"{\"commandId\":\"x\",\"state\":\"succeeded\",\"result\":{\"revision\":3,\"manifestHash\":\""
								+ "m".repeat(64) + "\",\"appliedPaths\":[\"main.svml\"]}}")));
	}

	/** planner 桩：新模式请求含「参考分析上下文」时回 validated 写回计划（作者真实产物面）。 */
	private void stubPlannerMutation() {
		String plan = "{\"actions\":[{\"kind\":\"mutation.apply\",\"input\":{\"applyMode\":\"validated\","
				+ "\"changes\":[{\"path\":\"main.svml\",\"action\":\"put\",\"content\":\"<svml/>\"}]}}]}";
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).withRequestBody(containing("执行规划器"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"choices\":[{\"message\":{\"content\":" + JSON.valueToTree(plan).toString()
								+ "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")));
	}

	// ── 种子 ────────────────────────────────────────────────────────────────

	private UUID seedReadyProject(int revision) {
		UUID projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'fix3-c10', 'clone', 'ready', :rev)")
				.bind("id", projectId.toString()).bind("owner", OWNER_A).bind("ws", UUID.randomUUID().toString())
				.bind("rev", revision).then().block(Duration.ofSeconds(10));
		return projectId;
	}

	private UUID seedAsset(UUID projectId, String sha, String status) {
		UUID assetId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_asset(id, project_id, media_id, resource_handle, role, origin_kind,"
				+ " sha256, mime_type, size_bytes, probe, status) VALUES (CAST(:id AS uuid), CAST(:p AS uuid),"
				+ " CAST(:m AS uuid), :handle, 'reference', 'upload', :sha, 'video/mp4', 8, NULL, :status)")
				.bind("id", assetId.toString()).bind("p", projectId.toString()).bind("m", UUID.randomUUID().toString())
				.bind("handle", "res-fix3c10-" + assetId.toString().substring(0, 8)).bind("sha", sha)
				.bind("status", status).then().block(Duration.ofSeconds(10));
		return assetId;
	}

	/**
	 * 真实分析 job（intent=analyze、checkpoint 携带 referenceAnalysisId 引用；newerBySeconds
	 * 控制 updated_at 先后）。
	 */
	private UUID seedAnalysisJob(UUID projectId, String analysisId, String mediaHash, long newerBySeconds) {
		UUID jobId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_job(id, account_id, project_id, kind, state, checkpoint_json)"
				+ " VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 'hypit.agent', 'succeeded', CAST(:cp AS jsonb))")
				.bind("id", jobId.toString()).bind("o", OWNER_A).bind("p", projectId.toString())
				.bind("cp",
						"{\"intent\":\"analyze\",\"referenceAnalysisId\":\"" + analysisId
								+ "\",\"referenceMediaHash\":\"" + mediaHash + "\"}")
				.then().block(Duration.ofSeconds(10));
		db.sql("UPDATE hypit_job SET updated_at = now() + make_interval(secs => :d) WHERE id = CAST(:id AS uuid)")
				.bind("d", newerBySeconds).bind("id", jobId.toString()).then().block(Duration.ofSeconds(10));
		return jobId;
	}

	/** reference.analyze 命令 result（SUCCEEDED 全覆盖分析；padding 控制快照体积）。 */
	private void seedAnalysisCommand(String analysisId, String mediaHash, String status, UUID assetId, String padding) {
		Map<String, Object> result = analysisResult(analysisId, mediaHash, status, assetId, padding);
		String resultJson = com.grassland.intelligence.hypit.project.HypitJson.write(result);
		String payload = com.grassland.intelligence.hypit.project.HypitJson
				.write(Map.of("mediaHash", mediaHash, "operationId", "ref-" + analysisId));
		String hash = com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge.sha256Hex(payload);
		db.sql("INSERT INTO hypit_command(id, account_id, project_id, target_key, action, request_id, payload_hash,"
				+ " payload_json, state, result_json) VALUES (CAST(:id AS uuid), 'system', CAST(:p AS uuid),"
				+ " :target, 'reference.analyze', CAST(:r AS uuid), :h, CAST(:payload AS jsonb), 'succeeded',"
				+ " CAST(:result AS jsonb))").bind("id", UUID.randomUUID().toString())
				.bind("target", "analysis:" + mediaHash)
				.bind("r",
						UUID.nameUUIDFromBytes(("ref-" + analysisId).getBytes(java.nio.charset.StandardCharsets.UTF_8))
								.toString())
				.bind("h", hash).bind("payload", payload).bind("result", resultJson).bindNull("p", String.class).then()
				.block(Duration.ofSeconds(10));
	}

	/** 全覆盖 SUCCEEDED 分析 result（fromMap 可重建；segments 带 evidence 锚点）。 */
	static Map<String, Object> analysisResult(String analysisId, String mediaHash, String status, UUID assetId,
			String padding) {
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("analysisId", analysisId);
		result.put("mediaHash", mediaHash);
		result.put("durationSeconds", 12.0);
		result.put("language", "zh");
		result.put("aspectRatio", "4:3");
		result.put("audioTrack", "PRESENT");
		result.put("status", status);
		result.put("segments", List.of(Map.of("index", 0, "startSeconds", 0.0, "endSeconds", 12.0, "summary", "全片主色序列",
				"evidence",
				List.of(Map.of("assetId", String.valueOf(assetId), "sourceTimeSeconds", 1.0, "note", "frame@1s")))));
		result.put("systems", List.of(Map.of("systemId", "sys-red", "kind", "visual", "name", "红墙", "firstSeenSeconds",
				0.0, "lastSeenSeconds", 12.0, "segmentIndexes", List.of("0"))));
		result.put("events", List.of());
		result.put("gaps", List.of());
		result.put("openQuestions", padding == null ? List.of() : List.of(padding));
		return result;
	}

	// ── HTTP 面 ─────────────────────────────────────────────────────────────

	private record Accepted(int status, String jobId, String state, String code) {
	}

	/** API-002 提交；regenerate=null 表示省略字段（旧客户端形态一）。 */
	private Accepted postAgentJob(UUID projectId, UUID requestId, String brief, Long baseRevision, Boolean regenerate,
			List<String> assetIds) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", requestId.toString());
		body.put("intent", "author");
		body.put("brief", brief);
		body.put("assetIds", assetIds);
		body.put("baseRevision", baseRevision);
		if (regenerate != null) {
			body.put("regenerateFromLatestAnalysis", regenerate);
		}
		byte[] raw = client().post().uri("/api/hypit/projects/{p}/agent-jobs", projectId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(com.grassland.intelligence.hypit.project.HypitJson.write(body)).exchange().expectStatus()
				.isAccepted().expectBody().returnResult().getResponseBody();
		try {
			Map<String, Object> envelope = JSON.readValue(raw, Map.class);
			Map<String, Object> data = (Map<String, Object>) envelope.get("data");
			return new Accepted(202, String.valueOf(data.get("jobId")), String.valueOf(data.get("state")), null);
		} catch (Exception error) {
			throw new IllegalStateException("bad envelope", error);
		}
	}

	/** 期望非 2xx 的提交（409/400），返回错误码。 */
	private String postAgentJobExpectError(UUID projectId, UUID requestId, String brief, Long baseRevision,
			int expectedStatus) {
		return postAgentJobExpectCode(projectId, requestId, brief, baseRevision, true, expectedStatus);
	}

	private String postAgentJobExpectCode(UUID projectId, UUID requestId, String brief, Long baseRevision,
			boolean regenerate, int expectedStatus) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", requestId.toString());
		body.put("intent", "author");
		body.put("brief", brief);
		body.put("assetIds", List.of());
		body.put("baseRevision", baseRevision);
		body.put("regenerateFromLatestAnalysis", regenerate);
		byte[] raw = client().post().uri("/api/hypit/projects/{p}/agent-jobs", projectId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(com.grassland.intelligence.hypit.project.HypitJson.write(body)).exchange().expectStatus()
				.isEqualTo(expectedStatus).expectBody().returnResult().getResponseBody();
		try {
			return String.valueOf(JSON.readValue(raw, Map.class).get("code"));
		} catch (Exception error) {
			throw new IllegalStateException("bad envelope", error);
		}
	}

	private Map<String, Object> checkpointOf(UUID jobId) {
		return db.sql("SELECT checkpoint_json::text AS cp FROM hypit_job WHERE id = CAST(:j AS uuid)")
				.bind("j", jobId.toString()).map((row, meta) -> row.get("cp", String.class)).one()
				.map(com.grassland.intelligence.hypit.project.HypitJson::read).block(Duration.ofSeconds(10));
	}

	private long countJobs(UUID projectId) {
		Long count = db.sql("SELECT COUNT(*) AS n FROM hypit_job WHERE project_id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	private long countAgentCreateCommands(UUID projectId) {
		Long count = db.sql(
				"SELECT COUNT(*) AS n FROM hypit_command WHERE action = 'agent.create' AND project_id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	private long plannerCalls() {
		return QWEN
				.countRequestsMatching(
						postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(containing("执行规划器")).build())
				.getCount();
	}

	private long plannerCallsContaining(String text) {
		return QWEN
				.countRequestsMatching(
						postRequestedFor(urlEqualTo("/chat/completions")).withRequestBody(containing(text)).build())
				.getCount();
	}

	/** 最近 clone.plan 结果（工程唯一，断言来源元数据）。 */
	private Map<String, Object> latestClonePlanResult(UUID projectId) {
		return db.sql("""
				SELECT result_json::text AS result FROM hypit_command
				WHERE action = 'clone.plan' AND project_id = CAST(:p AS uuid)
				  AND result_json IS NOT NULL ORDER BY created_at DESC LIMIT 1
				""").bind("p", projectId.toString()).map((row, meta) -> row.get("result", String.class)).one()
				.map(com.grassland.intelligence.hypit.project.HypitJson::read).block(Duration.ofSeconds(10));
	}

	private long countClonePlanCommands(UUID projectId) {
		Long count = db.sql(
				"SELECT COUNT(*) AS n FROM hypit_command WHERE action = 'clone.plan' AND project_id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	private long countBuilds(UUID projectId) {
		Long count = db.sql("SELECT COUNT(*) AS n FROM hypit_build WHERE project_id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	private String jobState(UUID jobId) {
		return jobs.findById(jobId).block(Duration.ofSeconds(10)).state();
	}

	/** 新模式崩溃恢复种子：作者已写回（观察 mutation.apply 成功）、plan 未持久、actions 已清、requeue。 */
	private UUID seedCrashedAfterWriteback(UUID projectId, Map<String, Object> referenceContext) {
		UUID jobId = UUID.randomUUID();
		Map<String, Object> checkpoint = new LinkedHashMap<>();
		checkpoint.put("schemaVersion", 2);
		checkpoint.put("intent", "author");
		checkpoint.put("brief", "按最新分析与素材重新生成方案");
		checkpoint.put("regenerateFromLatestAnalysis", true);
		checkpoint.put("referenceContext", referenceContext);
		checkpoint.put("assetIds", List.of());
		checkpoint.put("baseRevision", 2);
		checkpoint.put("inputs", Map.of());
		checkpoint.put("actions", List.of());
		checkpoint.put("phase", "executing");
		checkpoint.put("round", 1);
		checkpoint.put("actionIndex", 1);
		checkpoint.put("totalActions", 1);
		checkpoint.put("actionSlot", 1001);
		checkpoint.put("executedActions", 1);
		checkpoint.put("scope",
				Map.of("allowedTools", List.of("knowledge.search", "knowledge.read", "build.status", "build.submit",
						"build.cancel", "output.archive", "mutation.apply", "packages.build", "packages.pack")));
		checkpoint.put("observations",
				List.of(Map.of("actionId", UUID.randomUUID().toString(), "state", "succeeded", "result",
						Map.of("tool", "mutation.apply", "changesetId", UUID.randomUUID().toString(), "revision", 3,
								"manifestHash", "m".repeat(64), "appliedPaths", List.of("main.svml")))));
		db.sql("INSERT INTO hypit_job(id, account_id, project_id, kind, state, checkpoint_json)"
				+ " VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 'hypit.agent', 'queued', CAST(:cp AS jsonb))")
				.bind("id", jobId.toString()).bind("o", OWNER_A).bind("p", projectId.toString())
				.bind("cp", com.grassland.intelligence.hypit.project.HypitJson.write(checkpoint)).then()
				.block(Duration.ofSeconds(10));
		return jobId;
	}

	// ── TC-F3-10-01：来源冻结与完整落地 ───────────────────────────────────────

	@Test
	@DisplayName("TC-F3-10-01 来源冻结：202受理冻结version1快照source一致；作者请求实际含分析；clone.plan稳定ID带来源/resultRevision后succeeded；新事务重读一致")
	void sourceFrozenAndFullLanding() throws Exception {
		UUID projectId = seedReadyProject(2);
		String sha = "5".repeat(64);
		UUID assetId = seedAsset(projectId, sha, "ready");
		String analysisId = "ra-" + UUID.nameUUIDFromBytes("fix3-c10-x".getBytes());
		UUID analysisJobId = seedAnalysisJob(projectId, analysisId, sha, 0);
		seedAnalysisCommand(analysisId, sha, "SUCCEEDED", assetId, null);
		stubPlannerMutation();
		UUID requestId = UUID.fromString("eeeeeeee-1073-4000-8000-000000000010");

		// 202 受理：state=queued（202 不刷成功，§4.4/UI-01）。
		Accepted accepted = postAgentJob(projectId, requestId, "按最新分析与素材重新生成方案", 2L, Boolean.TRUE, List.of());
		assertThat(accepted.state()).as("202 受理不是成功态").isEqualTo("queued");

		// checkpoint 冻结：version1 + source
		// 一致（analysisId/mediaHash/assetIds/baseRevision/analysis）。
		Map<String, Object> checkpoint = checkpointOf(UUID.fromString(accepted.jobId()));
		assertThat(checkpoint.get("regenerateFromLatestAnalysis")).isEqualTo(true);
		Map<String, Object> context = com.grassland.intelligence.hypit.project.HypitJson
				.mapValue(checkpoint.get("referenceContext"));
		assertThat(((Number) context.get("version")).intValue()).isEqualTo(1);
		assertThat(context.get("analysisId")).isEqualTo(analysisId);
		assertThat(context.get("mediaHash")).isEqualTo(sha);
		assertThat(context.get("assetIds")).isEqualTo(List.of(assetId.toString()));
		assertThat(((Number) context.get("baseRevision")).longValue()).isEqualTo(2L);
		assertThat(context.get("analysisJobId")).isEqualTo(analysisJobId.toString());

		// worker 真实消费：作者实际请求含分析（参考分析上下文 + analysisId 标识）。
		worker.runOnce().block(Duration.ofSeconds(60));
		assertThat(plannerCalls()).as("planner 实际被调用").isGreaterThanOrEqualTo(1);
		assertThat(plannerCallsContaining("参考分析上下文")).as("作者请求携带冻结上下文标签").isGreaterThanOrEqualTo(1);
		assertThat(plannerCallsContaining(analysisId)).as("作者请求携带 analysisId").isGreaterThanOrEqualTo(1);

		// 终态 + clone.plan 稳定 ID 持久（来源/revision 元数据），checkpoint 记 saved。
		assertThat(jobState(UUID.fromString(accepted.jobId()))).isEqualTo("succeeded");
		Map<String, Object> after = checkpointOf(UUID.fromString(accepted.jobId()));
		assertThat(after.get("clonePlanStatus")).isEqualTo("saved");
		Map<String, Object> plan = latestClonePlanResult(projectId);
		assertThat(plan.get("sourceAnalysisId")).isEqualTo(analysisId);
		assertThat(plan.get("sourceMediaHash")).isEqualTo(sha);
		assertThat(plan.get("sourceJobId")).isEqualTo(analysisJobId.toString());
		assertThat(((Number) plan.get("baseRevision")).longValue()).isEqualTo(2L);
		assertThat(((Number) plan.get("resultRevision")).longValue()).as("resultRevision 对应写回后的新 head").isEqualTo(3L);
		assertThat(plan.get("planId")).asString().isNotBlank();
		assertThat(countClonePlanCommands(projectId)).as("clone.plan 恰一行（稳定 requestId）").isEqualTo(1L);

		// 新事务/刷新重读：checkpoint 与方案来源一致（持久面可重放）。
		Map<String, Object> reread = checkpointOf(UUID.fromString(accepted.jobId()));
		assertThat(reread.get("referenceContext")).isEqualTo(checkpoint.get("referenceContext"));
		assertThat(latestClonePlanResult(projectId).get("sourceAnalysisId")).isEqualTo(analysisId);
	}

	// ── TC-F3-10-02：幂等及旧客户端兼容 ──────────────────────────────────────

	@Test
	@DisplayName("TC-F3-10-02 幂等与兼容：插Y后重放同job同X零模型；同ID异body 409；旧客户端省略/null/false走旧路径无新键")
	void idempotentReplayAndLegacyCompat() {
		UUID projectId = seedReadyProject(2);
		String sha = "6".repeat(64);
		UUID assetId = seedAsset(projectId, sha, "ready");
		String analysisX = "ra-" + UUID.nameUUIDFromBytes("fix3-c10-x2".getBytes());
		seedAnalysisJob(projectId, analysisX, sha, 0);
		seedAnalysisCommand(analysisX, sha, "SUCCEEDED", assetId, null);
		UUID requestId = UUID.fromString("eeeeeeee-1073-4000-8000-000000000011");

		// 受理 X。
		Accepted first = postAgentJob(projectId, requestId, "按最新分析与素材重新生成方案", 2L, Boolean.TRUE, List.of());

		// 插入更新分析 Y（updated_at 更晚 + 新命令）——latest 已翻转。
		String analysisY = "ra-" + UUID.nameUUIDFromBytes("fix3-c10-y2".getBytes());
		seedAnalysisJob(projectId, analysisY, sha, 60);
		seedAnalysisCommand(analysisY, sha, "SUCCEEDED", assetId, null);
		// 已受理任务或其他编辑推进 head 后，同一 requestId 仍必须恢复原 job。
		db.sql("UPDATE hypit_project SET revision = 3 WHERE id = CAST(:p AS uuid)").bind("p", projectId.toString())
				.fetch().rowsUpdated().block();
		long jobsBeforeReplay = countJobs(projectId);

		// 重放原请求：同 job、同 X 快照、零额外模型调用（重放判定先于 latest 选择——RULE-011）。
		Accepted replay = postAgentJob(projectId, requestId, "按最新分析与素材重新生成方案", 2L, Boolean.TRUE, List.of());
		assertThat(replay.jobId()).as("同 requestId 重放同一 job").isEqualTo(first.jobId());
		Map<String, Object> checkpoint = checkpointOf(UUID.fromString(first.jobId()));
		assertThat(com.grassland.intelligence.hypit.project.HypitJson.mapValue(checkpoint.get("referenceContext"))
				.get("analysisId")).as("重放不重新选 latest（仍是 X）").isEqualTo(analysisX);
		assertThat(plannerCalls()).as("重放零额外模型调用").isZero();
		assertThat(countJobs(projectId)).as("重放不建新 job").isEqualTo(jobsBeforeReplay);

		// 同 ID 异 body（改 brief）：409 hypit_idempotency_conflict，保留原 job。
		String conflictCode = postAgentJobExpectConflict(projectId, requestId, "改过的 brief", 2L);
		assertThat(conflictCode).isEqualTo("hypit_idempotency_conflict");
		assertThat(countJobs(projectId)).isEqualTo(jobsBeforeReplay);

		// 旧客户端三形态（省略字段 / null / false；各独立 requestId）走旧路径。
		Accepted omitted = postAgentJob(projectId, UUID.randomUUID(), "旧客户端省略字段", 2L, null, null);
		Accepted explicitNull = postAgentJob(projectId, UUID.randomUUID(), "旧客户端显式null", 2L, null, List.of());
		Accepted explicitFalse = postAgentJob(projectId, UUID.randomUUID(), "旧客户端显式false", 2L, Boolean.FALSE,
				List.of());
		for (Accepted legacy : List.of(omitted, explicitNull, explicitFalse)) {
			Map<String, Object> legacyCheckpoint = checkpointOf(UUID.fromString(legacy.jobId()));
			assertThat(legacyCheckpoint).as("旧路径 checkpoint 无新键（%s）", legacy.jobId())
					.doesNotContainKey("regenerateFromLatestAnalysis").doesNotContainKey("referenceContext");
		}
	}

	/** 同 ID 异 body 的冲突提交（409 面）。 */
	private String postAgentJobExpectConflict(UUID projectId, UUID requestId, String brief, Long baseRevision) {
		return postAgentJobExpectCode(projectId, requestId, brief, baseRevision, true, 409);
	}

	// ── TC-F3-10-03：冲突、重启与部分成功 ────────────────────────────────────

	@Test
	@DisplayName("TC-F3-10-03 冲突/重启/部分成功：缺分析等五路409零副作用；256KiB边界；崩溃恢复只补plan一次；CAS不覆盖；取消不新增build")
	void conflictsRestartAndPartialSuccess() throws Exception {
		String sha = "7".repeat(64);

		// (a) 缺可信分析：409 + job/command/模型全零。
		UUID noAnalysisProject = seedReadyProject(2);
		seedAsset(noAnalysisProject, sha, "ready");
		assertThat(postAgentJobExpectError(noAnalysisProject, UUID.randomUUID(), "按最新分析与素材重新生成方案", 2L, 409))
				.isEqualTo("hypit_state_conflict");
		assertThat(countJobs(noAnalysisProject)).as("缺分析：0 job").isZero();
		assertThat(countAgentCreateCommands(noAnalysisProject)).as("缺分析：0 command（事务回滚）").isZero();
		assertThat(plannerCalls()).as("缺分析：0 模型调用").isZero();

		// (b) 非SUCCEEDED（PROVISIONAL）：409。
		UUID provisionalProject = seedReadyProject(2);
		UUID provisionalAsset = seedAsset(provisionalProject, sha, "ready");
		String provisionalAnalysis = "ra-" + UUID.nameUUIDFromBytes("fix3-c10-prov".getBytes());
		seedAnalysisJob(provisionalProject, provisionalAnalysis, sha, 0);
		seedAnalysisCommand(provisionalAnalysis, sha, "PROVISIONAL", provisionalAsset, null);
		assertThat(postAgentJobExpectError(provisionalProject, UUID.randomUUID(), "按最新分析与素材重新生成方案", 2L, 409))
				.isEqualTo("hypit_state_conflict");
		assertThat(countJobs(provisionalProject)).as("PROVISIONAL 不建新 job（仅剩种子分析 job）").isEqualTo(1L);

		// (c) 素材非 ready（failed）：409。
		UUID failedAssetProject = seedReadyProject(2);
		seedAsset(failedAssetProject, sha, "failed");
		String failedAssetAnalysis = "ra-" + UUID.nameUUIDFromBytes("fix3-c10-fa".getBytes());
		seedAnalysisJob(failedAssetProject, failedAssetAnalysis, sha, 0);
		seedAnalysisCommand(failedAssetAnalysis, sha, "SUCCEEDED", null, null);
		assertThat(postAgentJobExpectError(failedAssetProject, UUID.randomUUID(), "按最新分析与素材重新生成方案", 2L, 409))
				.isEqualTo("hypit_state_conflict");

		// (d) sha 不符（分析 mediaHash 与工程素材 sha 不匹配）：409。
		UUID mismatchProject = seedReadyProject(2);
		seedAsset(mismatchProject, "9".repeat(64), "ready");
		String mismatchAnalysis = "ra-" + UUID.nameUUIDFromBytes("fix3-c10-mm".getBytes());
		seedAnalysisJob(mismatchProject, mismatchAnalysis, sha, 0);
		seedAnalysisCommand(mismatchAnalysis, sha, "SUCCEEDED", null, null);
		assertThat(postAgentJobExpectError(mismatchProject, UUID.randomUUID(), "按最新分析与素材重新生成方案", 2L, 409))
				.isEqualTo("hypit_state_conflict");

		// (e) head 冲突（baseRevision=2 ≠ head=3）：409「工程已更新」。
		UUID headProject = seedReadyProject(3);
		UUID headAsset = seedAsset(headProject, sha, "ready");
		String headAnalysis = "ra-" + UUID.nameUUIDFromBytes("fix3-c10-head".getBytes());
		seedAnalysisJob(headProject, headAnalysis, sha, 0);
		seedAnalysisCommand(headAnalysis, sha, "SUCCEEDED", headAsset, null);
		assertThat(postAgentJobExpectError(headProject, UUID.randomUUID(), "按最新分析与素材重新生成方案", 2L, 409))
				.isEqualTo("hypit_state_conflict");

		// (f) 快照预算边界（§7.2）：恰 256KiB 受理；+1 字节 400 hypit_invalid_input（不截断事实）。
		// padding 由服务端同构序列化（snapshotBytes）预先精确到字节（probe 的 analysisId/job/asset
		// 均取与真实 seed 相同长度的 UUID 形态，长度差会使边界漂移）；两形态各用独立工程/analysisId
		// （reference.analyze 命令按 analysisId 派生稳定 request_id，同 ID 二次 seed 不会更新 result）。
		int target = 256 * 1024;
		UUID probeJob = UUID.randomUUID();
		UUID probeAsset = UUID.randomUUID();
		String exactAnalysis = "ra-" + UUID.nameUUIDFromBytes("fix3-c10-exact".getBytes());
		int baseline = snapshotBytes(probeJob, exactAnalysis, sha, probeAsset, "");
		String padExact = "x".repeat(Math.max(0, target - baseline));
		assertThat(snapshotBytes(probeJob, exactAnalysis, sha, probeAsset, padExact)).as("padding 精确到边界")
				.isEqualTo(target);

		UUID exactProject = seedReadyProject(2);
		UUID exactAsset = seedAsset(exactProject, sha, "ready");
		seedAnalysisJob(exactProject, exactAnalysis, sha, 0);
		seedAnalysisCommand(exactAnalysis, sha, "SUCCEEDED", exactAsset, padExact);
		Accepted boundaryAccepted = postAgentJob(exactProject, UUID.randomUUID(), "按最新分析与素材重新生成方案", 2L, Boolean.TRUE,
				List.of());
		Map<String, Object> boundaryContext = com.grassland.intelligence.hypit.project.HypitJson
				.mapValue(checkpointOf(UUID.fromString(boundaryAccepted.jobId())).get("referenceContext"));
		assertThat(com.grassland.intelligence.hypit.project.HypitJson.write(boundaryContext)
				.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).as("恰 256KiB 边界受理").isEqualTo(target);

		UUID overflowProject = seedReadyProject(2);
		UUID overflowAsset = seedAsset(overflowProject, sha, "ready");
		String overflowAnalysis = "ra-" + UUID.nameUUIDFromBytes("fix3-c10-overflow".getBytes());
		seedAnalysisJob(overflowProject, overflowAnalysis, sha, 0);
		seedAnalysisCommand(overflowAnalysis, sha, "SUCCEEDED", overflowAsset, padExact + "y");
		assertThat(postAgentJobExpectError(overflowProject, UUID.randomUUID(), "按最新分析与素材重新生成方案", 2L, 400))
				.isEqualTo("hypit_invalid_input");
		assertThat(countJobs(overflowProject)).as("超限不建 job").isEqualTo(1L); // 仅分析 job

		// (g) 崩溃恢复（作者已写回、plan 未持久）：恢复只补 plan 一次，重复恢复不增行。
		UUID recoverProject = seedReadyProject(3);
		UUID recoverAsset = seedAsset(recoverProject, sha, "ready");
		String recoverAnalysis = "ra-" + UUID.nameUUIDFromBytes("fix3-c10-recover".getBytes());
		UUID recoverAnalysisJob = seedAnalysisJob(recoverProject, recoverAnalysis, sha, 0);
		Map<String, Object> recoverContext = new LinkedHashMap<>();
		recoverContext.put("version", 1);
		recoverContext.put("analysisJobId", recoverAnalysisJob.toString());
		recoverContext.put("analysisId", recoverAnalysis);
		recoverContext.put("mediaHash", sha);
		recoverContext.put("assetIds", List.of(recoverAsset.toString()));
		recoverContext.put("baseRevision", 2);
		recoverContext.put("analysis", analysisResult(recoverAnalysis, sha, "SUCCEEDED", recoverAsset, null));
		UUID crashed = seedCrashedAfterWriteback(recoverProject, recoverContext);
		worker.runOnce().block(Duration.ofSeconds(60));
		assertThat(jobState(crashed)).as("恢复后收口 succeeded（plan 落档才成功）").isEqualTo("succeeded");
		assertThat(countClonePlanCommands(recoverProject)).as("恢复只补 plan 一次").isEqualTo(1L);
		Map<String, Object> recoveredPlan = latestClonePlanResult(recoverProject);
		assertThat(recoveredPlan.get("sourceAnalysisId")).isEqualTo(recoverAnalysis);
		assertThat(((Number) recoveredPlan.get("resultRevision")).longValue()).isEqualTo(3L);
		worker.runOnce().block(Duration.ofSeconds(60));
		assertThat(countClonePlanCommands(recoverProject)).as("重复恢复不增 clone.plan 行（读收据）").isEqualTo(1L);

		// (h) CAS 竞争：受理后他者把 head 推到 3，worker 的 baseRevision=2 写回被 CAS 拒，不覆盖。
		stubPlannerMutation();
		UUID casProject = seedReadyProject(2);
		UUID casAsset = seedAsset(casProject, sha, "ready");
		String casAnalysis = "ra-" + UUID.nameUUIDFromBytes("fix3-c10-cas".getBytes());
		seedAnalysisJob(casProject, casAnalysis, sha, 0);
		seedAnalysisCommand(casAnalysis, sha, "SUCCEEDED", casAsset, null);
		Accepted casAccepted = postAgentJob(casProject, UUID.randomUUID(), "按最新分析与素材重新生成方案", 2L, Boolean.TRUE,
				List.of());
		db.sql("UPDATE hypit_project SET revision = 3 WHERE id = CAST(:p AS uuid)").bind("p", casProject.toString())
				.then().block(Duration.ofSeconds(10));
		for (int i = 0; i < 4; i++) {
			worker.runOnce().block(Duration.ofSeconds(60));
		}
		assertThat(jobState(UUID.fromString(casAccepted.jobId()))).as("CAS 冲突下不得 succeeded").isNotEqualTo("succeeded");
		assertThat(projectRevision(casProject)).as("不覆盖他人 head").isEqualTo(3L);
		assertThat(countBuilds(casProject)).as("CAS 失败链零 build").isZero();

		// (i) 取消：派发前取消 → cancelled，不新增 build。
		UUID cancelProject = seedReadyProject(2);
		UUID cancelAsset = seedAsset(cancelProject, sha, "ready");
		String cancelAnalysis = "ra-" + UUID.nameUUIDFromBytes("fix3-c10-cancel".getBytes());
		seedAnalysisJob(cancelProject, cancelAnalysis, sha, 0);
		seedAnalysisCommand(cancelAnalysis, sha, "SUCCEEDED", cancelAsset, null);
		Accepted cancelAccepted = postAgentJob(cancelProject, UUID.randomUUID(), "按最新分析与素材重新生成方案", 2L, Boolean.TRUE,
				List.of());
		UUID cancelJobId = UUID.fromString(cancelAccepted.jobId());
		db.sql("UPDATE hypit_job SET cancel_requested_at = now() WHERE id = CAST(:j AS uuid)")
				.bind("j", cancelJobId.toString()).then().block(Duration.ofSeconds(10));
		worker.runOnce().block(Duration.ofSeconds(60));
		assertThat(jobState(cancelJobId)).isEqualTo("cancelled");
		assertThat(countBuilds(cancelProject)).as("取消不新增 build").isZero();
	}

	/** 服务端同构快照字节数（W23 toMap 序列化）——用于把分析 padding 调到精确边界。 */
	private int snapshotBytes(UUID analysisJobId, String analysisId, String mediaHash, UUID assetId, String padding) {
		Map<String, Object> result = analysisResult(analysisId, mediaHash, "SUCCEEDED", assetId, padding);
		com.grassland.intelligence.hypit.agent.HypitAuthorContextService.ReferenceContext context = new com.grassland.intelligence.hypit.agent.HypitAuthorContextService.ReferenceContext(
				1, analysisJobId, analysisId, mediaHash, List.of(assetId), 2L,
				com.grassland.intelligence.hypit.agent.HypitReferenceAnalysisService.fromMap(result));
		return com.grassland.intelligence.hypit.project.HypitJson.write(context.toMap())
				.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
	}

	private long projectRevision(UUID projectId) {
		Long revision = db.sql("SELECT revision FROM hypit_project WHERE id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((row, meta) -> row.get("revision", Long.class)).one()
				.block(Duration.ofSeconds(10));
		return revision == null ? 0 : revision;
	}
}
