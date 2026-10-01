package com.grassland.intelligence.hypit.fix2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.JsonNode;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;

/**
 * C107F2-05（107-fix-2）：按模板建工程与空工程首版初始化（F29）。
 *
 * <p>
 * 四组 TC（真实 PostgreSQL；sidecar WireMock 桩只验证 Java 半边契约——真实 broker 的模板克隆/blank
 * 骨架由 C03/C04 容器测试与 C08 纵向链路证明）：
 *
 * <ul>
 * <li>TC-F2-05-01 template 模式真实 templateId：缺 templateId 400；未命中 404； 不同模板
 * provision 载荷携带各自 templateId 且收敛出不同 manifestHash。</li>
 * <li>TC-F2-05-02 clone/brief 骨架 revision1：创建即 revision=1 + revision 行； 首个
 * Agent job 不因 revision0 被拒。</li>
 * <li>TC-F2-05-03 同 requestId 并发仅一工程；broker 失败 provisioning_failed 不假
 * ready。</li>
 * <li>TC-F2-05-04 存量 revision0 双触发 bootstrap：仅一个 revision1，owner/title 不变。</li>
 * </ul>
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitFix2C05IT extends IntelligenceItSupport {

	private static final String OWNER_A = "cccccccc-0000-4000-8000-00000000010a";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	static {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@org.springframework.beans.factory.annotation.Autowired
	private com.grassland.intelligence.hypit.project.HypitProjectService projectService;

	@org.springframework.test.context.DynamicPropertySource
	static void sidecarProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		registry.add("hypit.internal-token", () -> "fix2-c05-internal-token-0123456789abcdef");
	}

	@BeforeEach
	void clean() {
		SIDECAR.resetAll();
		db.sql("DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id" + " = :a)")
				.bind("a", OWNER_A).then()
				.then(db.sql("DELETE FROM hypit_job WHERE account_id = :a").bind("a", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :a").bind("a", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id = :a)").bind("a", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_changeset WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id = :a)").bind("a", OWNER_A).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :a").bind("a", OWNER_A).then())
				.block(java.time.Duration.ofSeconds(10));
	}

	// ── 桩 ────────────────────────────────────────────────────────────────
	private void stubTemplateDetail(String templateId, String materialState) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("templates.detail"))
				.withRequestBody(containing(templateId))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"templates.detail","state":"succeeded",
						 "result":{"templateId":"%s","title":"%s","materialState":"%s",
						   "sourcePath":"platform-hypit/templates/%s","runPaths":["main.svrun"]}}
						""".formatted(templateId, templateId, materialState, templateId))));
	}

	private void stubProvisionWithHash(String manifestHash) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.provision"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"workspace.provision","state":"succeeded",
						 "result":{"projectRoot":"/tmp/p","state":"created","skeleton":"blank",
						   "head":{"revision":1,"manifestHash":"%s","updatedAt":"now"}}}
						""".formatted(manifestHash))));
	}

	/** TC-F2-05-01：按 templateId 区分的 provision 桩（不同模板→不同 manifestHash）。 */
	private void stubProvisionForTemplate(String templateId, String manifestHash) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.provision"))
				.withRequestBody(containing(templateId))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"workspace.provision","state":"succeeded",
						 "result":{"projectRoot":"/tmp/p","state":"created","templateId":"%s",
						   "head":{"revision":1,"manifestHash":"%s","updatedAt":"now"}}}
						""".formatted(templateId, manifestHash))));
	}

	private void stubProvisionFailure() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.provision"))
				.willReturn(aResponse().withStatus(500).withBody("boom")));
	}

	private JsonNode create(String owner, UUID requestId, String title, String mode, String templateId) {
		String templateField = templateId == null ? "" : ",\"templateId\":\"" + templateId + "\"";
		String body = client().post().uri("/api/hypit/projects").header("X-Grassland-Identity", sign(owner, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"requestId\":\"%s\",\"title\":\"%s\",\"mode\":\"%s\"%s}".formatted(requestId, title, mode,
						templateField))
				.exchange().expectStatus().isEqualTo(202).expectBody(String.class).returnResult().getResponseBody();
		try {
			return JSON.readTree(body == null ? "{}" : body);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	// ── TC-F2-05-01 ───────────────────────────────────────────────────────
	@Test
	@org.junit.jupiter.api.DisplayName("TC-F2-05-01 template 模式真实 templateId 与内容区分")
	void templateModeUsesRealTemplateIdAndDistinctContent() {
		stubTemplateDetail("ranking-tier", "ready");
		stubTemplateDetail("caption-motion", "ready");
		stubProvisionForTemplate("ranking-tier", "b".repeat(64));
		stubProvisionForTemplate("caption-motion", "c".repeat(64));

		// 缺 templateId：400（§5.1 template 必须携带 catalog 内 templateId）。
		client().post().uri("/api/hypit/projects").header("X-Grassland-Identity", sign(OWNER_A, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(
						"{\"requestId\":\"%s\",\"title\":\"无模板\",\"mode\":\"template\"}".formatted(UUID.randomUUID()))
				.exchange().expectStatus().isBadRequest();

		// 未命中 catalog：404（detail 桩返回 failed not_found → Java 映射 404）。
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("templates.detail"))
				.withRequestBody(containing("no-such-template"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"x\",\"state\":\"failed\","
								+ "\"error\":{\"code\":\"not_found\",\"message\":\"template not found\"}}")));
		client().post().uri("/api/hypit/projects").header("X-Grassland-Identity", sign(OWNER_A, null))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(("{\"requestId\":\"%s\",\"title\":\"未知模板\",\"mode\":\"template\","
						+ "\"templateId\":\"no-such-template\"}").formatted(UUID.randomUUID()))
				.exchange().expectStatus().isNotFound();

		// 两个不同模板：provision 载荷各携 templateId，工程 manifestHash 各自不同。
		JsonNode ranking = create(OWNER_A, UUID.randomUUID(), "榜单", "template", "ranking-tier");
		JsonNode caption = create(OWNER_A, UUID.randomUUID(), "字幕", "template", "caption-motion");
		String rankingId = ranking.path("data").path("project").path("id").asText();
		String captionId = caption.path("data").path("project").path("id").asText();
		assertThat(rankingId).isNotBlank();
		assertThat(captionId).isNotBlank().isNotEqualTo(rankingId);

		assertThat(SIDECAR.countRequestsMatching(postRequestedFor(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("workspace.provision")).withRequestBody(containing("ranking-tier")).build())
				.getCount()).isEqualTo(1);
		assertThat(SIDECAR.countRequestsMatching(postRequestedFor(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("workspace.provision")).withRequestBody(containing("caption-motion"))
				.build()).getCount()).isEqualTo(1);

		String rankingHash = db.sql("SELECT head_manifest_hash FROM hypit_project WHERE id = CAST(:id AS uuid)")
				.bind("id", rankingId).map((row, meta) -> row.get("head_manifest_hash", String.class)).one().block();
		String captionHash = db.sql("SELECT head_manifest_hash FROM hypit_project WHERE id = CAST(:id AS uuid)")
				.bind("id", captionId).map((row, meta) -> row.get("head_manifest_hash", String.class)).one().block();
		assertThat(rankingHash).isNotEqualTo(captionHash);
	}

	// ── TC-F2-05-02 ───────────────────────────────────────────────────────
	@Test
	@org.junit.jupiter.api.DisplayName("TC-F2-05-02 clone blank 骨架 revision1 + Agent 接受")
	void cloneModeGetsBlankSkeletonRevisionOneAndAgentJobAccepted() {
		stubProvisionWithHash("d".repeat(64));
		JsonNode created = create(OWNER_A, UUID.randomUUID(), "复刻工程", "clone", null);
		String projectId = created.path("data").path("project").path("id").asText();
		assertThat(projectId).isNotBlank();

		Long revision = db.sql("SELECT revision FROM hypit_project WHERE id = CAST(:id AS uuid)").bind("id", projectId)
				.map((row, meta) -> row.get("revision", Long.class)).one().block();
		Long revisionRows = db.sql("SELECT COUNT(*) AS c FROM hypit_revision WHERE project_id = CAST(:id AS uuid)")
				.bind("id", projectId).map((row, meta) -> row.get("c", Long.class)).one().block();
		assertThat(revision).isEqualTo(1L);
		assertThat(revisionRows).isEqualTo(1L);

		// provision 载荷不带 template:true（clone → broker 落 blank 骨架）。
		assertThat(SIDECAR.countRequestsMatching(postRequestedFor(urlPathEqualTo("/internal/v1/commands"))
				.withRequestBody(containing("workspace.provision")).withRequestBody(containing("\"template\":true"))
				.build()).getCount()).isEqualTo(0);

		// 首个 Agent job：revision≥1 门通过（不再因 revision0 拒绝）。
		stubAgentPlan();
		client().post().uri("/api/hypit/projects/{id}/agent-jobs", projectId)
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(
						"{\"requestId\":\"%s\",\"intent\":\"author\",\"brief\":\"首个任务\"}".formatted(UUID.randomUUID()))
				.exchange().expectStatus().isEqualTo(202);
	}

	private void stubAgentPlan() {
		// Agent planner 走平台执行环（这里桩掉 sidecar 面；agent 内部不触 broker check）。
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.files"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"x\",\"state\":\"succeeded\","
								+ "\"result\":{\"files\":[\"main.svml\",\"main.svrun\",\"style.svs\"],\"revision\":1}}")));
	}

	// ── TC-F2-05-03 ───────────────────────────────────────────────────────
	@Test
	@org.junit.jupiter.api.DisplayName("TC-F2-05-03 同键并发单工程 + 失败不假 ready")
	void concurrentSameRequestIdCreatesOneProjectAndBrokerFailureIsProvisioningFailed() {
		stubProvisionWithHash("e".repeat(64));
		UUID requestId = UUID.randomUUID();
		JsonNode first = create(OWNER_A, requestId, "并发工程", "brief", null);
		JsonNode second = create(OWNER_A, requestId, "并发工程", "brief", null);
		assertThat(second.path("data").path("project").path("id").asText())
				.isEqualTo(first.path("data").path("project").path("id").asText());
		Long count = db.sql("SELECT COUNT(*) AS c FROM hypit_project WHERE account_id = :owner").bind("owner", OWNER_A)
				.map((row, meta) -> row.get("c", Long.class)).one().block();
		assertThat(count).isEqualTo(1L);

		// broker 失败：provisioning_failed，不假 ready。
		stubProvisionFailure();
		JsonNode failed = create(OWNER_A, UUID.randomUUID(), "失败工程", "brief", null);
		String failedId = failed.path("data").path("project").path("id").asText();
		assertThat(failed.path("data").path("job").path("state").asText()).isEqualTo("failed");
		String status = db.sql("SELECT status FROM hypit_project WHERE id = CAST(:id AS uuid)").bind("id", failedId)
				.map((row, meta) -> row.get("status", String.class)).one().block();
		assertThat(status).isEqualTo("provisioning_failed");
	}

	// ── TC-F2-05-04 ───────────────────────────────────────────────────────
	@Test
	@org.junit.jupiter.api.DisplayName("TC-F2-05-04 存量 revision0 并发 bootstrap 幂等")
	void legacyRevisionZeroBootstrapIsIdempotentUnderConcurrentTrigger() {
		// 直插存量 revision0 ready 工程（模拟 107-3 时期数据；不走 provision）。
		UUID projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision, version)"
				+ " VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), gen_random_uuid(), '存量工程', 'clone',"
				+ " 'ready', 0, 1)").bind("id", projectId.toString()).bind("owner", OWNER_A).then().block();

		stubProvisionWithHash("f".repeat(64));
		// 两客户端并发触发首版初始化。
		var both = reactor.core.publisher.Mono.when(projectService.ensureInitialRevision(OWNER_A, projectId),
				projectService.ensureInitialRevision(OWNER_A, projectId));
		both.block(java.time.Duration.ofSeconds(20));

		Long revisionRows = db
				.sql("SELECT COUNT(*) AS c FROM hypit_revision WHERE project_id = CAST(:id AS uuid)"
						+ " AND number = 1")
				.bind("id", projectId.toString()).map((row, meta) -> row.get("c", Long.class)).one().block();
		assertThat(revisionRows).isEqualTo(1L);
		Long revision = db.sql("SELECT revision FROM hypit_project WHERE id = CAST(:id AS uuid)")
				.bind("id", projectId.toString()).map((row, meta) -> row.get("revision", Long.class)).one().block();
		assertThat(revision).isEqualTo(1L);
		// owner/title/sourceContext 不变。
		String title = db.sql("SELECT title FROM hypit_project WHERE id = CAST(:id AS uuid)")
				.bind("id", projectId.toString()).map((row, meta) -> row.get("title", String.class)).one().block();
		String owner = db.sql("SELECT account_id FROM hypit_project WHERE id = CAST(:id AS uuid)")
				.bind("id", projectId.toString()).map((row, meta) -> row.get("account_id", String.class)).one().block();
		assertThat(title).isEqualTo("存量工程");
		assertThat(owner).isEqualTo(OWNER_A);
	}
}
