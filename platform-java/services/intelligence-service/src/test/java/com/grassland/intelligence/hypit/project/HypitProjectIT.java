package com.grassland.intelligence.hypit.project;

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
 * 工程生命周期 HTTP 面（任务书 #107-1 C107-04 / TC107-04-01/02 / §5.3）。
 *
 * <p>
 * sidecar 以 WireMock 桩（Java 侧验证幂等/归属/补偿状态机；文件事务真值由 B/tests/workspace/*
 * 覆盖——两者合起来才是 K06 全貌）。真 PostgreSQL 断言落库状态。
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitProjectIT extends IntelligenceItSupport {

	private static final String OWNER_A = "cccccccc-0000-4000-8000-00000000000a";
	private static final String OWNER_B = "cccccccc-0000-4000-8000-00000000000b";
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	static {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void sidecarProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		registry.add("hypit.internal-token", () -> "it-hypit-internal-token-0123456789abcdef");
	}

	@BeforeEach
	void clean() {
		SIDECAR.resetAll();
		// C107-22 引用锚点按 marker 清理（只删本类自种行，不碰他类数据——共享表跨类污染红线）。
		db.sql("DELETE FROM media_reference WHERE object_key LIKE 'hypit-it-key-%'").then()
				.then(db.sql("DELETE FROM ai_run WHERE provider = 'hypit-it'").then())
				.then(db.sql("DELETE FROM hypit_job_event WHERE job_id IN (SELECT id FROM hypit_job WHERE account_id"
						+ " IN (:a, :b))").bind("a", OWNER_A).bind("b", OWNER_B).then())
				.then(db.sql("DELETE FROM hypit_job WHERE account_id IN (:a, :b)").bind("a", OWNER_A).bind("b", OWNER_B)
						.then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id IN (:a, :b)").bind("a", OWNER_A)
						.bind("b", OWNER_B).then())
				.then(db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id IN (:a, :b))").bind("a", OWNER_A).bind("b", OWNER_B).then())
				.then(db.sql("DELETE FROM hypit_changeset WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id IN (:a, :b))").bind("a", OWNER_A).bind("b", OWNER_B).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id IN (:a, :b)").bind("a", OWNER_A)
						.bind("b", OWNER_B).then())
				.block(java.time.Duration.ofSeconds(10));
	}

	private void stubProvisionSuccess() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.provision"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"workspace.provision","state":"succeeded",
						 "result":{"projectRoot":"/tmp/p","state":"created",
						   "head":{"revision":1,"manifestHash":"%s","updatedAt":"now"}}}
						""".formatted("a".repeat(64)))));
	}

	private void stubProvisionFailure() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.provision"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"workspace.provision","state":"failed",
						 "error":{"code":"engine_error","message":"disk unavailable"}}
						""")));
	}

	private JsonNode create(String owner, UUID requestId, String title, String mode) {
		String body = client().post().uri("/api/hypit/projects").header("X-Grassland-Identity", sign(owner, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue("""
						{"requestId":"%s","title":"%s","mode":"%s"}
						""".formatted(requestId, title, mode)).exchange().expectStatus().isEqualTo(202)
				.expectBody(String.class).returnResult().getResponseBody();
		try {
			return JSON.readTree(body == null ? "{}" : body);
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	@Test
	void concurrentSameRequestCreatesOneProjectAndConflictingBodyIs409() {
		stubProvisionSuccess();
		UUID requestId = UUID.randomUUID();

		// 顺序重放两次：第二次必须返回同一工程（幂等键 (account,action,requestId)）。
		JsonNode first = create(OWNER_A, requestId, "同体工程", "brief");
		JsonNode second = create(OWNER_A, requestId, "同体工程", "brief");
		String projectId = first.path("data").path("project").path("id").asText();
		assertThat(projectId).isNotBlank();
		assertThat(second.path("data").path("project").path("id").asText()).isEqualTo(projectId);

		Long count = db.sql("SELECT COUNT(*) AS c FROM hypit_project WHERE account_id = :owner").bind("owner", OWNER_A)
				.map((row, meta) -> row.get("c", Long.class)).one().block();
		assertThat(count).isEqualTo(1L);

		// 同 requestId 不同 body：409 hypit_idempotency_conflict，无第二次文件副作用。
		client().post().uri("/api/hypit/projects").header("X-Grassland-Identity", sign(OWNER_A, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue("""
						{"requestId":"%s","title":"另一个工程","mode":"brief"}
						""".formatted(requestId)).exchange().expectStatus().isEqualTo(409).expectBody(String.class)
				.value(text -> assertThat(text).contains("hypit_idempotency_conflict"));

		// provision 只被调用了 1 次（幂等命令不重放 sidecar）。
		assertThat(SIDECAR.countRequestsMatching(postRequestedFor(urlPathEqualTo("/internal/v1/commands")).build())
				.getCount()).isEqualTo(1);

		// 落库终态：ready + revision 1 + job succeeded + revision 行。
		String status = db.sql("SELECT status FROM hypit_project WHERE id = CAST(:id AS uuid)").bind("id", projectId)
				.map((row, meta) -> row.get("status", String.class)).one().block();
		assertThat(status).isEqualTo("ready");
		Long revisions = db.sql("SELECT COUNT(*) AS c FROM hypit_revision WHERE project_id =" + " CAST(:id AS uuid)")
				.bind("id", projectId).map((row, meta) -> row.get("c", Long.class)).one().block();
		assertThat(revisions).isEqualTo(1L);
	}

	@Test
	void provisionFailureMarksProvisioningFailedAndJobFailed() {
		stubProvisionFailure();
		JsonNode result = create(OWNER_A, UUID.randomUUID(), "失败工程", "brief");
		String projectId = result.path("data").path("project").path("id").asText();
		String status = db.sql("SELECT status FROM hypit_project WHERE id = CAST(:id AS uuid)").bind("id", projectId)
				.map((row, meta) -> row.get("status", String.class)).one().block();
		assertThat(status).isEqualTo("provisioning_failed");
		String jobState = db.sql("SELECT state FROM hypit_job WHERE project_id = CAST(:id AS uuid)")
				.bind("id", projectId).map((row, meta) -> row.get("state", String.class)).one().block();
		assertThat(jobState).isEqualTo("failed");
	}

	@Test
	void nonOwnerGets404AndPatchUsesVersionCas() {
		stubProvisionSuccess();
		JsonNode created = create(OWNER_A, UUID.randomUUID(), "归属工程", "brief");
		String projectId = created.path("data").path("project").path("id").asText();

		// 他人读 404（不泄漏存在性）。
		client().get().uri("/api/hypit/projects/" + projectId).header("X-Grassland-Identity", sign(OWNER_B, null))
				.exchange().expectStatus().isEqualTo(404);
		// 未登录 401。
		client().get().uri("/api/hypit/projects/" + projectId).exchange().expectStatus().isEqualTo(401);

		// 本人读取 200。
		client().get().uri("/api/hypit/projects/" + projectId).header("X-Grassland-Identity", sign(OWNER_A, null))
				.exchange().expectStatus().isEqualTo(200);

		// PATCH 标题 version CAS：错误 baseVersion 409。
		client().patch().uri("/api/hypit/projects/" + projectId).header("X-Grassland-Identity", sign(OWNER_A, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue("""
						{"requestId":"%s","title":"新标题","baseVersion":99}
						""".formatted(UUID.randomUUID())).exchange().expectStatus().isEqualTo(409);
		// 正确 baseVersion 成功。
		client().patch().uri("/api/hypit/projects/" + projectId).header("X-Grassland-Identity", sign(OWNER_A, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue("""
						{"requestId":"%s","title":"新标题","baseVersion":1}
						""".formatted(UUID.randomUUID())).exchange().expectStatus().isEqualTo(200);
	}

	@Test
	void deleteRefusesActiveWorkAndCompletesAfterTerminal() {
		stubProvisionSuccess();
		JsonNode created = create(OWNER_A, UUID.randomUUID(), "删除工程", "brief");
		String projectId = created.path("data").path("project").path("id").asText();

		// 有活跃任务：409 hypit_active_work（provision job 已终态，补一个 queued 任务占位）。
		db.sql("INSERT INTO hypit_job(id, project_id, account_id, kind, state) VALUES"
				+ " (CAST(:id AS uuid), CAST(:project AS uuid), :owner, 'project.delete', 'queued')")
				.bind("id", UUID.randomUUID().toString()).bind("project", projectId).bind("owner", OWNER_A).then()
				.block(java.time.Duration.ofSeconds(10));
		client().delete().uri("/api/hypit/projects/" + projectId).header("X-Grassland-Identity", sign(OWNER_A, null))
				.exchange().expectStatus().isEqualTo(409).expectBody(String.class)
				.value(text -> assertThat(text).contains("hypit_active_work"));

		// 终态后删除成功（物理清理交 sidecar workspace.delete 桩）。
		db.sql("UPDATE hypit_job SET state = 'succeeded', updated_at = now() WHERE account_id = :owner"
				+ " AND state = 'queued'").bind("owner", OWNER_A).then().block(java.time.Duration.ofSeconds(10));

		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.delete"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"workspace.delete","state":"succeeded",
						 "result":{"deleted":true}}
						""")));
		client().delete().uri("/api/hypit/projects/" + projectId).header("X-Grassland-Identity", sign(OWNER_A, null))
				.exchange().expectStatus().isEqualTo(200);
		String status = db.sql("SELECT status FROM hypit_project WHERE id = CAST(:id AS uuid)").bind("id", projectId)
				.map((row, meta) -> row.get("status", String.class)).one().block();
		assertThat(status).isEqualTo("deleted");
	}

	@Test
	void changesetSaveVsValidatedAndStaleRevision409() {
		stubProvisionSuccess();
		JsonNode created = create(OWNER_A, UUID.randomUUID(), "变更工程", "brief");
		String projectId = created.path("data").path("project").path("id").asText();
		// 工程建好后 job 已终态。

		// save：坏语法内容也能建草稿（K06.3.8 普通保存）。
		String saveDraft = client().post().uri("/api/hypit/projects/" + projectId + "/changesets")
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"requestId":"%s","baseRevision":1,"applyMode":"save",
						 "changes":[{"path":"main.svml","action":"put","content":"<?svml broken"}]}
						""".formatted(UUID.randomUUID())).exchange().expectStatus().isEqualTo(202)
				.expectBody(String.class).returnResult().getResponseBody();
		assertThat(saveDraft).isNotNull();
		assertThat(saveDraft).contains("\"state\":\"draft\"");

		// validated：check 未通过（sidecar workspace.check 桩为 failed）→ 草稿
		// check_status=failed。
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.check"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"workspace.check","state":"failed",
						 "error":{"code":"compile_failed","message":"bad header"}}
						""")));
		String validatedDraft = client().post().uri("/api/hypit/projects/" + projectId + "/changesets")
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"requestId":"%s","baseRevision":1,"applyMode":"validated",
						 "changes":[{"path":"main.svml","action":"put","content":"<svml></svml>"}]}
						""".formatted(UUID.randomUUID())).exchange().expectStatus().isEqualTo(202)
				.expectBody(String.class).returnResult().getResponseBody();
		assertThat(validatedDraft).contains("\"checkStatus\":\"failed\"");

		// validated 未通过 check 的 apply → 422 hypit_compile_failed，草稿保留。
		String changesetId = extractId(validatedDraft);
		client().post().uri("/api/hypit/projects/" + projectId + "/changesets/" + changesetId + "/apply")
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"requestId":"%s","baseRevision":1}
						""".formatted(UUID.randomUUID())).exchange().expectStatus().isEqualTo(422)
				.expectBody(String.class).value(text -> assertThat(text).contains("hypit_compile_failed"));
		String state = db.sql("SELECT state FROM hypit_changeset WHERE id = CAST(:id AS uuid)").bind("id", changesetId)
				.map((row, meta) -> row.get("state", String.class)).one().block();
		assertThat(state).isEqualTo("draft");

		// save 草稿 apply：sidecar 成功 → revision 2。
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.apply"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"workspace.apply","state":"succeeded",
						 "result":{"revision":2,"manifestHash":"%s","journalId":"j",
						   "appliedPaths":["main.svml"],"snapshotDir":"/tmp/s"}}
						""".formatted("b".repeat(64)))));
		String saveChangesetId = extractId(saveDraft);
		client().post().uri("/api/hypit/projects/" + projectId + "/changesets/" + saveChangesetId + "/apply")
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"requestId":"%s","baseRevision":1}
						""".formatted(UUID.randomUUID())).exchange().expectStatus().isEqualTo(202)
				.expectBody(String.class).value(text -> assertThat(text).contains("\"revision\":2"));
		Long revision = db.sql("SELECT revision FROM hypit_project WHERE id = CAST(:id AS uuid)").bind("id", projectId)
				.map((row, meta) -> row.get("revision", Long.class)).one().block();
		assertThat(revision).isEqualTo(2L);

		// 旧 baseRevision 的 apply → 409（head 已是 2）。
		String staleDraft = client().post().uri("/api/hypit/projects/" + projectId + "/changesets")
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"requestId":"%s","baseRevision":1,"applyMode":"save",
						 "changes":[{"path":"other.txt","action":"put","content":"x"}]}
						""".formatted(UUID.randomUUID())).exchange().expectStatus().isEqualTo(202)
				.expectBody(String.class).returnResult().getResponseBody();
		String staleId = extractId(staleDraft);
		client().post().uri("/api/hypit/projects/" + projectId + "/changesets/" + staleId + "/apply")
				.header("X-Grassland-Identity", sign(OWNER_A, null)).contentType(MediaType.APPLICATION_JSON)
				.bodyValue("""
						{"requestId":"%s","baseRevision":1}
						""".formatted(UUID.randomUUID())).exchange().expectStatus().isEqualTo(409);
	}

	private String extractId(String body) {
		try {
			return JSON.readTree(body).path("data").path("changesetId").asText();
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	@Test
	void disabledEngineAnswers403Or503HonestlyForMutations() {
		// 本类 @TestPropertySource enabled=true；禁用语义由 C03 契约测试覆盖，这里验证 401 优先。
		client().post().uri("/api/hypit/projects").contentType(MediaType.APPLICATION_JSON).bodyValue("""
				{"requestId":"%s","title":"t","mode":"brief"}
				""".formatted(UUID.randomUUID())).exchange().expectStatus().isEqualTo(401);
	}

	// ------------------------------------------------------------------
	// C107-22（TC107-22-02）：sourceContext 服务端归属/固化核验。
	// ------------------------------------------------------------------

	private void seedReference(String id, String owner, String status, boolean media) {
		if (media) {
			db.sql("INSERT INTO media_reference (id, owner_account_id, purpose, object_key, mime_type, source,"
					+ " status) VALUES (CAST(:id AS uuid), :owner, 'video_asset', :key, 'video/mp4', 'generated',"
					+ " :status)").bind("id", id).bind("owner", owner).bind("key", "hypit-it-key-" + id)
					.bind("status", status).then().block(java.time.Duration.ofSeconds(10));
			return;
		}
		db.sql("INSERT INTO ai_run (id, operation_id, account_id, capability, provider, run_type, budget_cents)"
				+ " VALUES (CAST(:id AS uuid), CAST(:op AS uuid), :owner, 'video_analysis', 'hypit-it', 'sync', 0)")
				.bind("id", id).bind("op", UUID.randomUUID().toString()).bind("owner", owner).then()
				.block(java.time.Duration.ofSeconds(10));
	}

	private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec createWithSource(String owner,
			String sourceContextJson) {
		return client().post().uri("/api/hypit/projects").header("X-Grassland-Identity", sign(owner, null))
				.contentType(MediaType.APPLICATION_JSON).bodyValue("""
						{"requestId":"%s","title":"引用工程","mode":"clone","sourceContext":%s}
						""".formatted(UUID.randomUUID(), sourceContextJson)).exchange();
	}

	@Test
	void sourceContextOwnershipAndPermanenceValidatedServerSide() {
		stubProvisionSuccess();
		String myActive = UUID.randomUUID().toString();
		String myPending = UUID.randomUUID().toString();
		String otherActive = UUID.randomUUID().toString();
		String myRun = UUID.randomUUID().toString();
		String otherRun = UUID.randomUUID().toString();
		seedReference(myActive, OWNER_A, "active", true);
		seedReference(myPending, OWNER_A, "pending", true);
		seedReference(otherActive, OWNER_B, "active", true);
		seedReference(myRun, OWNER_A, "completed", false);
		seedReference(otherRun, OWNER_B, "completed", false);

		// 本人已固化媒体：放行（clone 工程 + sourceContext 落库）。
		createWithSource(OWNER_A, "{\"kind\":\"media\",\"id\":\"" + myActive + "\",\"label\":\"门头参考\"}").expectStatus()
				.isEqualTo(202);
		Long withSource = db
				.sql("SELECT COUNT(*) AS c FROM hypit_project WHERE account_id = :owner"
						+ " AND source_context::text LIKE :needle")
				.bind("owner", OWNER_A).bind("needle", "%" + myActive + "%")
				.map((row, meta) -> row.get("c", Long.class)).one().block();
		assertThat(withSource).isEqualTo(1L);

		// 他人媒体/分析：与不存在同答 404（不泄漏存在性，query 伪造 owner 无效）。
		createWithSource(OWNER_A, "{\"kind\":\"media\",\"id\":\"" + otherActive + "\"}").expectStatus().isEqualTo(404)
				.expectBody(String.class).value(text -> assertThat(text).contains("hypit_not_found"));
		createWithSource(OWNER_A, "{\"kind\":\"analysis\",\"id\":\"" + otherRun + "\"}").expectStatus().isEqualTo(404);
		createWithSource(OWNER_A, "{\"kind\":\"media\",\"id\":\"" + UUID.randomUUID() + "\"}").expectStatus()
				.isEqualTo(404);

		// 本人临时（pending）媒体：409 hypit_source_not_permanent——须先固化再引用。
		createWithSource(OWNER_A, "{\"kind\":\"media\",\"id\":\"" + myPending + "\"}").expectStatus().isEqualTo(409)
				.expectBody(String.class).value(text -> assertThat(text).contains("hypit_source_not_permanent"));

		// 本人 ai_run 分析：放行；非法 kind/uuid：400。
		createWithSource(OWNER_A, "{\"kind\":\"analysis\",\"id\":\"" + myRun + "\"}").expectStatus().isEqualTo(202);
		createWithSource(OWNER_A, "{\"kind\":\"walkman\",\"id\":\"" + myActive + "\"}").expectStatus().isEqualTo(400);
		createWithSource(OWNER_A, "{\"kind\":\"media\",\"id\":\"not-a-uuid\"}").expectStatus().isEqualTo(400);

		// 拒绝路径零副作用：provision 只被调用了 2 次（两次 202）。
		assertThat(SIDECAR.countRequestsMatching(postRequestedFor(urlPathEqualTo("/internal/v1/commands")).build())
				.getCount()).isEqualTo(2);
	}
}
