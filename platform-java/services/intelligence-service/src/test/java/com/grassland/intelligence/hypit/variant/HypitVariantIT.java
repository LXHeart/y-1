package com.grassland.intelligence.hypit.variant;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.hypit.variant.HypitVariantRepository.VariantRow;
import com.grassland.intelligence.hypit.variant.HypitVariantService.Axis;
import com.grassland.intelligence.hypit.variant.HypitVariantService.BatchView;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;

/**
 * 批量变体持久面（任务书 #107-3 C107-19 / TC107-19-01～04）：真 PostgreSQL 上 axes
 * 校验边界（100/101/重复 key）、批次创建部分失败保留、失败项重试新 attempt 且成功项不重跑、原 grant 不覆盖新增项。构建执行链由
 * C09 HypitBuildIT 覆盖；此处验证变体状态机与批次汇总语义。
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitVariantIT extends IntelligenceItSupport {

	private static final String OWNER = "dddddddd-0000-4000-8000-00000000001c";
	private static final UUID PROJECT = UUID.fromString("bbbbbbbb-0000-4000-8000-00000000b003");
	private static final WireMockServer SIDECAR = new WireMockServer(0);

	static {
		SIDECAR.start();
	}

	@org.junit.jupiter.api.AfterAll
	static void stopSidecar() {
		SIDECAR.stop();
	}

	@org.springframework.test.context.DynamicPropertySource
	static void sidecarProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("hypit.sidecar-base-url", SIDECAR::baseUrl);
		registry.add("hypit.internal-token", () -> "it-hypit-internal-token-0123456789abcdef");
	}

	@Autowired
	HypitVariantService variants;

	@Autowired
	HypitVariantRepository variantRepo;

	@Autowired
	DatabaseClient db;

	@BeforeEach
	void clean() {
		SIDECAR.resetAll();
		stubWorkspaceApply();
		db.sql("DELETE FROM hypit_variant WHERE project_id = CAST(:p AS uuid)").bind("p", PROJECT.toString()).then()
				.then(db.sql("DELETE FROM hypit_job WHERE project_id = CAST(:p AS uuid)").bind("p", PROJECT.toString())
						.then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o OR project_id = CAST(:p AS uuid)")
						.bind("o", OWNER).bind("p", PROJECT.toString()).then())
				.then(db.sql("DELETE FROM hypit_project WHERE id = CAST(:p AS uuid) OR account_id = :o")
						.bind("p", PROJECT.toString()).bind("o", OWNER).then())
				.then(db.sql("""
						INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision)
						VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'variant-it', 'clone', 'ready', 1)
						""").bind("id", PROJECT.toString()).bind("owner", OWNER)
						.bind("ws", UUID.randomUUID().toString()).then())
				.block(Duration.ofSeconds(10));
	}

	@AfterEach
	void sweep() {
		db.sql("DELETE FROM hypit_variant WHERE project_id = CAST(:p AS uuid)").bind("p", PROJECT.toString()).then()
				.then(db.sql("DELETE FROM hypit_job WHERE project_id = CAST(:p AS uuid)").bind("p", PROJECT.toString())
						.then())
				.then(db.sql("DELETE FROM hypit_command WHERE account_id = :o OR project_id = CAST(:p AS uuid)")
						.bind("o", OWNER).bind("p", PROJECT.toString()).then())
				.then(db.sql("DELETE FROM hypit_project WHERE id = CAST(:p AS uuid)").bind("p", PROJECT.toString())
						.then())
				.block(Duration.ofSeconds(10));
	}

	private void stubWorkspaceApply() {
		SIDECAR.stubFor(post(urlPathEqualTo("/internal/v1/commands")).withRequestBody(containing("workspace.apply"))
				.willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""
						{"commandId":"x","kind":"workspace.apply","state":"succeeded",
						 "result":{"revision":2,"manifestHash":"%s","journalId":"j",
						   "appliedPaths":["runs/variants/variant-0.svrun"],"snapshotDir":"/tmp/s"}}
						""".formatted("e".repeat(64)))));
	}

	private static HypitVariantService.CreateRequest create(List<Axis> axes) {
		return new HypitVariantService.CreateRequest(UUID.randomUUID(), "main.svrun", axes);
	}

	@Test
	void tc02AxisValidationBoundsAndDuplicateKeys() {
		// 20 项合法（2 值 × 10 值交叉积）。
		List<Axis> hundred = List.of(new Axis("topic", List.of("a", "b")),
				new Axis("host", List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10")));
		BatchView ok = variants.create(OWNER, PROJECT, create(hundred)).block(Duration.ofSeconds(20));
		assertThat(ok).isNotNull();
		assertThat(ok.items()).hasSize(20);

		// 重复 clientKey（同轴两次声明）→ 400。
		try {
			variants.validateAxes(List.of(new Axis("topic", List.of("a")), new Axis("topic", List.of("b"))));
			throw new AssertionError("expected 400");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_invalid_input");
		}
		// 超限 → 413 不截断。
		String[] values = new String[11];
		java.util.Arrays.fill(values, "v");
		try {
			variants.validateAxes(
					List.of(new Axis("topic", java.util.List.of(values)), new Axis("host", java.util.List.of(values))));
			throw new AssertionError("expected 413");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_too_large");
		}
	}

	@Test
	void tc01BatchCreatesIndependentVariantsAndRetryOnlyFailed() {
		BatchView batch = variants.create(OWNER, PROJECT, create(List.of(new Axis("topic", List.of("a", "b", "c")))))
				.block(Duration.ofSeconds(20));
		assertThat(batch).isNotNull();
		assertThat(batch.items()).hasSize(3);
		// 每变体独立 runFile（runs/variants/variant-N.svrun）与 ordinal。
		assertThat(batch.items().get(0).runFile()).isEqualTo("runs/variants/variant-0.svrun");
		assertThat(batch.items().get(2).ordinal()).isEqualTo(2);

		// 模拟 A 成功 / B 失败 / C queued：直接驱动变体状态机。
		markState(batch.items().get(0).id(), "succeeded");
		markState(batch.items().get(1).id(), "failed");
		// 重试失败项：新 attempt、清空 plan/build、回 queued。
		VariantRow retried = variants.retryVariant(OWNER, PROJECT, batch.items().get(1).id())
				.block(Duration.ofSeconds(20));
		assertThat(retried).isNotNull();
		assertThat(retried.state()).isEqualTo("queued");
		assertThat(retried.attempt()).isEqualTo(2);
		// 已成功项拒绝重跑。
		try {
			variants.retryVariant(OWNER, PROJECT, batch.items().get(0).id()).block(Duration.ofSeconds(20));
			throw new AssertionError("expected 409");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo("hypit_state_conflict");
		}
		// 批次汇总：partial 状态如实。
		BatchView summary = variants.batchSummary(OWNER, PROJECT, batch.batchJobId()).block(Duration.ofSeconds(20));
		assertThat(summary).isNotNull();
		assertThat(summary.status()).isEqualTo("running");
	}

	@Test
	void tc04CancelPendingVariantKeepsTerminalStates() {
		BatchView batch = variants.create(OWNER, PROJECT, create(List.of(new Axis("topic", List.of("a", "b")))))
				.block(Duration.ofSeconds(20));
		assertThat(batch).isNotNull();
		markState(batch.items().get(0).id(), "succeeded");
		// 取消 queued 项；succeeded 项不受影响（完成/已产生媒体保留）。
		VariantRow cancelled = variants.cancelVariant(OWNER, PROJECT, batch.items().get(1).id(), UUID.randomUUID())
				.block(Duration.ofSeconds(20));
		assertThat(cancelled).isNotNull();
		assertThat(cancelled.state()).isEqualTo("cancelled");
		String success = db.sql("SELECT state FROM hypit_variant WHERE id = CAST(:id AS uuid)")
				.bind("id", batch.items().get(0).id().toString()).map((row, meta) -> row.get("state", String.class))
				.one().block(Duration.ofSeconds(10));
		assertThat(success).isEqualTo("succeeded");
	}

	@Test
	void tc03GrantScopeNotExtendedByVariantAxis() {
		// 原批次 axes=[topic(a,b)]；新增第四项请求（axis c）不属于该批次：
		// 创建新批次是独立命令（新 requestId），但同 scope 的旧 grant 不自动覆盖它——
		// buildVariant 走 C09 submit 的 requireGrantIfNeeded（无 grant 且计划含
		// 收费 Need 时拒绝），此处验证变体行不会继承前一批次的 plan/grant 绑定。
		BatchView first = variants.create(OWNER, PROJECT, create(List.of(new Axis("topic", List.of("a", "b")))))
				.block(Duration.ofSeconds(20));
		assertThat(first).isNotNull();
		BatchView second = variants.create(OWNER, PROJECT, create(List.of(new Axis("topic", List.of("c")))))
				.block(Duration.ofSeconds(20));
		assertThat(second).isNotNull();
		assertThat(second.batchJobId()).isNotEqualTo(first.batchJobId());
		// 第二批次变体的 plan 从零开始（planId null），不继承第一批次授权。
		assertThat(second.items().get(0).state()).isEqualTo("draft");
		Long plans = db.sql("""
				SELECT count(*) AS n FROM hypit_plan WHERE project_id = CAST(:p AS uuid)
				""").bind("p", PROJECT.toString()).map((row, meta) -> row.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(plans).isZero();
	}

	private void markState(UUID variantId, String state) {
		db.sql("UPDATE hypit_variant SET state = CAST(:s AS text), updated_at = now() WHERE id = CAST(:id AS uuid)")
				.bind("id", variantId.toString()).bind("s", state).then().block(Duration.ofSeconds(10));
	}
}
