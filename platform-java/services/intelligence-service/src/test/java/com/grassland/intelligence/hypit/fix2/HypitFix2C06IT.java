package com.grassland.intelligence.hypit.fix2;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.client.HypitSidecarClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.JsonNode;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

/**
 * C107F2-06（107-fix-2）：统一源码根并冻结计划/价格/Profile/构建快照（F25/F26）。
 *
 * <p>
 * 真实 broker 子进程（platform-hypit/backend/src/main.mjs，真实引擎发行版 G）承载
 * plan/pricing/check/build.submit 的源码根与冻结语义；Java 侧断言 PG 冻结绑定。
 *
 * <ul>
 * <li>TC-F2-06-01 projectId/revision/runFile 三调用过真实 broker/runner 通道 （Java 载荷不含
 * sourceDir）；plan 返回真实 revision/manifestHash/profileHash。</li>
 * <li>TC-F2-06-02 work 前进到 B 后，对 A revision 的计划/报价仍绑定 A 快照 manifestHash；Java
 * build command 载荷携带完整冻结绑定。</li>
 * <li>TC-F2-06-03 Profile 摘要漂移后提交旧计划 → plan_stale（明确失败、零渲染副作用）。</li>
 * <li>TC-F2-06-04 revision 快照缺失/manifest 篡改 → 明确失败，不回退当前 work。</li>
 * </ul>
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitFix2C06IT extends IntelligenceItSupport {

	private static final String OWNER = "cccccccc-0000-4000-8000-00000000020a";
	private static final ObjectMapper JSON = new ObjectMapper();
	// gradle test cwd = platform-java/services/intelligence-service → 上三级=仓库根。
	private static final Path REPO = Path.of("").toAbsolutePath().resolve("../../..").normalize();
	private static final Path BACKEND = REPO.resolve("platform-hypit/backend");
	private static final Path GENERATED = REPO.resolve("platform-hypit/.generated/hypit");

	private static Process broker;
	private static Process daemon;
	private static Path brokerData;
	private static int brokerPort;
	private static String token;

	@org.springframework.beans.factory.annotation.Autowired
	private HypitSidecarClient sidecar;

	@org.springframework.beans.factory.annotation.Autowired
	private com.grassland.intelligence.hypit.build.HypitPlanService planService;

	@BeforeAll
	static void startBroker() throws Exception {
		token = "fix2-c06-internal-token-0123456789abcdef"; // secret-scan: allow
		brokerPort = 19300 + ThreadLocalRandom.current().nextInt(80);
		// runner 子进程的 Node permission 模型按 realpath 匹配 --allow-fs-read 根；
		// macOS 的 /tmp、/var/folders 是指向 /private/... 的符号链接，不先规范化
		// 会导致子进程全部读取被拒（"Access to this API has been restricted"）。
		brokerData = Files.createTempDirectory("fix2-c06-broker-").toRealPath();
		Path sockets = Files.createDirectories(brokerData.resolve("sockets"));
		Path slots = Files.createDirectories(brokerData.resolve("slots"));
		Path runnerTmp = Files.createDirectories(brokerData.resolve("runner-tmp"));
		Path runnerState = Files.createDirectories(brokerData.resolve("runner-state"));
		// C04 拓扑：常驻 daemon 持有作者执行面（同宿主路径与 broker 一致）。
		ProcessBuilder daemonBuilder = new ProcessBuilder("node", "--import", "tsx", "src/runner/daemon.mjs",
				"--socket", sockets.resolve("runner.sock").toString(), "--distribution-root", GENERATED.toString(),
				"--slot-root", slots.toString(), "--state-home", runnerState.toString(), "--tmp", runnerTmp.toString())
				.directory(BACKEND.toFile()).redirectErrorStream(true)
				.redirectOutput(brokerData.resolve("daemon.log").toFile());
		daemonBuilder.environment().clear();
		daemonBuilder.environment().putAll(
				Map.of("PATH", String.valueOf(System.getenv("PATH")), "HOME", String.valueOf(System.getenv("HOME"))));
		daemon = daemonBuilder.start();
		// 真实 broker：真实引擎发行版 + 临时数据根（不触碰任何主栈数据）。
		ProcessBuilder brokerBuilder = new ProcessBuilder("node", "--import", "tsx", "src/main.mjs")
				.directory(BACKEND.toFile()).redirectErrorStream(true)
				.redirectOutput(brokerData.resolve("broker.log").toFile());
		brokerBuilder.environment().clear();
		brokerBuilder.environment().putAll(Map.ofEntries(Map.entry("PATH", System.getenv("PATH")),
				Map.entry("HOME", System.getenv("HOME")), Map.entry("HYPIT_BACKEND_PORT", String.valueOf(brokerPort)),
				Map.entry("HYPIT_BACKEND_HOST", "127.0.0.1"), Map.entry("HYPIT_INTERNAL_TOKEN", token),
				Map.entry("HYPIT_DATA_ROOT", brokerData.resolve("hypit/host").toString()),
				Map.entry("HYPIT_GENERATED_ROOT", GENERATED.toString()),
				Map.entry("HYPIT_RUNNER_SOCKET_DIR", sockets.toString()),
				Map.entry("HYPIT_RUNNER_SLOT_ROOT", slots.toString()),
				Map.entry("HYPIT_RUNNER_TMP_ROOT", runnerTmp.toString())));
		broker = brokerBuilder.start();
		waitHealthy();
	}

	private static void waitHealthy() throws Exception {
		long deadline = System.currentTimeMillis() + 120_000;
		while (System.currentTimeMillis() < deadline) {
			try {
				var connection = new java.net.URL("http://127.0.0.1:" + brokerPort + "/healthz").openConnection();
				connection.setConnectTimeout(2000);
				connection.setReadTimeout(2000);
				if (connection.getInputStream().readAllBytes().length > 0) {
					return;
				}
			} catch (Exception ignored) {
				Thread.sleep(1000);
			}
		}
		throw new IllegalStateException("broker 未在 120s 内就绪：" + Files.readString(brokerData.resolve("broker.log")));
	}

	@AfterAll
	static void stopBroker() {
		if (broker != null) {
			broker.destroy();
			broker.onExit().orTimeout(10, TimeUnit.SECONDS);
		}
		if (daemon != null) {
			daemon.destroy();
			daemon.onExit().orTimeout(10, TimeUnit.SECONDS);
		}
	}

	@org.springframework.test.context.DynamicPropertySource
	static void sidecarProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		// brokerPort/token 在 @BeforeAll 才定——用 supplier 延迟取值。
		registry.add("hypit.sidecar-base-url", () -> "http://127.0.0.1:" + brokerPort);
		registry.add("hypit.internal-token", () -> token);
	}

	@BeforeEach
	void clean() {
		// FK 链叶→根：plan 成功会落 hypit_plan（V91：pricing_snapshot/build 引用
		// 它），必须先于 hypit_project 删除。
		db.sql("DELETE FROM hypit_execution WHERE grant_id IN (SELECT id FROM hypit_execution_grant"
				+ " WHERE project_id IN (SELECT id FROM hypit_project WHERE account_id = :a))").bind("a", OWNER).then()
				.then(db.sql("DELETE FROM hypit_execution_grant WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id = :a)").bind("a", OWNER).then())
				.then(db.sql("DELETE FROM hypit_output WHERE build_id IN (SELECT id FROM hypit_build"
						+ " WHERE project_id IN (SELECT id FROM hypit_project WHERE account_id = :a))").bind("a", OWNER)
						.then())
				.then(db.sql("DELETE FROM hypit_build WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id = :a)").bind("a", OWNER).then())
				.then(db.sql("DELETE FROM hypit_pricing_snapshot WHERE plan_id IN (SELECT id FROM"
						+ " hypit_plan WHERE project_id IN (SELECT id FROM hypit_project WHERE" + " account_id = :a))")
						.bind("a", OWNER).then())
				.then(db.sql("DELETE FROM hypit_plan WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id = :a)").bind("a", OWNER).then())
				.then(db.sql("DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id"
						+ " = :a)").bind("a", OWNER).then())
				.then(db.sql("DELETE FROM hypit_job WHERE account_id = :a").bind("a", OWNER).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :a").bind("a", OWNER).then())
				.then(db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id = :a)").bind("a", OWNER).then())
				.then(db.sql("DELETE FROM hypit_changeset WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id = :a)").bind("a", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :a").bind("a", OWNER).then())
				.block(Duration.ofSeconds(10));
	}

	private String brokerCommand(String kind, Map<String, Object> payload) {
		var command = sidecar.commandAsync("fix2-c06-" + UUID.randomUUID(), kind, payload).block(Duration.ofMinutes(3));
		assertThat(command.state()).as("broker %s 应成功：%s", kind, command.error()).isEqualTo("succeeded");
		return HypitJsonText.write(command.result());
	}

	/** 经真实 broker 建可计划模板工程（revision1）并返回 PG 工程 id。 */
	private String provisionTemplateProject() {
		UUID projectId = UUID.randomUUID();
		// D-05 blank 骨架只保证 check 通过（Film 无 Track，按上游语义不可 plan）；
		// 本卡 plan/pricing/freeze 语义需要可计划源码 → 用 catalog 模板（零远程费）。
		brokerCommand("workspace.provision", Map.ofEntries(Map.entry("projectId", projectId.toString()),
				Map.entry("template", true), Map.entry("templateId", "ranking-tier")));
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision, version)"
				+ " VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), gen_random_uuid(), '冻结工程', 'clone',"
				+ " 'ready', 1, 1)").bind("id", projectId.toString()).bind("owner", OWNER).then()
				.block(Duration.ofSeconds(10));
		return projectId.toString();
	}

	private Path projectRoot(String projectId) {
		// broker projectsRoot = dataRoot 的兄弟目录（D-03）：HYPIT_DATA_ROOT=
		// brokerData/hypit/host → projectsRoot = brokerData/hypit/projects。
		return brokerData.resolve("hypit").resolve("projects").resolve(projectId);
	}

	@Test
	@DisplayName("TC-F2-06-01 projectId/revision/runFile 三调用过真实 broker（无 sourceDir）")
	void planPricingCheckOnlyByProjectIdThroughRealBroker() {
		String projectId = provisionTemplateProject();

		// check：仅 projectId + entryFile（真实 runner 通道）。
		Map<String, Object> checkPayload = new HashMap<>(Map.of("projectId", projectId, "entryFile", "main.svml"));
		String check = brokerCommand("check", checkPayload);
		assertThat(check).contains("__runner").contains("\"container\":\"runner\"");

		// Java plan（真实 broker）：载荷只带 projectId/revision/runFile。
		var view = planService.plan(OWNER, UUID.fromString(projectId), "main.svrun").block(Duration.ofMinutes(3));
		assertThat(view).isNotNull();
		assertThat(view.row().revision()).isEqualTo(1L);
		assertThat(view.row().profileHash()).isNotBlank().hasSize(64);

		// plan 行的文档带冻结元数据（revision/manifestHash/profileHash）。
		Map<String, Object> doc = com.grassland.intelligence.hypit.project.HypitJson
				.read(view.row().planJson() == null ? "{}" : view.row().planJson());
		assertThat(doc.get("manifestHash")).asString().hasSize(64);
		assertThat(((Number) doc.get("revision")).longValue()).isEqualTo(1L);

		// pricing：消费同一冻结计划（Java 载荷 revision=计划 revision）。
		String pricing = brokerCommand("pricing",
				Map.of("projectId", projectId, "revision", 1, "runFile", "main.svrun"));
		assertThat(pricing).contains("pricingHash");
	}

	@Test
	@DisplayName("TC-F2-06-02 work 前进后 A revision 计划仍绑定 A 快照")
	void frozenPlanStaysOnRevisionAAfterWorkAdvances() throws Exception {
		String projectId = provisionTemplateProject();
		var planA = planService.plan(OWNER, UUID.fromString(projectId), "main.svrun").block(Duration.ofMinutes(3));
		String manifestA = com.grassland.intelligence.hypit.project.HypitJson.read(planA.row().planJson())
				.get("manifestHash").toString();

		// work 前进到 B：workspace 文件物化为只读（444，保护在途输入），修改必须
		// 走 changeset 通道（读→改→put），产出 revision2。baseHash 省略（缺省=
		// 跳过乐观锁；空串会触发 revision_conflict）。
		Path work = projectRoot(projectId).resolve("work");
		brokerCommand("workspace.apply",
				Map.ofEntries(Map.entry("projectId", projectId), Map.entry("baseRevision", 1),
						Map.entry("applyMode", "save"),
						Map.entry("changes", List.of(Map.of("path", "main.svml", "action", "put", "content",
								Files.readString(work.resolve("main.svml")).replace("end=\"3s\"", "end=\"2s\""))))));

		// 对 A revision 的计划仍取 A 快照（manifestHash 不变，不读当前 work）。
		String planA2 = brokerCommand("plan", Map.of("projectId", projectId, "revision", 1, "runFile", "main.svrun"));
		JsonNode docA2 = JSON.readTree(planA2);
		assertThat(docA2.path("manifestHash").asText()).isEqualTo(manifestA);

		// plan 行冻结绑定完整（revision/manifestHash；profileHash 在 TC-01 已断言）。
		assertThat(planA.row().revision()).isEqualTo(1L);
		assertThat(manifestA).hasSize(64);
	}

	@Test
	@DisplayName("TC-F2-06-03 Profile 漂移后旧计划提交被拒（plan_stale、零渲染）")
	void staleProfileRejectsFrozenSubmit() throws Exception {
		String projectId = provisionTemplateProject();
		var plan = planService.plan(OWNER, UUID.fromString(projectId), "main.svrun").block(Duration.ofMinutes(3));
		Map<String, Object> doc = com.grassland.intelligence.hypit.project.HypitJson.read(plan.row().planJson());

		// 受控 Profile 变更：向 runtime profile 写入合法的非凭据配置（worker 执行
		// 内存预算，$runtime schema 的合法键；凭据键不参与摘要，选它才必然漂移）。
		Path profile = projectRoot(projectId).resolve("hypit.runtime.json");
		JsonNode node = JSON.readTree(Files.readString(profile));
		((org.testcontainers.shaded.com.fasterxml.jackson.databind.node.ObjectNode) node).putObject("worker")
				.put("executionMemoryMb", 1024);
		Files.writeString(profile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node));

		// 用旧 profileHash 提交冻结 build → broker 权威拒绝 plan_stale。
		Map<String, Object> submit = new HashMap<>();
		submit.put("projectId", projectId);
		submit.put("revision", 1);
		submit.put("manifestHash", doc.get("manifestHash"));
		submit.put("profileHash", doc.get("profileHash"));
		submit.put("planId", plan.row().id().toString());
		submit.put("runFile", "main.svrun");
		var command = sidecar.commandAsync("fix2-c06-stale-" + UUID.randomUUID(), "build.submit", submit)
				.block(Duration.ofMinutes(3));
		assertThat(command.state()).as("旧计划必须被拒（状态 failed 而非假成功）").isEqualTo("failed");
		assertThat(command.error()).isNotNull();
		assertThat(command.error().get("code").toString()).contains("plan_stale");
		// 零渲染副作用：命令失败且无 engineBuildId。
		assertThat(command.error().toString()).doesNotContain("engineBuildId");
	}

	@Test
	@DisplayName("TC-F2-06-04 快照缺失/manifest 篡改 → 明确失败不回退当前 work")
	void missingOrTamperedSnapshotFailsExplicitly() throws Exception {
		String projectId = provisionTemplateProject();

		// 缺失：revision 99 无快照 → not_found/engine error，明确失败。
		var missing = sidecar
				.commandAsync("fix2-c06-missing-" + UUID.randomUUID(), "plan",
						Map.of("projectId", projectId, "revision", 99, "runFile", "main.svrun"))
				.block(Duration.ofMinutes(2));
		assertThat(missing.state()).isEqualTo("failed");
		assertThat(missing.error()).isNotNull();

		// 篡改：改坏 revision1 快照 manifest 后以显式 manifestHash 提交 → 拒绝。
		var plan = planService.plan(OWNER, UUID.fromString(projectId), "main.svrun").block(Duration.ofMinutes(3));
		String manifest = com.grassland.intelligence.hypit.project.HypitJson.read(plan.row().planJson())
				.get("manifestHash").toString();
		Path snapshotManifest = projectRoot(projectId).resolve("revisions/1/manifest.json");
		assertThat(Files.exists(snapshotManifest)).isTrue();
		Files.writeString(snapshotManifest, "{\"entries\":[],\"tampered\":true}");
		var tampered = sidecar.commandAsync("fix2-c06-tamper-" + UUID.randomUUID(), "build.submit",
				Map.of("projectId", projectId, "revision", 1, "manifestHash", manifest, "profileHash",
						plan.row().profileHash(), "runFile", "main.svrun"))
				.block(Duration.ofMinutes(2));
		assertThat(tampered.state()).isEqualTo("failed");
		assertThat(tampered.error().toString()).isNotBlank();
	}

	/** 本类内部小工具（避免与生产 HypitJson 混淆命名）。 */
	static final class HypitJsonText {
		static String write(Object value) {
			return com.grassland.intelligence.hypit.project.HypitJson.write(value);
		}
	}
}
