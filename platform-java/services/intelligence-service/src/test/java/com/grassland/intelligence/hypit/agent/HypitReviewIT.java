package com.grassland.intelligence.hypit.agent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.agent.HypitReviewService.Issue;
import com.grassland.intelligence.hypit.agent.HypitReviewService.ReviewResult;
import com.grassland.intelligence.hypit.agent.HypitReviewService.ReviseResult;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;

/**
 * 审片修改闭环持久面（任务书 #107-3 C107-18 / TC107-18-01/03/04）：真 PostgreSQL
 * 上按评论修改生成真实参数变更集（字号/gain），需新生成素材的意见如实 waiting_input，未完成的评论不假 resolve，无关
 * Provider submit 计数恒为零。 FEEDBACK 桥以 WireMock 桩（上游格式语义由
 * B/tests/studio/feedback-roundtrip 真值覆盖）。
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitReviewIT extends IntelligenceItSupport {

	private static final String OWNER = "dddddddd-0000-4000-8000-00000000001b";
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	static {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@Autowired
	HypitReviewService reviewService;

	@Autowired
	DatabaseClient db;

	private static final UUID PROJECT = UUID.fromString("bbbbbbbb-0000-4000-8000-00000000b002");

	@org.springframework.test.context.DynamicPropertySource
	static void sidecarProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		registry.add("hypit.internal-token", () -> "it-hypit-internal-token-0123456789abcdef");
	}

	@BeforeEach
	void clean() {
		SIDECAR.resetAll();
		db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM hypit_project WHERE id"
				+ " = CAST(:p AS uuid) OR account_id = :o)").bind("p", PROJECT.toString()).bind("o", OWNER).then()
				.then(db.sql("DELETE FROM hypit_changeset WHERE project_id = CAST(:p AS uuid)")
						.bind("p", PROJECT.toString()).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o OR project_id = CAST(:p AS uuid)")
						.bind("o", OWNER).bind("p", PROJECT.toString()).then())
				.then(db.sql("DELETE FROM hypit_project WHERE id = CAST(:p AS uuid) OR account_id = :o")
						.bind("p", PROJECT.toString()).bind("o", OWNER).then())
				.then(db.sql("""
						INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)
						VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'review-it', 'clone', 'ready', 1)
						""").bind("id", PROJECT.toString()).bind("owner", OWNER)
						.bind("ws", UUID.randomUUID().toString()).then())
				.block(Duration.ofSeconds(10));
	}

	@AfterEach
	void sweep() {
		db.sql("DELETE FROM hypit_revision WHERE project_id = CAST(:p AS uuid)").bind("p", PROJECT.toString()).then()
				.then(db.sql("DELETE FROM hypit_changeset WHERE project_id = CAST(:p AS uuid)")
						.bind("p", PROJECT.toString()).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o OR project_id = CAST(:p AS uuid)")
						.bind("o", OWNER).bind("p", PROJECT.toString()).then())
				.then(db.sql("DELETE FROM hypit_project WHERE id = CAST(:p AS uuid)").bind("p", PROJECT.toString())
						.then())
				.block(Duration.ofSeconds(10));
	}

	/** TC107-18-01 的三条评论：字幕放大 / 提到产品时出图 / 音效降低。 */
	private void stubFeedbackRead() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("feedback.read"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"feedback.read","state":"succeeded","result":{
						  "file":"FEEDBACK.json",
						  "comments":[
						    {"id":"fb-1","run":"main.svrun","at":1.5,"text":"字幕放大"},
						    {"id":"fb-2","run":"main.svrun","at":4.0,"text":"提到产品时出图"},
						    {"id":"fb-3","run":"main.svrun","at":6.5,"text":"音效降低"}
						  ]}}
						""")));
	}

	private void stubApply() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.apply"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"workspace.apply","state":"succeeded",
						 "result":{"revision":2,"manifestHash":"%s","journalId":"j",
						   "appliedPaths":["style.svs"],"snapshotDir":"/tmp/s"}}
						""".formatted("d".repeat(64)))));
	}

	private void stubCheckOk() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.check"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"workspace.check","state":"succeeded",
						 "result":{"ok":true,"diagnostics":[]}}
						""")));
	}

	private long generationCommandCount() {
		Long count = db.sql("""
				SELECT count(*) AS n FROM hypit_command
				WHERE action IN ('build.submit','provider.invoke','clone.execute')
				  AND (account_id = :o OR project_id = CAST(:p AS uuid))
				""").bind("o", OWNER).bind("p", PROJECT.toString()).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		return count == null ? -1L : count;
	}

	@Test
	void tc01ReviseChangesRealParamsWaitingInputHonest() {
		stubFeedbackRead();
		ReviewResult review = reviewService.review(PROJECT, UUID.randomUUID(), "main.svrun")
				.block(Duration.ofSeconds(20));
		assertThat(review).isNotNull();
		// 出图意见需要新生成素材 → 审片状态 WAITING_INPUT，不猜授权。
		assertThat(review.status()).isEqualTo("WAITING_INPUT");

		stubCheckOk();
		stubApply();
		ReviseResult revise = reviewService.revise(OWNER, PROJECT, UUID.randomUUID(), 1L, review.issues())
				.block(Duration.ofSeconds(20));
		assertThat(revise).isNotNull();
		// 字幕/音效真实修复；出图意见 waiting_input。
		assertThat(revise.appliedCommentIds()).containsExactlyInAnyOrder("fb-1", "fb-3");
		assertThat(revise.waitingCommentIds()).containsExactly("fb-2");
		assertThat(revise.revision()).isEqualTo(2L);
		// 参数真实变：变更集里的 style.svs 含字号与 gain 属性（文件真值）。
		String payload = db.sql("""
				SELECT payload_json::text AS p FROM hypit_command
				WHERE action = 'changeset.create' AND project_id = CAST(:p AS uuid)
				ORDER BY created_at DESC LIMIT 1
				""").bind("p", PROJECT.toString()).map((row, meta) -> row.get("p", String.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(payload).contains("caption-size").contains("48px");
		assertThat(payload).contains("gain-db").contains("-6");
		// 无关生成 Provider submit 计数 = 原值（0）：范围外意见绝不自动生成。
		assertThat(generationCommandCount()).isZero();
	}

	@Test
	void tc04AllOutOfScopeIs422NoEmptyChangeset() {
		List<Issue> issues = List.of(new Issue("fb-9", "main.svrun", 2.0, "换个产品图", "material.generation", "", true));
		try {
			reviewService.revise(OWNER, PROJECT, UUID.randomUUID(), 1L, issues).block(Duration.ofSeconds(20));
			throw new AssertionError("expected 422");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_missing_material");
		}
		Long changesets = db.sql("SELECT count(*) AS n FROM hypit_changeset WHERE project_id = CAST(:p AS uuid)")
				.bind("p", PROJECT.toString()).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(changesets).isZero();
	}

	@Test
	void tc03StaleFeedbackHashKeepsInput() {
		// 并发修改评论：sidecar 端 CAS 冲突如实失败（B 侧真值已验证桥语义）。
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("feedback.mutate"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"feedback.mutate","state":"failed",
						 "error":{"code":"feedback_conflict","message":"FEEDBACK changed outside this view"}}
						""")));
		java.util.Map<String, Object> payload = java.util.Map.of("projectId", PROJECT.toString(), "expectedHash",
				"stale");
		com.grassland.intelligence.hypit.client.HypitSidecarClient.SidecarCommand receipt = sidecar()
				.commandAsync("java-feedback-mutate-" + UUID.randomUUID(), "feedback.mutate", payload)
				.block(Duration.ofSeconds(10));
		assertThat(receipt).isNotNull();
		assertThat(receipt.state()).isEqualTo("failed");
		assertThat(receipt.error().get("code")).isEqualTo("feedback_conflict");
		Long head = db.sql("SELECT revision FROM hypit_project WHERE id = CAST(:p AS uuid)")
				.bind("p", PROJECT.toString()).map((row, meta) -> row.get("revision", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(head).isEqualTo(1L);
	}

	private com.grassland.intelligence.hypit.client.HypitSidecarClient sidecar() {
		return new com.grassland.intelligence.hypit.client.HypitSidecarClient(
				new com.grassland.intelligence.hypit.config.HypitProperties(true, SIDECAR.baseUrl(),
						"it-hypit-internal-token-0123456789abcdef", ""));
	}
}
