package com.grassland.intelligence.hypit.agent;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.agent.HypitAuthorService.DraftInput;
import com.grassland.intelligence.hypit.agent.HypitAuthorService.DraftResult;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;

/**
 * 编写代理持久面（任务书 #107-3 C107-17 / TC107-17 持久层）：真 PostgreSQL 上 生成多文件
 * changeset、check 两轮修复边界、CAS 409 草稿保留、超限 413 不截断。 sidecar 以 WireMock 桩承载
 * workspace.check/apply（编译语义由 B 侧 custom-packages.test.ts 的真引擎用例覆盖，此处验证 Java
 * 编排与持久状态机）。
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitAuthoringIT extends IntelligenceItSupport {

	private static final String OWNER = "dddddddd-0000-4000-8000-00000000001a";
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	static {
		SIDECAR.start();
	}

	@org.junit.jupiter.api.AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}
	private static final UUID REQUEST_ID = UUID.fromString("aaaaaaaa-0000-4000-8000-00000000a001");
	private static final UUID PROJECT = UUID.fromString("bbbbbbbb-0000-4000-8000-00000000b001");

	@Autowired
	HypitAuthorService authorService;

	@Autowired
	com.grassland.intelligence.hypit.project.HypitChangesetService changesets;

	@Autowired
	DatabaseClient db;

	@org.springframework.test.context.DynamicPropertySource
	static void sidecarProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		registry.add("hypit.internal-token", () -> "it-hypit-internal-token-0123456789abcdef");
	}

	@BeforeEach
	void clean() {
		SIDECAR.resetAll();
		db.sql("""
				DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM hypit_project
				WHERE id = CAST(:p AS uuid) OR account_id = :o)
				""").bind("p", PROJECT.toString()).bind("o", OWNER).then()
				.then(db.sql("DELETE FROM hypit_changeset WHERE project_id = CAST(:p AS uuid)")
						.bind("p", PROJECT.toString()).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE id = CAST(:p AS uuid) OR account_id = :o")
						.bind("p", PROJECT.toString()).bind("o", OWNER).then())
				.then(db.sql("""
						INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)
						VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'author-it', 'clone', 'ready', 1)
						""").bind("id", PROJECT.toString()).bind("owner", OWNER)
						.bind("ws", UUID.randomUUID().toString()).then())
				.block(Duration.ofSeconds(10));
	}

	@AfterEach
	void sweep() {
		db.sql("DELETE FROM hypit_revision WHERE project_id = CAST(:p AS uuid)").bind("p", PROJECT.toString()).then()
				.then(db.sql("DELETE FROM hypit_changeset WHERE project_id = CAST(:p AS uuid)")
						.bind("p", PROJECT.toString()).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE id = CAST(:p AS uuid)").bind("p", PROJECT.toString())
						.then())
				.block(Duration.ofSeconds(10));
	}

	private static DraftInput input(List<Map<String, Object>> steps) {
		return new DraftInput(REQUEST_ID, PROJECT, 1L, "author-it", steps,
				List.of("references/production/authoring.md"));
	}

	private void stubCheck(boolean ok) {
		String result = ok
				? "{\"ok\":true,\"diagnostics\":[],\"sourceKind\":\"run\"}"
				: "{\"ok\":false,\"diagnostics\":[{\"file\":\"main.svml\",\"severity\":\"error\","
						+ "\"message\":\"film:Film requires exactly appearance, canvas, id, timeline\"}]}";
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.check"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"workspace.check","state":"succeeded","result":%s}
						""".formatted(result))));
	}

	private void stubApply() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.apply"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"workspace.apply","state":"succeeded",
						 "result":{"revision":2,"manifestHash":"%s","journalId":"j",
						   "appliedPaths":["main.svml","style.svs","main.svrun","plan.json"],
						   "snapshotDir":"/tmp/s"}}
						""".formatted("c".repeat(64)))));
	}

	@Test
	void tc01GeneratesMultifileChangesetCheckPassesApplyMovesHead() {
		stubCheck(true);
		stubApply();
		List<Map<String, Object>> steps = List
				.of(Map.of("index", 0, "capability", "clone.badge", "boundSystemId", "sys-badge", "anchorSeconds", 0));
		DraftResult draft = authorService.draft(OWNER, input(steps)).block(Duration.ofSeconds(20));
		assertThat(draft).isNotNull();
		assertThat(draft.status()).isEqualTo("DRAFT_PASSED");
		assertThat(draft.rounds()).isEqualTo(1);
		// 多文件 changeset：SVML/SVS/Run + plan 锚点文档（不止 main.svml）。
		assertThat(draft.paths()).containsExactlyInAnyOrder("main.svml", "style.svs", "main.svrun", "plan.json");

		UUID changesetId = UUID.fromString(draft.changesetId());
		var applied = changesets.apply(OWNER, PROJECT, changesetId, UUID.randomUUID(), 1L)
				.block(Duration.ofSeconds(20));
		assertThat(applied).isNotNull();
		assertThat(applied.revision()).isEqualTo(2L);
		Long head = db.sql("SELECT revision FROM hypit_project WHERE id = CAST(:p AS uuid)")
				.bind("p", PROJECT.toString()).map((row, meta) -> row.get("revision", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(head).isEqualTo(2L);
	}

	@Test
	void tc02TwoRoundsStillBadKeepsDiagnosticsAndHeadUntouched() {
		stubCheck(false);
		List<Map<String, Object>> steps = List
				.of(Map.of("index", 0, "capability", "clone.badge", "boundSystemId", "sys-badge", "anchorSeconds", 0));
		DraftResult draft = authorService.draft(OWNER, input(steps)).block(Duration.ofSeconds(20));
		assertThat(draft).isNotNull();
		assertThat(draft.status()).isEqualTo("DRAFT_DIAGNOSTICS");
		assertThat(draft.rounds()).isEqualTo(2);
		assertThat(draft.diagnostics()).isNotEmpty();
		// head 未动、草稿保留：诊断可复查，diff 不丢弃。
		Long head = db.sql("SELECT revision FROM hypit_project WHERE id = CAST(:p AS uuid)")
				.bind("p", PROJECT.toString()).map((row, meta) -> row.get("revision", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(head).isEqualTo(1L);
		String state = db.sql("SELECT state FROM hypit_changeset WHERE id = CAST(:id AS uuid)")
				.bind("id", draft.changesetId()).map((row, meta) -> row.get("state", String.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(state).isEqualTo("draft");
	}

	@Test
	void tc03UserEditedSourceIs409AndDraftSurvives() {
		stubCheck(true);
		stubApply();
		DraftResult draft = authorService.draft(OWNER, input(List
				.of(Map.of("index", 0, "capability", "clone.badge", "boundSystemId", "sys-badge", "anchorSeconds", 0))))
				.block(Duration.ofSeconds(20));
		assertThat(draft).isNotNull();
		UUID changesetId = UUID.fromString(draft.changesetId());
		// 用户先改了源码（head 前进到 2），作者草稿仍基于 revision 1 → 409。
		db.sql("UPDATE hypit_project SET revision = 2 WHERE id = CAST(:p AS uuid)").bind("p", PROJECT.toString()).then()
				.block(Duration.ofSeconds(10));
		try {
			changesets.apply(OWNER, PROJECT, changesetId, UUID.randomUUID(), 1L).block(Duration.ofSeconds(20));
			throw new AssertionError("expected 409");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_revision_conflict");
		}
		String state = db.sql("SELECT state FROM hypit_changeset WHERE id = CAST(:id AS uuid)").bind("id", changesetId)
				.map((row, meta) -> row.get("state", String.class)).one().block(Duration.ofSeconds(10));
		assertThat(state).isEqualTo("draft");
	}

	@Test
	void tc04OversizePlanIsRejectedNotTruncated() {
		List<Map<String, Object>> steps = new ArrayList<>();
		for (int index = 0; index < 500; index++) {
			steps.add(Map.of("index", index, "capability", "package.p" + index, "boundSystemId", "sys-" + index,
					"anchorSeconds", index));
		}
		try {
			authorService.generateChangeset(input(steps));
			throw new AssertionError("expected 413");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_too_large");
		}
	}

	@Test
	void sameRequestIdReplaysWithoutSecondDraft() {
		stubCheck(true);
		List<Map<String, Object>> steps = List
				.of(Map.of("index", 0, "capability", "clone.badge", "boundSystemId", "sys-badge", "anchorSeconds", 0));
		DraftResult first = authorService.draft(OWNER, input(steps)).block(Duration.ofSeconds(20));
		DraftResult second = authorService.draft(OWNER, input(steps)).block(Duration.ofSeconds(20));
		assertThat(first).isNotNull();
		assertThat(second).isNotNull();
		// 幂等命令：同 requestId+payload 重放不建第二份草稿。
		Long count = db.sql("""
				SELECT count(*) AS n FROM hypit_command WHERE account_id = :o AND action = 'author.draft'
				""").bind("o", OWNER).map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(count).isEqualTo(1L);
	}
}
