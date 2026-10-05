package com.grassland.intelligence.hypit.fix3;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
import com.grassland.intelligence.hypit.agent.HypitAgentScope;
import com.grassland.intelligence.hypit.agent.HypitAgentToolRegistry;
import com.grassland.intelligence.hypit.agent.HypitAgentToolRegistry.DispatchOutcome;
import com.grassland.intelligence.hypit.agent.HypitAgentToolRegistry.ToolCall;
import com.grassland.intelligence.hypit.asset.HypitArchiveService;
import com.grassland.intelligence.hypit.build.HypitBuildRepository;
import com.grassland.intelligence.hypit.build.HypitOutputRepository;
import com.grassland.intelligence.hypit.build.HypitPlanRepository;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge;
import com.grassland.intelligence.media.HypitMediaArchiveAdapter;
import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.PresignRequest;
import com.grassland.storage.StoredObject;
import com.grassland.storage.UploadTicket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
import reactor.core.publisher.Mono;

/**
 * C107F3-09（任务书 107-fix-3 §12.2 TC-F3-09-01～03 / RULE-004 / D-11）：Agent
 * 归档工程归属闸。
 *
 * <p>
 * 真 PostgreSQL + 真实归档生产服务（W65 HypitArchiveService 原样复用）；仅 sidecar 导出/资源 HTTP
 * 与媒体存储末跳用记录式替身（WireMock + 计数对象存储，同 output 稳定媒体 ID）—— 归档调用数以 sidecar
 * results.export 请求数 / 对象 PUT 数 / media_reference 行数三面观测（TC-F3-09-01「spy 记录
 * archive 调用」）。
 *
 * <ul>
 * <li>TC-F3-09-01 归档 owner 与 output 归属：A 合法 output 真实归档恰一次；B 工程 output 与未知
 * outputId 统一 ok=false + hypit_not_found + scopeRefusal=true，归档 0
 * 调用，不泄漏名称/mediaId。</li>
 * <li>TC-F3-09-02 合法重放与已归档输出：同合法输出重复 dispatch 复用同一确定性归档媒体，不新增重复 media/归档，owner
 * 保持 A；自动归档路径（内部 archiveOutput 直调）兼容。</li>
 * <li>TC-F3-09-03 删除撤权及旧工具回归：dispatch 订阅前屏障删除工程 / accountId=B 但 projectId=A →
 * 订阅时校验拒绝、0 归档副作用；原 build.status 工程隔离正反例保持。</li>
 * </ul>
 *
 * <p>
 * 防假阳性（§12.2）：删掉 output→build→project 比较应让「B 工程 output」用例失败（当前源码即无闸——
 * 本类先红后绿）；只在装配 Mono 时检查、订阅时跳过检查会在 TC-F3-09-03 屏障用例失败。
 */
@TestPropertySource(properties = {"hypit.enabled=true", "hypit.agent-worker.enabled=false"})
class HypitFix3ArchiveIT extends IntelligenceItSupport {

	/** §12.2 共享前提 F-OWN：A/B 固定测试 UUID，归属不共享。 */
	private static final String OWNER_A = "aaaaaaaa-1073-4000-8000-000000000001";
	private static final String OWNER_B = "bbbbbbbb-1073-4000-8000-000000000002";

	private static final byte[] MP4_BYTES = {0, 0, 0, 8, 'f', 't', 'y', 'p'};
	private static final String HANDLE = "res-fix3c09-00000000000000001";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	@MockitoBean
	private CreditsClient credits;

	@Autowired
	HypitAgentToolRegistry registry;

	@Autowired
	HypitArchiveService archives;

	@Autowired
	HypitPlanRepository plans;

	@Autowired
	HypitBuildRepository builds;

	@Autowired
	HypitOutputRepository outputs;

	@Autowired
	DatabaseClient db;

	@Autowired
	ObjectStorageAdapter storage;

	/** 媒体存储末跳记录式替身：同 output 稳定媒体 key，PUT 计数即归档调用证据。 */
	@TestConfiguration
	static class StorageConfig {
		@Bean
		ObjectStorageAdapter hypitFix3ArchiveTestStorage() {
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
		registry.add("hypit.internal-token", () -> "fix3-c09-internal-token-0123456789abcdef");
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
	}

	@BeforeAll
	static void startSidecar() {
		SIDECAR.start();
		String sha = HypitExternalExecutionBridge.sha256Hex(new String(MP4_BYTES));
		// 归档链（output.archive）：sidecar results.export → 内部资源端点回读真实字节。
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("results.export"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","state":"succeeded","result":{"kind":"resource","mediaType":"video/mp4",
						 "size":8,"sha256":"%s","handle":"%s"}}
						""".formatted(sha, HANDLE))));
		SIDECAR.stubFor(get(urlEqualTo("/internal/v1/resources/" + HANDLE))
				.willReturn(aResponse().withHeader("Content-Type", "video/mp4").withBody(MP4_BYTES)));
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	private UUID projectA;
	private UUID projectB;

	@BeforeEach
	void seed() {
		reset(credits);
		CreditsStubs.stubDefaults(credits);
		SIDECAR.resetRequests();
		((CountingStorage) storage).puts.clear();
		cleanup();
		projectA = seedReadyProject(OWNER_A, "fix3-c09-a");
		projectB = seedReadyProject(OWNER_B, "fix3-c09-b");
	}

	@AfterEach
	void sweep() {
		cleanup();
	}

	/** 每例独立数据命名空间：A/B 两 owner 全链自清（§12.2 共享前提）。 */
	private void cleanup() {
		for (String owner : List.of(OWNER_A, OWNER_B)) {
			db.sql("DELETE FROM hypit_job_action WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
					.bind("o", owner).then()
					.then(db.sql(
							"DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id = :o)")
							.bind("o", owner).then())
					.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", owner).then())
					.then(db.sql("DELETE FROM hypit_output WHERE build_id IN (SELECT b.id FROM hypit_build b"
							+ " JOIN hypit_project p ON p.id = b.project_id WHERE p.account_id = :o)").bind("o", owner)
							.then())
					.then(db.sql("DELETE FROM hypit_build WHERE project_id IN (SELECT id FROM hypit_project"
							+ " WHERE account_id = :o)").bind("o", owner).then())
					.then(db.sql("DELETE FROM hypit_plan WHERE project_id IN (SELECT id FROM hypit_project"
							+ " WHERE account_id = :o)").bind("o", owner).then())
					.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", owner).then())
					.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", owner).then())
					.then(db.sql("DELETE FROM media_reference WHERE owner_account_id = :o").bind("o", owner).then())
					.block(Duration.ofSeconds(20));
		}
	}

	private UUID seedReadyProject(String owner, String title) {
		UUID projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), :title, 'clone', 'ready', 2)")
				.bind("id", projectId.toString()).bind("owner", owner).bind("ws", UUID.randomUUID().toString())
				.bind("title", title).then().block(Duration.ofSeconds(10));
		return projectId;
	}

	/** finished build + resource 输出行（归档链种子，沿 C14/C12 手法；每 build 唯一 engine id）。 */
	private UUID seedFinishedBuildWithOutput(String owner, UUID projectId, String outputName) {
		UUID planId = plans
				.insertPlan(UUID.randomUUID(), projectId, 2, "main.svrun", "c".repeat(64), "p".repeat(64), "{}")
				.block(Duration.ofSeconds(10)).id();
		UUID commandId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_command(id, account_id, project_id, target_key, action, request_id,"
				+ " payload_hash, payload_json, state) VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 't',"
				+ " 'build.submit', CAST(:r AS uuid), :h, '{}', 'acknowledged')").bind("id", commandId.toString())
				.bind("o", owner).bind("p", projectId.toString()).bind("r", UUID.randomUUID()).bind("h", "h".repeat(64))
				.then().block(Duration.ofSeconds(10));
		UUID buildId = builds.insert(UUID.randomUUID(), commandId, projectId, 2L, planId, "main.svrun")
				.flatMap(row -> db
						.sql("UPDATE hypit_build SET engine_build_id = :e, lifecycle = 'finished',"
								+ " outcome = 'complete', finished_at = now(), result_location = CAST('{}' AS jsonb)"
								+ " WHERE id = CAST(:id AS uuid)")
						.bind("e", "bld-fix3c09-" + UUID.randomUUID()).bind("id", row.id().toString()).then()
						.then(builds.findById(row.id())))
				.block(Duration.ofSeconds(10)).id();
		return outputs.insert(UUID.randomUUID(), buildId, outputName, "resource", "video/mp4", 8L,
				"{\"type\":\"video\",\"displayName\":\"成片\"}").block(Duration.ofSeconds(10)).id();
	}

	/** 真实 hypit_agent job 行（ToolCall.jobId 合法；不绕 dispatch 边界）。 */
	private UUID insertAgentJob(String owner, UUID projectId) {
		UUID jobId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_job(id, account_id, project_id, kind, state, checkpoint_json)"
				+ " VALUES (CAST(:id AS uuid), :o, CAST(:p AS uuid), 'hypit.agent', 'running', CAST('{}' AS jsonb))")
				.bind("id", jobId.toString()).bind("o", owner).bind("p", projectId.toString()).then()
				.block(Duration.ofSeconds(10));
		return jobId;
	}

	private DispatchOutcome dispatchArchive(HypitAgentScope scope, String accountId, UUID projectId, UUID jobId,
			UUID outputId) {
		return registry
				.dispatch(scope, "output.archive",
						new ToolCall(jobId, projectId, accountId, 2L, Map.of("outputId", outputId.toString())))
				.block(Duration.ofSeconds(30));
	}

	// ── 观测面：sidecar export 请求数 / 对象 PUT 数 / media 行数（spy 三面） ─────
	private long exportRequests() {
		return SIDECAR.countRequestsMatching(postRequestedFor(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("results.export")).build()).getCount();
	}

	private long storagePutsFor(UUID outputId) {
		String key = "hypit/output/" + HypitMediaArchiveAdapter.deterministicMediaId(outputId);
		return ((CountingStorage) storage).puts.stream().filter(put -> put.equals(key)).count();
	}

	private long mediaRowsFor(UUID outputId) {
		Long count = db
				.sql("SELECT COUNT(*) AS n FROM media_reference WHERE domain_type = 'hypit_output'"
						+ " AND domain_id = :d")
				.bind("d", outputId.toString()).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		return count == null ? 0 : count;
	}

	private String mediaOwnerOf(UUID outputId) {
		return db
				.sql("SELECT owner_account_id AS o FROM media_reference WHERE domain_type = 'hypit_output'"
						+ " AND domain_id = :d")
				.bind("d", outputId.toString()).map((row, meta) -> row.get("o", String.class)).one()
				.block(Duration.ofSeconds(10));
	}

	private String outputArchiveState(UUID outputId) {
		return outputs.findById(outputId).block(Duration.ofSeconds(10)).archiveState();
	}

	/**
	 * 统一拒绝口径（RULE-004/§6.5）：ok=false + hypit_not_found +
	 * scopeRefusal=true，不泄漏名称/mediaId。
	 */
	private void assertRefused(DispatchOutcome outcome, UUID... leakProbes) throws Exception {
		assertThat(outcome.ok()).as("拒绝不伪成功").isFalse();
		assertThat(outcome.errorCode()).as("统一 hypit_not_found").isEqualTo("hypit_not_found");
		assertThat(outcome.scopeRefusal()).as("安全拒绝标记（worker 据此终止）").isTrue();
		assertThat(outcome.result()).as("失败结果不携带 mediaId").doesNotContainKey("mediaId");
		String serialized = JSON.writeValueAsString(outcome.result()) + String.valueOf(outcome.errorMessage());
		for (UUID probe : leakProbes) {
			assertThat(serialized).as("不泄漏对象标识 " + probe).doesNotContain(probe.toString());
		}
	}

	// ── TC-F3-09-01：归档 owner 与 output 归属 ───────────────────────────────
	@Test
	@DisplayName("TC-F3-09-01 A合法output归档恰一次；B工程/未知output统一 hypit_not_found+scopeRefusal，归档0调用不泄漏")
	void archiveOwnerAndOutputBelonging() throws Exception {
		UUID aOutput = seedFinishedBuildWithOutput(OWNER_A, projectA, "a-final.video");
		// B 工程 output 起可识别名——拒绝结果若泄漏 outputName/mediaId 在序列化断言现形。
		UUID bOutput = seedFinishedBuildWithOutput(OWNER_B, projectB, "b-private-final.video");
		UUID unknownOutput = UUID.randomUUID();
		UUID jobId = insertAgentJob(OWNER_A, projectA);
		HypitAgentScope scope = HypitAgentScope.authorScope();

		// A 合法：真实归档链恰一次（export 恰 1 次、PUT 恰 1 次、media 恰 1 行、owner=A）。
		DispatchOutcome legal = dispatchArchive(scope, OWNER_A, projectA, jobId, aOutput);
		assertThat(legal.ok()).as("A 本人当前工程 output 合法归档").isTrue();
		assertThat(String.valueOf(legal.result().get("archiveState"))).isEqualTo("archived");
		assertThat(String.valueOf(legal.result().get("mediaId")))
				.isEqualTo(HypitMediaArchiveAdapter.deterministicMediaId(aOutput).toString());
		assertThat(exportRequests()).as("A 合法归档 export 恰一次").isEqualTo(1L);
		assertThat(storagePutsFor(aOutput)).as("A 合法归档对象 PUT 恰一次").isEqualTo(1L);
		assertThat(mediaRowsFor(aOutput)).as("A 合法归档 media 恰一行").isEqualTo(1L);
		assertThat(mediaOwnerOf(aOutput)).as("归档 owner 保持工程 owner A").isEqualTo(OWNER_A);

		// B 工程 output（其余字段全合法）：统一拒绝 + 归档 0 调用 + 输出保持 pending。
		DispatchOutcome foreign = dispatchArchive(scope, OWNER_A, projectA, jobId, bOutput);
		assertRefused(foreign, bOutput, projectB);
		assertThat(String.valueOf(foreign.errorMessage())).doesNotContain("b-private-final.video");
		assertThat(outputArchiveState(bOutput)).as("B 输出行未被认领").isEqualTo("pending");
		assertThat(exportRequests()).as("拒绝路径归档 0 调用（export 仍 1）").isEqualTo(1L);
		assertThat(storagePutsFor(bOutput)).as("拒绝路径 0 对象写").isZero();
		assertThat(mediaRowsFor(bOutput)).as("拒绝路径 0 media 行").isZero();

		// 未知 outputId：与 B 同口径。
		DispatchOutcome unknown = dispatchArchive(scope, OWNER_A, projectA, jobId, unknownOutput);
		assertRefused(unknown, unknownOutput);
		assertThat(exportRequests()).as("未知 ID 同样 0 调用").isEqualTo(1L);
		assertThat(mediaRowsFor(unknownOutput)).isZero();
	}

	// ── TC-F3-09-02：合法重放与已归档输出 ───────────────────────────────────
	@Test
	@DisplayName("TC-F3-09-02 同合法输出重复dispatch复用同一归档媒体不增重复media/归档；自动归档路径兼容")
	void legalReplayReusesArchiveMedia() {
		UUID pendingOutput = seedFinishedBuildWithOutput(OWNER_A, projectA, "replay.video");
		UUID archivedOutput = seedFinishedBuildWithOutput(OWNER_A, projectA, "auto.video");
		UUID jobId = insertAgentJob(OWNER_A, projectA);
		HypitAgentScope scope = HypitAgentScope.authorScope();
		String pendingMedia = HypitMediaArchiveAdapter.deterministicMediaId(pendingOutput).toString();

		// 未归档 output：第一次 dispatch 真实归档。
		DispatchOutcome first = dispatchArchive(scope, OWNER_A, projectA, jobId, pendingOutput);
		assertThat(first.ok()).as("首次归档成功").isTrue();
		assertThat(String.valueOf(first.result().get("mediaId"))).isEqualTo(pendingMedia);
		assertThat(exportRequests()).isEqualTo(1L);

		// 同固定 ToolCall/request 标识重放：复用同一归档媒体，零新增 export/PUT/media。
		DispatchOutcome replay = dispatchArchive(scope, OWNER_A, projectA, jobId, pendingOutput);
		assertThat(replay.ok()).as("重放不伪失败").isTrue();
		assertThat(String.valueOf(replay.result().get("mediaId"))).isEqualTo(pendingMedia);
		assertThat(String.valueOf(replay.result().get("archiveState"))).isEqualTo("archived");
		assertThat(exportRequests()).as("重放零新 export").isEqualTo(1L);
		assertThat(storagePutsFor(pendingOutput)).as("重放零新对象 PUT").isEqualTo(1L);
		assertThat(mediaRowsFor(pendingOutput)).as("不新增重复 media/归档").isEqualTo(1L);

		// 已归档 output：自动归档路径（内部 archiveOutput 直调，W65 语义保持）先产出现状。
		archives.archiveOutput(archivedOutput).block(Duration.ofSeconds(30));
		assertThat(mediaRowsFor(archivedOutput)).as("自动归档路径落一行 media").isEqualTo(1L);
		assertThat(mediaOwnerOf(archivedOutput)).isEqualTo(OWNER_A);
		String autoMedia = HypitMediaArchiveAdapter.deterministicMediaId(archivedOutput).toString();
		// 基线捕获：此时 export = pending 首归档 1 + 自动归档 1；重放断言只看增量。
		long exportsBeforeReplays = exportRequests();
		assertThat(exportsBeforeReplays).as("前序恰两次真实归档").isEqualTo(2L);

		// registry 重复 dispatch 两次：同媒体、零副作用、owner 保持 A。
		DispatchOutcome replayOne = dispatchArchive(scope, OWNER_A, projectA, jobId, archivedOutput);
		DispatchOutcome replayTwo = dispatchArchive(scope, OWNER_A, projectA, jobId, archivedOutput);
		assertThat(replayOne.ok()).as("已归档重放一 ok").isTrue();
		assertThat(replayTwo.ok()).as("已归档重放二 ok").isTrue();
		assertThat(String.valueOf(replayOne.result().get("mediaId"))).isEqualTo(autoMedia);
		assertThat(String.valueOf(replayTwo.result().get("mediaId"))).isEqualTo(autoMedia);
		assertThat(exportRequests()).as("已归档重放零新 export").isEqualTo(exportsBeforeReplays);
		assertThat(storagePutsFor(archivedOutput)).as("已归档重放零新 PUT").isEqualTo(1L);
		assertThat(mediaRowsFor(archivedOutput)).as("已归档重放不增 media").isEqualTo(1L);
		assertThat(mediaOwnerOf(archivedOutput)).isEqualTo(OWNER_A);
	}

	// ── TC-F3-09-03：删除撤权及旧工具回归 ───────────────────────────────────
	@Test
	@DisplayName("TC-F3-09-03 订阅前屏障删除工程/换账号B→订阅时拒绝0归档；与unknown同口径；build.status隔离保持")
	void deletedProjectAndAccountSwitchRefuseAtSubscription() throws Exception {
		UUID aOutput = seedFinishedBuildWithOutput(OWNER_A, projectA, "barrier.video");
		UUID aBuild = outputs.findById(aOutput).block(Duration.ofSeconds(10)).buildId();
		UUID bOutput = seedFinishedBuildWithOutput(OWNER_B, projectB, "b-status.video");
		UUID bBuild = outputs.findById(bOutput).block(Duration.ofSeconds(10)).buildId();
		UUID jobId = insertAgentJob(OWNER_A, projectA);
		HypitAgentScope scope = HypitAgentScope.authorScope();

		// accountId=B 但 projectId=A（订阅时 owner 校验拒绝；与 unknown 同口径）。
		DispatchOutcome switched = dispatchArchive(scope, OWNER_B, projectA, jobId, aOutput);
		assertRefused(switched, aOutput);
		assertThat(outputArchiveState(aOutput)).isEqualTo("pending");
		assertThat(exportRequests()).as("换账号 0 归档调用").isZero();

		// 旧工具回归：build.status 正反例隔离保持（原语义不动）。
		DispatchOutcome statusOwn = registry
				.dispatch(scope, "build.status",
						new ToolCall(jobId, projectA, OWNER_A, 2L, Map.of("buildId", aBuild.toString())))
				.block(Duration.ofSeconds(30));
		assertThat(statusOwn.ok()).as("build.status 合法输入保持通过").isTrue();
		DispatchOutcome statusForeign = registry
				.dispatch(scope, "build.status",
						new ToolCall(jobId, projectA, OWNER_A, 2L, Map.of("buildId", bBuild.toString())))
				.block(Duration.ofSeconds(30));
		assertThat(statusForeign.ok()).as("build.status 跨工程隔离保持拒绝").isFalse();
		assertThat(statusForeign.errorCode()).isEqualTo("hypit_not_found");

		// 订阅前屏障：先装配 dispatch Mono（未订阅），删除工程后再订阅——校验必须读订阅时现状。
		Mono<DispatchOutcome> assembled = registry.dispatch(scope, "output.archive",
				new ToolCall(jobId, projectA, OWNER_A, 2L, Map.of("outputId", aOutput.toString())));
		db.sql("UPDATE hypit_project SET status = 'deleted', deleted_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("id", projectA.toString()).then().block(Duration.ofSeconds(10));
		DispatchOutcome afterDelete = assembled.block(Duration.ofSeconds(30));
		assertRefused(afterDelete, aOutput);
		assertThat(outputArchiveState(aOutput)).as("删除工程后输出未被认领").isEqualTo("pending");
		assertThat(exportRequests()).as("删除工程 0 归档调用").isZero();
		assertThat(storagePutsFor(aOutput)).as("删除工程 0 对象写").isZero();
		assertThat(mediaRowsFor(aOutput)).as("删除工程 0 media 行").isZero();
	}
}
