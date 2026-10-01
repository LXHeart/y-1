package com.grassland.intelligence.hypit.fix2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.credits.CreditsStubs;
import com.grassland.intelligence.hypit.agent.HypitAgentScope;
import com.grassland.intelligence.hypit.agent.HypitAgentStepService;
import com.grassland.intelligence.hypit.agent.HypitAgentToolRegistry;
import com.grassland.intelligence.hypit.agent.HypitAgentToolRegistry.DispatchOutcome;
import com.grassland.intelligence.hypit.agent.HypitAgentToolRegistry.ToolCall;
import com.grassland.intelligence.hypit.agent.HypitAgentWorker;
import com.grassland.intelligence.hypit.asset.HypitAssetService;
import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitBuildService;
import com.grassland.intelligence.hypit.build.HypitOutputRepository;
import com.grassland.intelligence.hypit.build.HypitPlanRepository;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository;
import com.grassland.intelligence.hypit.job.HypitJobActionRepository.ActionRow;
import com.grassland.intelligence.hypit.job.HypitJobRepository;
import com.grassland.intelligence.hypit.job.HypitJobRepository.JobRow;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.PresignRequest;
import com.grassland.storage.StoredObject;
import com.grassland.storage.UploadTicket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * C107F2-14（107-fix-2 §6.7 / RULE-08 / F21）：Agent 工具分发、逐步执行与真实成功判定。
 *
 * <p>
 * 真 PostgreSQL + WireMock sidecar（命令/资源）+ 内存对象存储；planner 走 QWEN 桩（沿
 * HypitAgentJobIT 手法：平台 text 行指向 QWEN、CreditsClient 放行、worker 调度静默、直调 runOnce
 * 确定性驱动）。
 *
 * <ul>
 * <li>TC-F2-14-01 工具集合相等：契约 agentTools == 注册表 visibleTools；逐工具 dispatch 全部命中 真实
 * handler（无默认 501）；未登记工具结构化 hypit_unsupported_action；参数类型保真（number
 * 原样持久、字符串形态被拒）。</li>
 * <li>TC-F2-14-02 失败不伪成功：唯一必要 mutation.apply 校验失败 → job 非 succeeded，failed 带
 * 可行动原因，零 changeset 落库。</li>
 * <li>TC-F2-14-03 观察再规划：首动作失败诊断、次轮修复；第二轮 planner 输入包含第一轮真实观察 （actionId +
 * 错误码）；43 条计划截断 40，实际动作 ≤40/轮。</li>
 * <li>TC-F2-14-04 scope 单因子：合法输入但工具不在 scope → 结构化拒绝 scopeRefusal，业务 service
 * 零调用（零 changeset/零 command）、外部执行零消耗（sidecar/QWEN 零请求）。</li>
 * </ul>
 */
@TestPropertySource(properties = {"hypit.enabled=true", "hypit.agent-worker.enabled=false"})
class HypitFix2C14IT extends IntelligenceItSupport {

	private static final String OWNER = "eeeeeeee-0000-4000-8000-00000000014a";
	private static final byte[] MP4_BYTES = {0, 0, 0, 8, 'f', 't', 'y', 'p'};
	private static final String HANDLE = "res-fix2c14-0001-it0000000000";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final WireMockServer SIDECAR = new WireMockServer(0);
	private static final Path REPO = Path.of("").toAbsolutePath().resolve("../../..").normalize();

	@MockitoBean
	private CreditsClient credits;

	@Autowired
	HypitAgentToolRegistry registry;

	@Autowired
	HypitAgentStepService steps;

	@Autowired
	HypitAgentWorker worker;

	@Autowired
	HypitJobRepository jobs;

	@Autowired
	HypitJobActionRepository jobActions;

	@Autowired
	HypitPlanRepository plans;

	@Autowired
	HypitBuildRepository builds;

	@Autowired
	HypitOutputRepository outputs;

	@Autowired
	HypitAssetService assets;

	@Autowired
	HypitBuildService buildService;

	@Autowired
	DatabaseClient db;

	@Autowired
	ObjectStorageAdapter storage;

	@TestConfiguration
	static class StorageConfig {
		@Bean
		ObjectStorageAdapter hypitFix2C14TestStorage() {
			return new CountingStorage();
		}
	}

	static final class CountingStorage implements ObjectStorageAdapter {

		final Map<String, byte[]> objects = new ConcurrentHashMap<>();
		final List<String> puts = new CopyOnWriteArrayList<>();

		@Override
		public UploadTicket presignUpload(PresignRequest request) {
			throw new UnsupportedOperationException();
		}

		@Override
		public URI presignDownload(String key, long expiresSeconds) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void putObject(String key, byte[] content, String contentType) {
			objects.put(key, content);
			puts.add(key);
		}

		@Override
		public byte[] getObject(String key) {
			return objects.get(key);
		}

		@Override
		public java.util.Optional<StoredObject> headObject(String key) {
			byte[] content = objects.get(key);
			return java.util.Optional.ofNullable(content)
					.map((value) -> new StoredObject(key, value.length, null, null, java.time.Instant.now()));
		}

		@Override
		public void deleteObject(String key) {
			objects.remove(key);
		}

		@Override
		public List<StoredObject> listObjects(String prefix) {
			return objects.entrySet().stream().filter(entry -> entry.getKey().startsWith(prefix))
					.map(entry -> new StoredObject(entry.getKey(), entry.getValue().length, null, null,
							java.time.Instant.now()))
					.toList();
		}
	}

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> "fix2-c14-internal-token-0123456789abcdef");
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
	}

	@BeforeAll
	static void startSidecar() {
		SIDECAR.start();
		String sha = HypitExternalExecutionBridge.sha256Hex(new String(MP4_BYTES));
		// 归档链（output.archive）：results.read → results.export → 字节回读。
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("results.read"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","state":"succeeded","result":{"outputs":[
						 {"name":"final.video","kind":"resource","mediaType":"video/mp4"}]}}
						""")));
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("results.export"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","state":"succeeded","result":{"kind":"resource","mediaType":"video/mp4",
						 "size":8,"sha256":"%s","handle":"%s"}}
						""".formatted(sha, HANDLE))));
		SIDECAR.stubFor(get(urlEqualTo("/internal/v1/resources/" + HANDLE))
				.willReturn(aResponse().withHeader("Content-Type", "video/mp4").withBody(MP4_BYTES)));
		// 命令类工具（packages.build/pack、snapshot、feedback.mutate）：真实 dispatcher 命中后的回执形态。
		for (String[] stub : new String[][]{{"packages.build", "{\"staged\":true,\"dist\":\"dist/\"}"},
				{"packages.pack", "{\"bundle\":\"pkg-0001.zip\",\"entries\":3}"},
				{"snapshot", "{\"run\":\"r1\",\"frames\":3}"},
				{"feedback.mutate", "{\"applied\":1,\"run\":\"r1\",\"acknowledged\":true}"},
				// mutation.apply 的引擎侧落库回执（workspace.apply → revision 前进）。
				{"workspace.apply", "{\"revision\":3,\"manifestHash\":\"" + "m".repeat(64)
						+ "\",\"appliedPaths\":[\"src/app.js\"]}"}}) {
			String body = "{\"commandId\":\"x\",\"state\":\"succeeded\",\"result\":"
					+ stub[1].substring(stub[1].indexOf('{')) + "}";
			SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing(stub[0]))
					.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
		}
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	private UUID projectId;

	@BeforeEach
	void seed() throws Exception {
		reset(credits);
		CreditsStubs.stubDefaults(credits);
		QWEN.resetAll();
		SIDECAR.resetRequests();
		((CountingStorage) storage).puts.clear();
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'fix2-c14', 'clone', 'ready', 2)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
		seedPlatformTextRow();
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
				.then(db.sql("DELETE FROM hypit_changeset WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_plan WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM media_reference WHERE owner_account_id = :o").bind("o", OWNER).then())
				.block(Duration.ofSeconds(20));
	}

	/** 平台 text 行指向 QWEN（planner 走执行环）；按目的地自清后建行。 */
	private void seedPlatformTextRow() {
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
						.withBody("{\"choices\":[{\"message\":{\"content\":" + jsonString(content) + "}}],"
								+ "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")));
	}

	private static String jsonString(String value) {
		return JSON.valueToTree(value).toString();
	}

	/** 直插 hypit_job（受控 checkpoint；绕前端是 TC-04 的目标条件）。 */
	private UUID insertJob(String state, String checkpointJson) {
		UUID jobId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_job(id, account_id, project_id, kind, state, checkpoint_json)"
				+ " VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 'hypit.agent', :state, CAST(:cp AS jsonb))")
				.bind("id", jobId.toString()).bind("o", OWNER).bind("p", projectId.toString()).bind("state", state)
				.bind("cp", checkpointJson).then().block(Duration.ofSeconds(10));
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

	private long countRows(String sql) {
		Long count = db.sql(sql).bind("o", OWNER).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	/** 本 owner 名下工程的 changeset 行数（owner 绑定统一走 :o 占位符）。 */
	private long changesetRows() {
		return countRows("SELECT COUNT(*) AS n FROM hypit_changeset c"
				+ " JOIN hypit_project p ON p.id = c.project_id WHERE p.account_id = :o");
	}

	private DispatchOutcome dispatch(HypitAgentScope scope, String tool, Map<String, Object> input) {
		return registry
				.dispatch(scope, tool,
						new ToolCall(UUID.randomUUID(), projectId, OWNER, 2L, input == null ? Map.of() : input))
				.block(Duration.ofSeconds(30));
	}

	/** 造一个 finished build + final.video 输出行（归档链种子，沿 C12 手法）。 */
	private UUID seedFinishedBuildWithOutput() {
		UUID planId = plans
				.insertPlan(UUID.randomUUID(), projectId, 2, "main.svrun", "c".repeat(64), "p".repeat(64), "{}")
				.block(Duration.ofSeconds(10)).id();
		UUID commandId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_command(id, account_id, project_id, target_key, action, request_id,"
				+ " payload_hash, payload_json, state) VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 't',"
				+ " 'build.submit', CAST(:r AS uuid), :h, '{}', 'acknowledged')").bind("id", commandId.toString())
				.bind("o", OWNER).bind("p", projectId.toString()).bind("r", UUID.randomUUID()).bind("h", "h".repeat(64))
				.then().block(Duration.ofSeconds(10));
		UUID buildId = builds.insert(UUID.randomUUID(), commandId, projectId, 2L, planId, "main.svrun")
				.flatMap(row -> db
						.sql("UPDATE hypit_build SET engine_build_id = :e, lifecycle = 'finished',"
								+ " outcome = 'complete', finished_at = now(), result_location = CAST('{}' AS jsonb)"
								+ " WHERE id = CAST(:id AS uuid)")
						.bind("e", "bld_fix2c14_0000000000000001").bind("id", row.id().toString()).then()
						.then(builds.findById(row.id())))
				.block(Duration.ofSeconds(10)).id();
		outputs.insert(UUID.randomUUID(), buildId, "final.video", "resource", "video/mp4", 8L,
				"{\"type\":\"video\",\"displayName\":\"成片\"}").block(Duration.ofSeconds(10));
		return buildId;
	}

	// ── TC-F2-14-01：工具集合相等 + 逐工具真实 handler + 类型保真 ──────────
	@Test
	@DisplayName("TC-F2-14-01 契约=注册表；逐工具 dispatch 命中真实 handler；未登记结构化拒绝；类型保真")
	void toolSetMatchesContractAndEveryToolHitsRealHandler() throws Exception {
		// 契约 agentTools 集合 == 注册表可见集合（可见工具集合 == 实际 handler 集合）。
		JsonNode contract = JSON.readTree(Files.readString(REPO.resolve("contracts/hypit-tools.v1.json")));
		Set<String> contractTools = new LinkedHashSet<>();
		contract.path("agentTools").forEach(tool -> contractTools.add(tool.path("id").asText()));
		assertThat(registry.visibleTools()).as("契约 agentTools == 注册表 visibleTools").isEqualTo(contractTools);

		// 逐工具种子与合法输入。
		UUID submitBuildId = seedSubmittedBuildForStatusCancel();
		UUID archiveBuildId = seedFinishedBuildWithOutput();
		HypitAgentScope all = new HypitAgentScope(false, registry.visibleTools());

		// knowledge：真实索引。
		DispatchOutcome search = dispatch(all, "knowledge.search",
				Map.of("topic", "production", "query", "browser", "limit", 5));
		assertThat(search.ok()).as("knowledge.search 命中真实 handler").isTrue();
		assertThat((List<?>) search.result().get("hits")).isNotEmpty();
		assertThat(String.valueOf(search.result().get("sourceCommit"))).isNotBlank();

		DispatchOutcome read = dispatch(all, "knowledge.read",
				Map.of("path", "references/production/browser-capture.md"));
		assertThat(read.ok()).as("knowledge.read 命中真实 handler").isTrue();
		assertThat(String.valueOf(read.result().get("document"))).isNotBlank();

		// build：真实 Build 服务（记账型提交 → 状态 → 就地取消）。
		DispatchOutcome status = dispatch(all, "build.status", Map.of("buildId", submitBuildId.toString()));
		assertThat(status.ok()).as("build.status 命中真实 handler").isTrue();
		assertThat(String.valueOf(status.result().get("lifecycle"))).isNotBlank();

		DispatchOutcome cancel = dispatch(all, "build.cancel",
				Map.of("buildId", submitBuildId.toString(), "reason", "agent 取消"));
		assertThat(cancel.ok()).as("build.cancel 命中真实 handler（未提交就地取消）").isTrue();
		assertThat(String.valueOf(cancel.result().get("outcome"))).isEqualTo("cancelled");

		// output.archive：真实归档链（sidecar export + 对象 PUT）。
		UUID outputId = outputs.findByBuild(archiveBuildId).next().block(Duration.ofSeconds(10)).id();
		DispatchOutcome archive = dispatch(all, "output.archive", Map.of("outputId", outputId.toString()));
		assertThat(archive.ok()).as("output.archive 命中真实归档链").isTrue();
		assertThat(String.valueOf(archive.result().get("archiveState"))).isEqualTo("archived");
		assertThat(archive.result().get("mediaId")).isNotNull();

		// mutation.apply：真实 changeset create+apply（revision 前进）。
		DispatchOutcome mutation = dispatch(all, "mutation.apply",
				Map.of("changes", List.of(Map.of("path", "src/app.js", "action", "put", "content", "// c14"))));
		assertThat(mutation.ok()).as("mutation.apply 命中真实 changeset 链").isTrue();
		assertThat(((Number) mutation.result().get("revision")).longValue()).isGreaterThan(2L);

		// packages/snapshot/feedback：真实 dispatcher 提交链（sidecar 命令）。
		for (String tool : List.of("packages.build", "packages.pack", "snapshot")) {
			DispatchOutcome outcome = dispatch(all, tool, Map.of("projectId", projectId.toString()));
			assertThat(outcome.ok()).as(tool + " 命中真实 dispatcher 提交链").isTrue();
			assertThat(outcome.result()).as(tool + " 回执携带结果").isNotEmpty();
		}
		DispatchOutcome feedback = dispatch(all, "feedback.mutate",
				Map.of("mutations", List.of(Map.of("kind", "resolve", "commentId", "c1")), "run", "r1"));
		assertThat(feedback.ok()).as("feedback.mutate 命中真实 dispatcher 提交链").isTrue();

		// 未登记工具：结构化 hypit_unsupported_action，绝非默认 501。
		DispatchOutcome unknown = dispatch(all, "tools.unknown", Map.of("x", 1));
		assertThat(unknown.ok()).isFalse();
		assertThat(unknown.errorCode()).isEqualTo("hypit_unsupported_action");
		assertThat(unknown.scopeRefusal()).isFalse();

		// 类型保真（§6.7）：字符串形态的 number 参数被 schema 拒绝（不静默 asText 化）。
		DispatchOutcome stringified = dispatch(all, "knowledge.search", Map.of("query", "browser", "limit", "5"));
		assertThat(stringified.ok()).as("字符串化 number 不被静默接受").isFalse();
		assertThat(stringified.errorCode()).isEqualTo("hypit_invalid_input");

		// 持久面：executeOne 落库的 input_json 保持 number 原始类型。
		UUID jobId = insertJob("running", """
				{"scope":{"allowedTools":["knowledge.search","knowledge.read","build.status"]},"actions":[
				 {"kind":"knowledge.search","input":{"query":"browser","limit":5}}]}
				""".replace("\n", " "));
		steps.executeOne(jobId, projectId, OWNER, 2L, HypitAgentScope.readOnlyScope(), 0,
				new com.grassland.intelligence.hypit.agent.HypitAgentAction("knowledge.search",
						"{\"query\":\"browser\",\"limit\":5}", null, false))
				.block(Duration.ofSeconds(30));
		ActionRow row = jobActions.findByJob(jobId).next().block(Duration.ofSeconds(10));
		assertThat(row.state()).isEqualTo("succeeded");
		JsonNode storedInput = JSON.readTree(row.inputJson());
		assertThat(storedInput.path("input").path("limit").isNumber()).as("limit 持久保持 number").isTrue();
		assertThat(storedInput.path("tool").asText()).isEqualTo("knowledge.search");
		assertThat(row.kind()).as("kind 恒为受限枚举（工具名进 input_json）").isEqualTo("tool");
	}

	/** build.submit 种子：真实提交链产出的 build（revision 2 冻结匹配）。 */
	private UUID seedSubmittedBuildForStatusCancel() {
		UUID planId = plans
				.insertPlan(UUID.randomUUID(), projectId, 2, "main.svrun", "d".repeat(64), "q".repeat(64), "{}")
				.block(Duration.ofSeconds(10)).id();
		DispatchOutcome submit = dispatch(new HypitAgentScope(false, Set.of("knowledge.search", "build.submit")),
				"build.submit", Map.of("planId", planId.toString()));
		assertThat(submit.ok()).as("build.submit 命中真实提交链").isTrue();
		return UUID.fromString(String.valueOf(submit.result().get("buildId")));
	}

	// ── TC-F2-14-02：失败不伪成功 ─────────────────────────────────────────
	@Test
	@DisplayName("TC-F2-14-02 唯一必要 mutation 校验失败 → job failed 非伪成功，零 changeset 落库")
	void mutationValidationFailureFailsJobWithActionableReason() throws Exception {
		// action=write 不在 put/delete 枚举：真实 ChangesetService.create 校验失败（无需 sidecar 桩）。
		UUID jobId = insertJob("queued", """
				{"scope":{"allowedTools":["mutation.apply"]},"actions":[
				 {"kind":"mutation.apply","input":{"changes":[
				   {"path":"src/app.js","action":"write","content":"x"}]}}],"baseRevision":2}
				""".replace("\n", " "));

		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow job = awaitTerminal(jobId, "failed");
		assertThat(job).as("校验失败绝不收 succeeded").isNotNull();
		assertThat(job.state()).isNotEqualTo("succeeded");
		assertThat(job.errorCode()).isEqualTo("hypit_action_failed");
		assertThat(String.valueOf(job.errorMessage())).isNotBlank();

		List<ActionRow> rows = jobActions.findByJob(jobId).collectList().block(Duration.ofSeconds(10));
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).state()).isEqualTo("failed");
		assertThat(rows.get(0).resultJson()).contains("mutation.apply");

		Map<String, Object> checkpoint = checkpointOf(jobId);
		assertThat(String.valueOf(checkpoint.get("blockedReason"))).as("失败原因留诊断").contains("mutation.apply");

		// 副作用面：校验失败零 changeset 落库（失败检查行数）。
		assertThat(changesetRows()).as("校验失败零 changeset 行").isZero();
	}

	// ── TC-F2-38-02R：defer 类失败行的恢复重派发（round-9 实录回归） ──────────
	@Test
	@DisplayName("TC-F2-38-02R defer 类 failed 行恢复后重派发收敛；真失败 failed 行仍回放不重跑")
	void deferredBrokerFailureRowRedispatchesAndTrueFailureRowReplays() throws Exception {
		// 基线读桩（needsBase：put 缺 baseHash → 服务端实读 head hash 作为 CAS 基线）。
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.read"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"x\",\"state\":\"succeeded\",\"result\":{\"hash\":\""
								+ "a".repeat(64) + "\"}}")));

		// 场景 2（先跑，sidecar 零流量才能断言「不重派发」）：真失败行（非 defer 类）
		// 原样回放——job 收 failed，绝不重新打 sidecar。
		String trueFailInput = "{\"changes\":[{\"action\":\"put\",\"content\":\"y\",\"path\":\"notes.md\"}]}";
		String trueFailHash = HypitExternalExecutionBridge.sha256Hex(trueFailInput);
		UUID trueFailJob = insertJob("queued", """
				{"scope":{"allowedTools":["mutation.apply"]},"actions":[
				 {"kind":"mutation.apply","input":{"changes":[
				   {"path":"notes.md","action":"put","content":"y"}]}}],"baseRevision":2}
				""".replace("\n", " "));
		db.sql("INSERT INTO hypit_job_action(id, job_id, step_index, kind, state, input_hash, result_json)"
				+ " VALUES (CAST(:a AS uuid), CAST(:j AS uuid), 0, 'tool', 'failed', :hash,"
				+ " CAST('{\"code\":\"hypit_action_failed\",\"tool\":\"mutation.apply\","
				+ "\"error\":\"compile_failed\"}' AS jsonb))").bind("a", UUID.randomUUID().toString())
				.bind("j", trueFailJob.toString()).bind("hash", trueFailHash).then().block(Duration.ofSeconds(10));

		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow trueFail = awaitTerminal(trueFailJob, "failed");
		assertThat(trueFail).as("真失败行回放收 failed（不伪成功）").isNotNull();
		SIDECAR.verify(0, postRequestedFor(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("workspace.read")));

		// 场景 1（round-9 实录）：断网期间 mutation.apply 落成 defer 类 failed 行
		// （hypit_broker_unreachable）。broker 恢复后重试必须重置 prepared 重派发
		// ——否则永远回放首次失败，job 陷入 90s/轮无限 defer（实测 broker 已恢复仍不收敛）。
		String canonicalInput = "{\"changes\":[{\"action\":\"put\",\"content\":\"x\",\"path\":\"main.svml\"}]}";
		String realHash = HypitExternalExecutionBridge.sha256Hex(canonicalInput);
		UUID jobId = insertJob("queued", """
				{"scope":{"allowedTools":["mutation.apply"]},"actions":[
				 {"kind":"mutation.apply","input":{"changes":[
				   {"path":"main.svml","action":"put","content":"x"}]}}],"baseRevision":2}
				""".replace("\n", " "));
		db.sql("INSERT INTO hypit_job_action(id, job_id, step_index, kind, state, input_hash, result_json)"
				+ " VALUES (CAST(:a AS uuid), CAST(:j AS uuid), 0, 'tool', 'failed', :hash,"
				+ " CAST('{\"code\":\"hypit_broker_unreachable\",\"tool\":\"mutation.apply\","
				+ "\"error\":\"sidecar 不可达：文件基线 hash 未知\"}' AS jsonb))").bind("a", UUID.randomUUID().toString())
				.bind("j", jobId.toString()).bind("hash", realHash).then().block(Duration.ofSeconds(10));

		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow job = awaitTerminal(jobId, "succeeded");
		assertThat(job).as("defer 类失败行恢复后重派发收敛 succeeded").isNotNull();

		List<ActionRow> rows = jobActions.findByJob(jobId).collectList().block(Duration.ofSeconds(10));
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0).state()).as("重派发后行收敛 succeeded（非回放首次 failed）").isEqualTo("succeeded");
		assertThat(changesetRows()).as("apply 恰一次").isEqualTo(1);
		SIDECAR.verify(postRequestedFor(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("workspace.read")));
	}

	// ── TC-F2-14-03：观察再规划（受控 planner 序列） ────────────────────────
	@Test
	@DisplayName("TC-F2-14-03 首动作失败诊断次轮修复；第二轮输入含真实观察；43 条截断 40")
	void replanCarriesRealObservationsAndCapsRoundAtFortyActions() throws Exception {
		// 第一轮：读不存在的文档 → 真实 404 诊断（观察落 checkpoint）。
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).inScenario("replan")
				.whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED).willSetStateTo("second")
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"choices\":[{\"message\":{\"content\":" + jsonString(
								"{\"actions\":[{\"kind\":\"knowledge.read\",\"input\":{\"path\":\"references/production/no-such-doc.md\"}}]}")
								+ "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")));
		// 第二轮：1 条正确 read（修复）+ 42 条不同 query 的 search（共 43 条 → 截断 40；
		// 互异 input 保证不触发同 hash 幂等折叠）。
		StringBuilder fortyThree = new StringBuilder("{\"actions\":[");
		fortyThree.append(
				"{\"kind\":\"knowledge.read\",\"input\":{\"path\":\"references/production/browser-capture.md\"}}");
		for (int i = 0; i < 42; i++) {
			fortyThree.append(",{\"kind\":\"knowledge.search\",\"input\":{\"query\":\"probe-").append(i)
					.append("\",\"limit\":2}}");
		}
		fortyThree.append("]}");
		QWEN.stubFor(post(urlEqualTo("/chat/completions")).inScenario("replan").whenScenarioStateIs("second")
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"choices\":[{\"message\":{\"content\":" + jsonString(fortyThree.toString())
								+ "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":20}}")));

		UUID jobId = insertJob("queued", """
				{"intent":"analyze","brief":"读取浏览器采集文档","baseRevision":2,"scope":
				 {"allowedTools":["knowledge.search","knowledge.read","build.status"]},"inputs":{},"actions":[]}
				""".replace("\n", " "));

		worker.runOnce().block(Duration.ofSeconds(120));
		JobRow job = awaitTerminal(jobId, "succeeded");
		assertThat(job).as("次轮修复后收 succeeded").isNotNull();

		List<ActionRow> rows = jobActions.findByJob(jobId).collectList().block(Duration.ofSeconds(10));
		assertThat(rows).as("1 条失败诊断 + 40 条修复（43 截断）").hasSize(41);
		assertThat(rows.get(0).state()).isEqualTo("failed");
		assertThat(rows.get(0).resultJson()).contains("hypit_not_found");
		assertThat(rows.stream().filter(row -> "succeeded".equals(row.state())).count()).isEqualTo(40L);

		Map<String, Object> checkpoint = checkpointOf(jobId);
		assertThat(((Number) checkpoint.get("totalActions")).intValue()).as("单轮 ≤40").isEqualTo(40);
		assertThat(((Number) checkpoint.get("fixRounds")).intValue()).isEqualTo(1);

		// 第二轮 planner 输入包含第一轮真实观察：动作行 id + 眞实错误码（WireMock 请求证据）。
		String firstActionId = rows.stream().filter(row -> "failed".equals(row.state())).findFirst().orElseThrow().id()
				.toString();
		List<String> plannerBodies = QWEN.getAllServeEvents().stream()
				.filter(event -> event.getRequest().getUrl().contains("/chat/completions"))
				.map(event -> event.getRequest().getBodyAsString()).toList();
		assertThat(plannerBodies).as("planner 恰两次调用").hasSize(2);
		// getAllServeEvents 最新在前：不依赖顺序，直接断言「带第一轮真实观察」的调用恰一次。
		long withObservations = plannerBodies.stream()
				.filter(body -> body.contains(firstActionId) && body.contains("hypit_not_found")).count();
		assertThat(withObservations).as("第二轮输入含第一轮动作行 id + 真实错误码（真实观察注入）").isEqualTo(1);
	}

	// ── TC-F2-14-04：scope 单因子（合法输入、工具不在 scope） ───────────────
	@Test
	@DisplayName("TC-F2-14-04 工具不在 scope：结构化拒绝、业务 service 零调用、外部执行零消耗")
	void outOfScopeToolIsRefusedWithZeroBusinessCalls() throws Exception {
		long changesetsBefore = changesetRows();
		long commandsBefore = countRows("SELECT COUNT(*) AS n FROM hypit_command WHERE account_id = :o");

		// 绕前端直接 dispatch：合法输入，但 mutation.apply 不在只读 scope。
		Map<String, Object> legalInput = Map.of("changes",
				List.of(Map.of("path", "src/app.js", "action", "put", "content", "// legal")));
		DispatchOutcome refused = dispatch(HypitAgentScope.readOnlyScope(), "mutation.apply", legalInput);
		assertThat(refused.ok()).isFalse();
		assertThat(refused.errorCode()).isEqualTo("hypit_agent_scope");
		assertThat(refused.scopeRefusal()).as("安全/越权拒绝标记（worker 据此终止）").isTrue();

		assertThat(changesetRows()).as("业务 service 零调用：零新 changeset").isEqualTo(changesetsBefore);
		assertThat(countRows("SELECT COUNT(*) AS n FROM hypit_command WHERE account_id = :o")).as("零新 command")
				.isEqualTo(commandsBefore);
		assertThat(SIDECAR.countRequestsMatching(postRequestedFor(urlPathEqualTo("/internal/v1/commands")).build())
				.getCount()).as("不消耗外部执行：sidecar 零命令").isZero();
		assertThat(QWEN.countRequestsMatching(postRequestedFor(urlEqualTo("/chat/completions")).build()).getCount())
				.as("不消耗外部执行：planner 零调用").isZero();

		// worker 路径同样拒绝：老式 job 直插越权动作 → failed hypit_agent_refused，零副作用。
		UUID jobId = insertJob("queued", """
				{"scope":{"allowedTools":["knowledge.search"]},"actions":[
				 {"kind":"mutation.apply","input":{"changes":[
				   {"path":"src/app.js","action":"put","content":"// legal"}]}}]}
				""".replace("\n", " "));
		worker.runOnce().block(Duration.ofSeconds(60));
		JobRow job = awaitTerminal(jobId, "failed");
		assertThat(job).isNotNull();
		assertThat(job.errorCode()).isEqualTo("hypit_agent_refused");
		assertThat(changesetRows()).as("worker 路径同样零 changeset").isEqualTo(changesetsBefore);
		assertThat(SIDECAR.countRequestsMatching(postRequestedFor(urlPathEqualTo("/internal/v1/commands")).build())
				.getCount()).as("worker 路径 sidecar 仍零命令").isZero();
	}
}
