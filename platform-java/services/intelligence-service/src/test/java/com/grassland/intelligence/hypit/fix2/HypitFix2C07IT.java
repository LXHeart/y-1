package com.grassland.intelligence.hypit.fix2;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.TestPropertySource;

/**
 * C107F2-07（107-fix-2）：真实 Provider 授权桥（F27）。
 *
 * <p>
 * 授权权威在 Java（/internal/hypit/executions prepare/complete/fail/cancel）； broker 侧
 * ExecutionAuthorizer/httpExecutionBridge 是真实客户端（IT 内以 platform-hypit/backend 的
 * node 模块直跑 bridge 客户端代码）。全链原生 runtime 侧（Need 执行时的 fetch 级拦截）由 C08 的真实 Docker
 * 渲染链收口。
 *
 * <ul>
 * <li>TC-F2-07-01 真实授权链：broker bridge 客户端 → 真实 Java prepare/complete， PG
 * 行逐段可追踪（grant→execution prepared→succeeded+receipt），operationId 唯一。</li>
 * <li>TC-F2-07-02 scope 方向：targets=[a,b]、grant=[a]，执行 b → 拒绝（零 execution 行）； 空
 * targets 同样无授权效力。</li>
 * <li>TC-F2-07-03 并发预算：上限 1.000000、两个 0.700000 并发 prepare → 最多一个获准。</li>
 * <li>TC-F2-07-04 unknown 回执：accepted 后丢响应 → 同 operationId 重放 → 查询原行，
 * 不产生第二次执行/费用。</li>
 * </ul>
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitFix2C07IT extends IntelligenceItSupport {

	private static final String OWNER = "cccccccc-0000-4000-8000-00000000030a";
	private static final String TOKEN = "fix2-c07-internal-token-0123456789abcdef"; // secret-scan: allow
	private static final Path REPO = Path.of("").toAbsolutePath().resolve("../../..").normalize();
	private static final Path BACKEND = REPO.resolve("platform-hypit/backend");
	private static final ObjectMapper JSON = new ObjectMapper();

	@org.springframework.test.context.DynamicPropertySource
	static void sidecarProps(org.springframework.test.context.DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> TOKEN);
	}

	@BeforeEach
	void clean() {
		db.sql("DELETE FROM hypit_execution WHERE grant_id IN (SELECT id FROM hypit_execution_grant"
				+ " WHERE account_id = :a)").bind("a", OWNER).then()
				.then(db.sql("DELETE FROM hypit_execution_grant WHERE account_id = :a").bind("a", OWNER).then())
				.then(db.sql("DELETE FROM hypit_revision WHERE project_id IN (SELECT id FROM"
						+ " hypit_project WHERE account_id = :a)").bind("a", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :a").bind("a", OWNER).then())
				.block(Duration.ofSeconds(10));
	}

	private String seedProject() {
		UUID projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status, revision, version)"
				+ " VALUES (CAST(:id AS uuid), CAST(:owner AS uuid), gen_random_uuid(), '授权工程', 'clone',"
				+ " 'ready', 1, 1)").bind("id", projectId.toString()).bind("owner", OWNER).then()
				.block(Duration.ofSeconds(10));
		return projectId.toString();
	}

	private String createGrant(String projectId, List<String> targets, String maxCost, boolean allowUnknown) {
		UUID requestId = UUID.randomUUID();
		String body = client().post().uri("/api/hypit/projects/{p}/execution-grants", projectId)
				.header("X-Grassland-Identity", sign(OWNER, null))
				.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
				.bodyValue(("{\"requestId\":\"%s\",\"scope\":{\"targets\":%s},"
						+ "\"maxCost\":%s,\"currency\":\"USD\",\"allowUnknownCost\":%s,\"variantCount\":5,"
						+ "\"expiresAt\":\"%s\"}").formatted(requestId,
								com.grassland.intelligence.hypit.project.HypitJson.write(targets),
								maxCost == null ? "null" : maxCost, allowUnknown,
								Instant.now().plus(Duration.ofHours(1)).toString()))
				.exchange().expectStatus().isEqualTo(201).expectBody(String.class).returnResult().getResponseBody();
		try {
			return new org.testcontainers.shaded.com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(body == null ? "{}" : body).path("data").path("grant").path("id").asText();
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	/** 以 broker 真实 bridge 客户端代码（node）调用本 IT 的 Java 端点。 */
	/** JSON 字符串字面量（脚本内嵌）。 */
	private static String jsonQuote(Object value) {
		try {
			return JSON.writeValueAsString(String.valueOf(value));
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private String bridgeCall(String action, String json) throws Exception {
		Path script = Files.createTempFile("fix2-c07-bridge-", ".mjs");
		// tsx 从 CWD 解析 node_modules；模块用绝对 file:// URL 指向 broker 源码。
		String moduleUrl = BACKEND.resolve("src/providers/authorization.ts").toUri().toString();
		String scriptBody = "import { httpExecutionBridge } from " + jsonQuote(moduleUrl) + ";\n"
				+ "const bridge = httpExecutionBridge({ baseUrl: " + jsonQuote("http://127.0.0.1:" + port) + ", token: "
				+ jsonQuote(TOKEN) + ", timeoutMs: 15000 });\n" + "const action = " + jsonQuote(action) + ";\n"
				+ "const request = " + json + ";\n"
				+ "const outcome = action === \"prepare\" ? await bridge.prepare(request)\n"
				+ "  : action === \"complete\" ? await bridge.complete(request.operationId,\n"
				+ "    { state: \"succeeded\", receipt: request.receipt ?? { accepted: true } })\n"
				+ "  : await bridge.fail(request.operationId, { receipt: request.receipt ?? {}, detail: \"test\" });\n"
				+ "console.log(JSON.stringify(outcome));\n";
		Files.writeString(script, scriptBody);
		Process process = new ProcessBuilder("node", "--import", "tsx", script.toString()).directory(BACKEND.toFile())
				.redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes());
		process.waitFor(60, TimeUnit.SECONDS);
		Files.deleteIfExists(script);
		if (process.exitValue() != 0) {
			throw new IllegalStateException("bridge call failed: " + output);
		}
		// node 输出可能带 tsx 警告行——取最后一行 JSON。
		String[] lines = output.strip().split("\n");
		return lines[lines.length - 1];
	}

	private static String prepareJson(String operationId, String projectId, String grantId, List<String> targets,
			String estimatedCost) {
		return ("{\"operationId\":\"%s\",\"projectId\":\"%s\",\"needId\":\"need.video\","
				+ "\"capability\":\"@hypit/media@1#video\",\"model\":\"m1\",\"endpointId\":\"e1\","
				+ "\"requestHash\":\"%s\",\"grantId\":\"%s\",\"targets\":%s%s}").formatted(operationId, projectId,
						"h".repeat(64), grantId, com.grassland.intelligence.hypit.project.HypitJson.write(targets),
						estimatedCost == null ? "" : ",\"estimatedCost\":" + estimatedCost);
	}

	@Test
	@DisplayName("TC-F2-07-01 真实授权链 prepare/complete 逐段可追踪、operationId 唯一")
	void realChainPrepareCompleteTraceable() throws Exception {
		String projectId = seedProject();
		String grantId = createGrant(projectId, List.of("final.video"), "5.000000", false);
		String operationId = UUID.randomUUID().toString();

		String permit = bridgeCall("prepare",
				prepareJson(operationId, projectId, grantId, List.of("final.video"), "0.500000"));
		assertThat(permit).contains("permitId").contains("credentialRef");

		Long prepared = db.sql("SELECT COUNT(*) FROM hypit_execution WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", operationId).map((row, meta) -> row.get(0, Long.class)).one().block();
		assertThat(prepared).isEqualTo(1L);
		String state = db.sql("SELECT state FROM hypit_execution WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", operationId).map((row, meta) -> row.get(0, String.class)).one().block();
		assertThat(state).isEqualTo("prepared");

		String settled = bridgeCall("complete",
				("{\"operationId\":\"%s\",\"receipt\":{\"provider\":\"fixture\",\"accepted\":true}}")
						.formatted(operationId));
		assertThat(settled).contains("settled");
		String finalState = db.sql("SELECT state FROM hypit_execution WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", operationId).map((row, meta) -> row.get(0, String.class)).one().block();
		assertThat(finalState).isEqualTo("succeeded");
		// 重复 complete 幂等（receipt 不二次入账）。
		bridgeCall("complete", ("{\"operationId\":\"%s\"}").formatted(operationId));
		Long still = db.sql("SELECT COUNT(*) FROM hypit_execution WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", operationId).map((row, meta) -> row.get(0, Long.class)).one().block();
		assertThat(still).isEqualTo(1L);
	}

	@Test
	@DisplayName("TC-F2-07-02 scope 方向：执行 target 未获授权/空 targets 均拒绝、零执行行")
	void ungrantedTargetRejectedWithZeroExecutions() throws Exception {
		String projectId = seedProject();
		String grantId = createGrant(projectId, List.of("a"), "5.000000", false);

		// targets=[a,b]、grant=[a]：执行 b 被拒。
		try {
			bridgeCall("prepare",
					prepareJson(UUID.randomUUID().toString(), projectId, grantId, List.of("a", "b"), "0.100000"));
			throw new AssertionError("未授权 target 的 prepare 必须失败");
		} catch (IllegalStateException expected) {
			assertThat(expected.getMessage()).containsPattern("未获授权|target|scope");
		}
		// 空 targets：无授权效力。
		try {
			bridgeCall("prepare", prepareJson(UUID.randomUUID().toString(), projectId, grantId, List.of(), "0.100000"));
			throw new AssertionError("空 targets 的 prepare 必须失败");
		} catch (IllegalStateException expected) {
			assertThat(expected.getMessage()).isNotBlank();
		}
		Long executions = db.sql("SELECT COUNT(*) FROM hypit_execution WHERE grant_id = CAST(:g AS uuid)")
				.bind("g", grantId).map((row, meta) -> row.get(0, Long.class)).one().block();
		assertThat(executions).as("拒绝路径不得留下执行行（Provider 调用计数 0）").isEqualTo(0L);
	}

	@Test
	@DisplayName("TC-F2-07-03 并发预算 CAS：上限1.0 两笔0.7 并发最多一笔获准")
	void concurrentBudgetReservationCapsAtGrantMax() throws Exception {
		String projectId = seedProject();
		String grantId = createGrant(projectId, List.of("final.video"), "1.000000", false);
		String opA = UUID.randomUUID().toString();
		String opB = UUID.randomUUID().toString();

		// 并发两个 prepare（真实 Java 事务锁序列化）。
		var execA = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				return bridgeCall("prepare", prepareJson(opA, projectId, grantId, List.of("final.video"), "0.700000"));
			} catch (Exception error) {
				return "FAILED:" + error.getMessage();
			}
		});
		var execB = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try {
				return bridgeCall("prepare", prepareJson(opB, projectId, grantId, List.of("final.video"), "0.700000"));
			} catch (Exception error) {
				return "FAILED:" + error.getMessage();
			}
		});
		String a = execA.get(90, TimeUnit.SECONDS);
		String b = execB.get(90, TimeUnit.SECONDS);
		long granted = (a.contains("permitId") ? 1 : 0) + (b.contains("permitId") ? 1 : 0);
		assertThat(granted).as("并发 0.7+0.7 > 1.0 只允许一笔获准（实际 A=%s B=%s）", a, b).isLessThanOrEqualTo(1);

		BigDecimal reserved = db
				.sql("SELECT COALESCE(SUM(estimated_cost),0) FROM hypit_execution WHERE grant_id = CAST(:g AS uuid)"
						+ " AND state IN ('prepared','submitted','unknown')")
				.bind("g", grantId).map((row, meta) -> row.get(0, BigDecimal.class)).one().block();
		assertThat(reserved).as("总预留不得超上限").isLessThanOrEqualTo(new BigDecimal("1.000000"));
	}

	@Test
	@DisplayName("TC-F2-07-04 unknown/重放：同 operationId 不产生第二次执行或费用")
	void replaySameOperationIdNeverDoubleBooks() throws Exception {
		String projectId = seedProject();
		String grantId = createGrant(projectId, List.of("final.video"), "5.000000", false);
		String operationId = UUID.randomUUID().toString();

		String first = bridgeCall("prepare",
				prepareJson(operationId, projectId, grantId, List.of("final.video"), "0.300000"));
		assertThat(first).contains("permitId");
		// Provider 已接受但 broker 丢响应：同 operationId、同 requestHash 重放 → 原行幂等返回。
		String replay = bridgeCall("prepare",
				prepareJson(operationId, projectId, grantId, List.of("final.video"), "0.300000"));
		assertThat(replay).contains("permitId");
		Long rows = db.sql("SELECT COUNT(*) FROM hypit_execution WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", operationId).map((row, meta) -> row.get(0, Long.class)).one().block();
		assertThat(rows).isEqualTo(1L);

		// requestHash 变化的重放被拒（不得换哈希重跑）。
		try {
			bridgeCall("prepare", prepareJson(operationId, projectId, grantId, List.of("final.video"), "0.900000")
					.replace("h".repeat(64), "x".repeat(64)));
			throw new AssertionError("异 requestHash 重放必须被拒");
		} catch (IllegalStateException expected) {
			assertThat(expected.getMessage()).isNotBlank();
		}
	}
}
