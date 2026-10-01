package com.grassland.intelligence.hypit.fix2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.agent.HypitReviewService;
import com.grassland.intelligence.security.IntelligenceException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * C107F2-25（107-fix-2 / F10、F22 / §6.11）：按评论做语义源码修改并关联解决状态。
 *
 * <p>
 * 真 PostgreSQL + WireMock
 * sidecar（feedback.read/mutate、workspace.read/check/apply 桩）。
 *
 * <ul>
 * <li>TC-F2-25-01 字幕 font-size:24px + 评论「32px」→ 最小替换 32px、其余结构不变、 应用后评论批量
 * resolved（feedback.mutate 收到 replace）、comment→job→revision 映射入回执。</li>
 * <li>TC-F2-25-02 评论「让它更好看」无对象锚点 → WAITING_INPUT 保留源码（零 changeset
 * command），不写一行注释。</li>
 * <li>TC-F2-25-03 检查非法（模型修复语法坏）→ hypit_compile_failed；head/评论状态不变，
 * 诊断可见（草稿保留）。</li>
 * <li>TC-F2-25-04 同字幕两评论要求 32px 与 48px → 明确冲突 waiting（不随机覆盖），源码不变。</li>
 * </ul>
 */
@TestPropertySource(properties = "hypit.enabled=true")
class HypitFix2C25IT extends IntelligenceItSupport {

	private static final String OWNER = "eeeeeeee-0000-4000-8000-00000000250a";
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	private static final String ORIGINAL_SVS = """
			<?svml using="@hypit/svs@1"?>
			<sheet version="1">
			  caption.style { font-size: 24px; color: #ffffff; }
			  film.main { background: #10131c; }
			</sheet>
			""";

	@Autowired
	HypitReviewService reviews;

	@Autowired
	DatabaseClient db;

	private UUID projectId;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> "fix2-c25-internal-token-0123456789abcdef");
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
	}

	@BeforeAll
	static void startSidecar() {
		SIDECAR.start();
	}

	@AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@BeforeEach
	void seed() {
		SIDECAR.resetRequests();
		cleanup();
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'fix2-c25', 'clone', 'ready', 1)")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
		stubReadSource(ORIGINAL_SVS);
		stubCheckOk();
		stubApplyOk();
		stubFeedbackRead(List.of());
		stubFeedbackMutateOk();
	}

	@AfterEach
	void sweep() {
		cleanup();
	}

	private void cleanup() {
		db.sql("DELETE FROM hypit_changeset WHERE project_id IN (SELECT id FROM hypit_project WHERE account_id = :o)")
				.bind("o", OWNER).then()
				.then(db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM hypit_project"
						+ " WHERE account_id = :o)").bind("o", OWNER).then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o").bind("o", OWNER).then()).then()
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :o").bind("o", OWNER).then())
				.block(Duration.ofSeconds(20));
	}

	// ── 桩 ────────────────────────────────────────────────────────────────────
	private void stubReadSource(String content) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.read"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"c\",\"state\":\"succeeded\",\"result\":{\"path\":\"style.svs\","
								+ "\"content\":" + com.grassland.intelligence.hypit.project.HypitJson.write(content)
								+ "}}")));
	}

	private void stubCheckOk() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.check"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(
						"{\"commandId\":\"c\",\"state\":\"succeeded\",\"result\":{\"ok\":true,\"diagnostics\":[]}}")));
	}

	private void stubCheckAlwaysFailing(String message) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.check"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"c\",\"state\":\"failed\",\"error\":{\"code\":\"compile_failed\","
								+ "\"message\":\"" + message + "\"}}")));
	}

	private void stubApplyOk() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.apply"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"c\",\"state\":\"succeeded\",\"result\":{\"revision\":2,"
								+ "\"manifestHash\":\"" + "a".repeat(64) + "\",\"appliedPaths\":[\"style.svs\"]}}")));
	}

	private void stubFeedbackRead(List<Map<String, Object>> comments) {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("feedback.read"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"c\",\"state\":\"succeeded\",\"result\":{\"comments\":"
								+ com.grassland.intelligence.hypit.project.HypitJson.write(comments) + "}}")));
	}

	private void stubFeedbackMutateOk() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("feedback.mutate"))
				.willReturn(aResponse().withHeader("Content-Type", "application/json")
						.withBody("{\"commandId\":\"c\",\"state\":\"succeeded\",\"result\":{\"applied\":1}}")));
	}

	private Map<String, Object> comment(String id, String text) {
		return Map.of("id", id, "run", "main.svrun", "at", 12, "text", text, "resolved", false);
	}

	/** 从 changeset 命令的 canonical 载荷提取指定文件的新内容。 */
	private String changesetContent(String path) {
		Map<String, Object> payload = db
				.sql("SELECT payload_json::text AS payload FROM hypit_command WHERE action = 'changeset.create'"
						+ " AND account_id = :o ORDER BY created_at DESC LIMIT 1")
				.bind("o", OWNER).map((row, meta) -> row.get("payload", String.class)).one()
				.map(com.grassland.intelligence.hypit.project.HypitJson::read).block(Duration.ofSeconds(10));
		Object canonicalRaw = payload.get("canonical");
		Map<String, Object> canonical = canonicalRaw instanceof String text
				? com.grassland.intelligence.hypit.project.HypitJson.read(text)
				: com.grassland.intelligence.hypit.project.HypitJson.mapValue(canonicalRaw);
		for (Object changeRaw : (List<?>) canonical.get("changes")) {
			Map<String, Object> change = com.grassland.intelligence.hypit.project.HypitJson.mapValue(changeRaw);
			if (path.equals(change.get("path"))) {
				return String.valueOf(change.get("content"));
			}
		}
		throw new IllegalStateException("file not in changeset: " + path);
	}

	// ── TC-F2-25-01：目标 32px、其他结构不变、评论应用后 resolved ─────────────
	@Test
	@DisplayName("TC-F2-25-01 字幕 24px→32px 最小替换；其余结构不变；评论 resolved+映射")
	void semanticEditChangesOnlyTheTargetProperty() {
		stubFeedbackRead(List.of(comment("c-32", "字幕字号改成 32px，现在太小了")));

		HypitReviewService.ReviseResult result = reviews
				.revise(OWNER, projectId, UUID.randomUUID(), 1L, List.of("c-32"), "main.svrun", null)
				.block(Duration.ofSeconds(60));

		assertThat(result).isNotNull();
		assertThat(result.status()).isEqualTo("REVISED");
		assertThat(result.appliedCommentIds()).containsExactly("c-32");
		assertThat(result.revision()).isEqualTo(2L);

		// 目标 32px、其余结构不变（只换首个 font-size 的值）。
		String next = changesetContent("style.svs");
		assertThat(next).contains("font-size: 32px");
		assertThat(next).contains("color: #ffffff").contains("film.main { background: #10131c; }");
		assertThat(next).doesNotContain("24px");
		// baseHash 携带原文 sha256（非 null 整文件盲覆盖）。
		assertThat(next).doesNotContain("review:");

		// 评论批量 resolve：feedback.mutate 收到 replace 且 resolved=true。
		boolean resolvedMutate = SIDECAR.getAllServeEvents().stream()
				.filter(event -> event.getRequest().getBodyAsString().contains("feedback.mutate"))
				.anyMatch(event -> event.getRequest().getBodyAsString().contains("\"resolved\":true"));
		assertThat(resolvedMutate).as("applied comments are resolved via feedback.mutate").isTrue();

		// comment→job→revision 映射入 review 命令回执。
		Map<String, Object> mapping = db
				.sql("SELECT result_json::text AS result FROM hypit_command WHERE action = 'review.revise'"
						+ " AND project_id = CAST(:p AS uuid) ORDER BY created_at DESC LIMIT 1")
				.bind("p", projectId.toString()).map((row, meta) -> row.get("result", String.class)).one()
				.map(com.grassland.intelligence.hypit.project.HypitJson::read).block(Duration.ofSeconds(10));
		assertThat(mapping).isNotNull();
		assertThat(String.valueOf(mapping.get("revision"))).isEqualTo("2");
		assertThat(String.valueOf(mapping.get("commentIds"))).contains("c-32");
	}

	// ── TC-F2-25-02：无锚点意见 → WAITING_INPUT 保留源码 ──────────────────────
	@Test
	@DisplayName("TC-F2-25-02 评论无对象锚点：WAITING_INPUT，零 changeset，不写一行注释")
	void unanchoredCommentWaitsWithoutTouchingSource() {
		stubFeedbackRead(List.of(comment("c-vague", "让它更好看")));

		HypitReviewService.ReviseResult result = reviews
				.revise(OWNER, projectId, UUID.randomUUID(), 1L, List.of("c-vague"), "main.svrun", null)
				.block(Duration.ofSeconds(30));

		assertThat(result).isNotNull();
		assertThat(result.status()).isEqualTo("WAITING_INPUT");
		assertThat(result.waitingCommentIds()).containsExactly("c-vague");
		assertThat(result.changesetId()).isNull();
		Long changesets = db
				.sql("SELECT COUNT(*) AS n FROM hypit_command WHERE action = 'changeset.create'"
						+ " AND account_id = :o")
				.bind("o", OWNER).map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(changesets).as("no changeset command for unanchored comments").isZero();
		assertThat(SIDECAR.getAllServeEvents()).as("no feedback.mutate (comments stay open, source untouched)")
				.noneSatisfy(event -> assertThat(event.getRequest().getBodyAsString()).contains("feedback.mutate"));
	}

	// ── TC-F2-25-03：检查非法 → head/评论不变，诊断可见 ────────────────────────
	@Test
	@DisplayName("TC-F2-25-03 修复语法非法：hypit_compile_failed，head/评论状态不变")
	void failingCheckKeepsHeadAndComments() {
		stubCheckAlwaysFailing("修改后样式表语法非法");
		stubFeedbackRead(List.of(comment("c-32", "字幕字号改成 32px")));

		Throwable failure = catchThrowable(
				() -> reviews.revise(OWNER, projectId, UUID.randomUUID(), 1L, List.of("c-32"), "main.svrun", null)
						.block(Duration.ofSeconds(30)));

		assertThat(failure).isInstanceOf(IntelligenceException.class);
		assertThat(((IntelligenceException) failure).code()).isEqualTo("hypit_compile_failed");
		// 评论不动：无 feedback.mutate。
		assertThat(SIDECAR.getAllServeEvents())
				.noneSatisfy(event -> assertThat(event.getRequest().getBodyAsString()).contains("feedback.mutate"));
		// head 不动：无 workspace.apply。
		assertThat(SIDECAR.getAllServeEvents())
				.noneSatisfy(event -> assertThat(event.getRequest().getBodyAsString()).contains("workspace.apply"));
		// 诊断可见：草稿保留（changeset 行在，check_status 非 passed）。
		Long drafts = db
				.sql("SELECT COUNT(*) AS n FROM hypit_changeset c JOIN hypit_project p ON p.id = c.project_id"
						+ " WHERE p.account_id = :o AND c.state = 'draft'")
				.bind("o", OWNER).map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(drafts).as("validated draft kept with diagnostics").isEqualTo(1L);
	}

	// ── TC-F2-25-04：同字幕冲突意见 → 明确冲突待选择 ──────────────────────────
	@Test
	@DisplayName("TC-F2-25-04 同字幕 32px 与 48px：冲突 waiting，不随机覆盖，源码不变")
	void conflictingValuesWaitForUserChoice() {
		stubFeedbackRead(List.of(comment("c-a", "字幕字号改成 32px"), comment("c-b", "字幕字号要 48px")));

		HypitReviewService.ReviseResult result = reviews
				.revise(OWNER, projectId, UUID.randomUUID(), 1L, List.of("c-a", "c-b"), "main.svrun", null)
				.block(Duration.ofSeconds(30));

		assertThat(result).isNotNull();
		assertThat(result.status()).isEqualTo("WAITING_INPUT");
		assertThat(result.conflicts()).containsExactlyInAnyOrder("c-b");
		assertThat(result.appliedCommentIds()).isEmpty();
		Long changesets = db
				.sql("SELECT COUNT(*) AS n FROM hypit_command WHERE action = 'changeset.create'"
						+ " AND account_id = :o")
				.bind("o", OWNER).map((row, meta) -> row.get("n", Long.class)).one().block(Duration.ofSeconds(10));
		assertThat(changesets).as("conflicting opinions never land a changeset").isZero();
	}
}
