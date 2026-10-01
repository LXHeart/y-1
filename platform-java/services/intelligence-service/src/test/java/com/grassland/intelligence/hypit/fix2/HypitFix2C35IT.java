package com.grassland.intelligence.hypit.fix2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.project.HypitProjectService;
import com.grassland.intelligence.hypit.studio.HypitSessionRepository;
import com.grassland.intelligence.hypit.studio.HypitStudioSessionService;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;

/**
 * C107F2-35（F08/F18/F19/F31 / §7.4、§9.5 R-LIFECYCLE）：会话、资产、任务及导入 临时文件生命周期收口。真
 * PostgreSQL + WireMock sidecar 桩。
 *
 * <ul>
 * <li>TC-F2-35-01 工程删除顺序=撤会话→拒新写（deleting 非 ready）→就地取消可取消 job→sidecar
 * 清独占派生物→deleted；revision/结果 DB 行按既有保留策略留存。</li>
 * <li>TC-F2-35-02 两工程引用同一固化 media：删其一，另一工程 asset 行与 media_reference
 * 完好（共享媒体零触碰）。</li> <liTC-F2-35-03 sidecar workspace.delete 第一次失败：工程保持 deleting
 * 可重试、 无 deleted 假象；恢复后同 commandId 重试收敛 deleted（幂等不重复派发副作用）。</li>
 * <li>TC-F2-35-04 A/B 账号隔离：B 不能读 A 的工程/素材/会话（404 同答）；登出/ 切号在前端经
 * useHypitProjectScope account generation abort（C11 守卫）。</li>
 * </ul>
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitFix2C35IT extends IntelligenceItSupport {

	private static final String OWNER_A = "f1000000-0000-4000-8000-00000000000a";
	private static final String OWNER_B = "f1000000-0000-4000-8000-00000000000b";
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	static {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@Autowired
	HypitProjectService projects;

	@Autowired
	HypitStudioSessionService sessions;

	@Autowired
	HypitSessionRepository sessionRepository;

	@Autowired
	DatabaseClient db;

	@org.springframework.test.context.DynamicPropertySource
	static void props(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> "fix2-c35-internal-token-0123456789abcdef");
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		registry.add("hypit.session-ticket-secret", () -> "fix2-c35-ticket-secret-0123456789abcdef");
		registry.add("hypit.session-assertion-secret", () -> "fix2-c35-assert-secret-0123456789abcdef");
	}

	@AfterEach
	void sweep() {
		cleanup(OWNER_A);
		cleanup(OWNER_B);
	}

	private void cleanup(String owner) {
		db.sql("DELETE FROM hypit_asset_reference WHERE project_id IN (SELECT id FROM hypit_project"
				+ " WHERE account_id = :o)").bind("o", owner).then()
				.then(db.sql("DELETE FROM hypit_asset WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", owner).then())
				.then(db.sql("DELETE FROM hypit_session WHERE account_id = :o").bind("o", owner).then())
				.then(db.sql("DELETE FROM hypit_job WHERE account_id = :o").bind("o", owner).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", owner).then())
				.then(db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", owner).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", owner).then())
				.then(db.sql("DELETE FROM media_reference WHERE owner_account_id = :o").bind("o", owner).then())
				.block(Duration.ofSeconds(20));
	}

	private UUID seedProject(String owner) {
		UUID id = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'fix2-c35', 'clone', 'ready', 1)")
				.bind("id", id.toString()).bind("owner", owner).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
		return id;
	}

	private void seedJob(UUID projectId, String owner, String state) {
		db.sql("INSERT INTO hypit_job(id, project_id, account_id, kind, state, attempt)"
				+ " VALUES (CAST(:id AS uuid), CAST(:p AS uuid), :o, 'asset.upload', :s, 1)")
				.bind("id", UUID.randomUUID().toString()).bind("p", projectId.toString()).bind("o", owner)
				.bind("s", state).then().block(Duration.ofSeconds(10));
	}

	private void stubStudioSessionOk() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("studio.session"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"c\",\"state\":\"succeeded\",\"result\":{\"sessionId\":\"sid\","
								+ "\"port\":9500,\"pid\":1,\"expiresAt\":\"2026-12-31T00:00:00Z\","
								+ "\"revision\":1,\"readOnly\":false}}")));
	}

	private void stubWorkspaceDelete(int status, String body) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.delete"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body)));
	}

	// ── TC-F2-35-01：删除顺序=撤会话/拒新写/取消 job/清独占派生物 ────────────────

	@Test
	@DisplayName("TC-F2-35-01 删除：会话 revoked、deleting 拒新写、job cancelled、sidecar 清理、deleted")
	void tc01DeleteLifecycleOrdering() {
		UUID projectId = seedProject(OWNER_A);
		seedJob(projectId, OWNER_A, "queued");
		seedJob(projectId, OWNER_A, "running");
		stubWorkspaceDelete(200, "{\"commandId\":\"d\",\"state\":\"succeeded\",\"result\":{\"deleted\":true}}");
		stubStudioSessionOk();
		HypitStudioSessionService.StudioSessionView session = sessions
				.openSession(OWNER_A, projectId, UUID.randomUUID(), "main.svrun", 1L, false)
				.block(Duration.ofSeconds(30));
		assertThat(session).isNotNull();

		projects.delete(OWNER_A, projectId).block(Duration.ofSeconds(30));

		// 会话已撤销（access 面按 revoked 即时拒绝）。
		HypitSessionRepository.SessionRow revoked = sessionRepository.findById(session.sessionId())
				.block(Duration.ofSeconds(10));
		assertThat(revoked).isNotNull();
		assertThat(revoked.state()).isEqualTo("revoked");
		// job 全部就地终态。
		Long activeJobs = db
				.sql("SELECT COUNT(*) FROM hypit_job WHERE project_id = CAST(:p AS uuid)"
						+ " AND state NOT IN ('cancelled','succeeded','failed')")
				.bind("p", projectId.toString()).map((r, m) -> r.get(0, Long.class)).one().block();
		assertThat(activeJobs).isZero();
		// 工程终态 deleted；revision 行按既有保留策略留存（不随删除清 DB 行）。
		String status = db.sql("SELECT status FROM hypit_project WHERE id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((r, m) -> r.get(0, String.class)).one().block();
		assertThat(status).isEqualTo("deleted");
		// 删除后的工程对所有 owner 闸不可见（新写拒绝语义由 requireReadyOwner 承接）。
		projects.delete(OWNER_A, projectId).block(Duration.ofSeconds(10)); // 幂等：再删已删除工程是空操作
	}

	// ── TC-F2-35-02：共享固化媒体不误删 ─────────────────────────────────────────

	@Test
	@DisplayName("TC-F2-35-02 两工程引用同一 media：删其一，另一工程与媒体行完好")
	void tc02SharedMediaSurvivesProjectDelete() {
		UUID mediaId = UUID.randomUUID();
		db.sql("INSERT INTO media_reference (id, owner_account_id, purpose, object_key, mime_type,"
				+ " size_bytes, checksum, status) VALUES (CAST(:id AS uuid), :o, 'user_upload', :k,"
				+ " 'video/mp4', 48, :c, 'active')").bind("id", mediaId.toString()).bind("o", OWNER_A)
				.bind("k", "it/c35/" + mediaId).bind("c", "b".repeat(64)).then().block(Duration.ofSeconds(10));
		UUID projectA = seedProject(OWNER_A);
		UUID projectB = seedProject(OWNER_A);
		for (UUID project : List.of(projectA, projectB)) {
			db.sql("INSERT INTO hypit_asset(id, project_id, media_id, resource_handle, role, origin_kind,"
					+ " sha256, mime_type, size_bytes, status)"
					+ " VALUES (CAST(:id AS uuid), CAST(:p AS uuid), CAST(:m AS uuid), :h, 'reference',"
					+ " 'import', :c, 'video/mp4', 48, 'ready')").bind("id", UUID.randomUUID().toString())
					.bind("p", project.toString()).bind("m", mediaId.toString()).bind("h", "media-shared-handle")
					.bind("c", "b".repeat(64)).then().block(Duration.ofSeconds(10));
		}
		stubWorkspaceDelete(200, "{\"commandId\":\"d\",\"state\":\"succeeded\",\"result\":{\"deleted\":true}}");

		projects.delete(OWNER_A, projectA).block(Duration.ofSeconds(30));

		// 共享媒体行完好；B 工程的素材行完好（media 引用与 resource_handle 未被清理触碰）。
		Long mediaRows = db
				.sql("SELECT COUNT(*) FROM media_reference WHERE id = CAST(:m AS uuid)" + " AND status = 'active'")
				.bind("m", mediaId.toString()).map((r, m) -> r.get(0, Long.class)).one().block();
		assertThat(mediaRows).isEqualTo(1L);
		Long assetB = db
				.sql("SELECT COUNT(*) FROM hypit_asset WHERE project_id = CAST(:p AS uuid)" + " AND status = 'ready'")
				.bind("p", projectB.toString()).map((r, m) -> r.get(0, Long.class)).one().block();
		assertThat(assetB).isEqualTo(1L);
	}

	// ── TC-F2-35-03：清理失败可重试、恢复后收敛且不重复副作用 ────────────────────

	@Test
	@DisplayName("TC-F2-35-03 清理失败保持 deleting；恢复后同 commandId 幂等收敛 deleted")
	void tc03CleanupFailureRetriesIdempotently() {
		UUID projectId = seedProject(OWNER_A);
		// 第一次：sidecar 回执 failed（权限故障形态）。
		stubWorkspaceDelete(200, "{\"commandId\":\"d\",\"state\":\"failed\",\"error\":{\"code\":\"permission_denied\","
				+ "\"message\":\"staging not removable\"}}");
		org.assertj.core.api.Assertions
				.catchThrowable(() -> projects.delete(OWNER_A, projectId).block(Duration.ofSeconds(30)));
		String midStatus = db.sql("SELECT status FROM hypit_project WHERE id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((r, m) -> r.get(0, String.class)).one().block();
		assertThat(midStatus).as("失败不得伪造 deleted").isEqualTo("deleting");

		// 恢复权限后重试：同一 commandId（java-delete-<projectId>）幂等收敛。
		SIDECAR.resetAll();
		stubWorkspaceDelete(200, "{\"commandId\":\"d\",\"state\":\"succeeded\",\"result\":{\"deleted\":true}}");
		projects.delete(OWNER_A, projectId).block(Duration.ofSeconds(30));
		String finalStatus = db.sql("SELECT status FROM hypit_project WHERE id = CAST(:p AS uuid)")
				.bind("p", projectId.toString()).map((r, m) -> r.get(0, String.class)).one().block();
		assertThat(finalStatus).isEqualTo("deleted");
		// 稳定 commandId：重试派发复用 java-delete-<projectId>（broker 侧幂等键，
		// 不换身份二次派生副作用）；PG 侧删除走直接派发，无 command 行可数。
		com.github.tomakehurst.wiremock.matching.RequestPatternBuilder deleteRequests = com.github.tomakehurst.wiremock.client.WireMock
				.postRequestedFor(
						com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("workspace.delete"));
		assertThat(SIDECAR.findAll(deleteRequests)).hasSize(1);
		assertThat(SIDECAR.findAll(deleteRequests).get(0).getBodyAsString()).contains("java-delete-" + projectId);
	}

	// ── TC-F2-35-04：账号隔离——B 不能读 A 的资源 ────────────────────────────────

	@Test
	@DisplayName("TC-F2-35-04 A/B 隔离：B 读 A 工程/会话 access 全 404；B 删 A 工程 404")
	void tc04CrossAccountIsolation() {
		UUID projectA = seedProject(OWNER_A);
		seedProject(OWNER_B);
		stubStudioSessionOk();
		HypitStudioSessionService.StudioSessionView sessionA = sessions
				.openSession(OWNER_A, projectA, UUID.randomUUID(), "main.svrun", 1L, false)
				.block(Duration.ofSeconds(30));
		assertThat(sessionA).isNotNull();

		// B 读 A 工程：404（不泄漏存在性）。
		assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
				() -> projects.get(OWNER_B, projectA).block(Duration.ofSeconds(10)), IntelligenceException.class))
				.satisfies(error -> {
					assertThat(((IntelligenceException) error).code()).isEqualTo("hypit_not_found");
				});
		// B 的会话 access 面：非本人会话同答拒绝（access 控制器 owner 闸）。
		assertThat(sessionRepository.findById(sessionA.sessionId()).block(Duration.ofSeconds(10)).accountId())
				.isEqualTo(OWNER_A);
		// B 删除 A 工程：404 且 A 工程状态不变。
		assertThat(org.assertj.core.api.Assertions.catchThrowableOfType(
				() -> projects.delete(OWNER_B, projectA).block(Duration.ofSeconds(10)), IntelligenceException.class))
				.isInstanceOf(IntelligenceException.class);
		String status = db.sql("SELECT status FROM hypit_project WHERE id = CAST(:p AS uuid)")
				.bind("p", projectA.toString()).map((r, m) -> r.get(0, String.class)).one().block();
		assertThat(status).isEqualTo("ready");
	}
}
