package com.grassland.intelligence.hypit.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge.ExecutionPermit;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge.PrepareRequest;
import com.grassland.intelligence.hypit.execution.HypitExternalExecutionBridge.Settlement;
import com.grassland.intelligence.hypit.execution.HypitExecutionRepository.GrantRow;
import com.grassland.intelligence.security.IntelligenceException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 外部执行授权桥（任务书 #107-1 C107-07 / TC107-07：K12.3 prepare 原子预算 CAS、 K12.6
 * 幂等重放、K12.8 unknown 保留、回执只补不清）。
 *
 * <p>
 * 真 PostgreSQL：variant 额度并发领取恰好一个、假 grant/模型 hash 变更 409、 同 operation
 * 重放返回新许可、终态回执幂等不改已落字段；HTTP 面内部 token 缺失/错误 401 且零副作用。凭据内容不经本桥（许可只带 store
 * 引用），「缺 credentials」的 provider 侧行为由 B/tests/providers/provider-contracts 覆盖。
 */
@TestPropertySource(properties = {"hypit.enabled=true"})
class HypitExecutionBridgeIT extends IntelligenceItSupport {

	private static final String TOKEN = "it-hypit-internal-token-0123456789abcdef"; // secret-scan: allow
	private static final String OWNER = "cccccccc-0000-4000-8000-000000000007";

	@Autowired
	HypitExternalExecutionBridge bridge;

	@Autowired
	HypitExecutionRepository executions;

	@DynamicPropertySource
	static void props(DynamicPropertyRegistry registry) {
		registry.add("hypit.internal-token", () -> TOKEN);
	}

	private UUID projectId;

	@BeforeEach
	void clean() {
		db.sql("DELETE FROM hypit_execution WHERE grant_id IN"
				+ " (SELECT id FROM hypit_execution_grant WHERE account_id = :owner)").bind("owner", OWNER).then()
				.then(db.sql("DELETE FROM hypit_execution_grant WHERE account_id = :owner").bind("owner", OWNER).then())
				.then(db.sql("DELETE FROM hypit_project WHERE account_id = :owner").bind("owner", OWNER).then())
				.block(Duration.ofSeconds(10));
		projectId = UUID.randomUUID();
		db.sql("INSERT INTO hypit_project(id, account_id, workspace_id, title, mode, status)"
				+ " VALUES (CAST(:id AS uuid), :owner, CAST(:ws AS uuid), 'bridge-it', 'clone', 'ready')")
				.bind("id", projectId.toString()).bind("owner", OWNER).bind("ws", UUID.randomUUID().toString()).then()
				.block(Duration.ofSeconds(10));
	}

	private GrantRow grant(int variantCount, String maxCost, Instant expiresAt) {
		return executions.insertGrant(UUID.randomUUID(), projectId, OWNER, null, null, "f".repeat(64),
				"{\"need\":\"video.clone\"}", maxCost == null ? null : new BigDecimal(maxCost), "USD", maxCost == null,
				variantCount, expiresAt).block(Duration.ofSeconds(10));
	}

	private PrepareRequest request(UUID grantId, UUID operationId, String requestHash) {
		return new PrepareRequest(operationId, projectId, null, null, "need-" + operationId, "video.clone",
				"seedance-2-mini", "hypihub.default", requestHash, grantId, new BigDecimal("0.50"));
	}

	private static IntelligenceException expectConflict(Mono<?> call, String code) {
		try {
			call.block(Duration.ofSeconds(20));
			throw new AssertionError("expected conflict " + code + " but call succeeded");
		} catch (IntelligenceException error) {
			assertThat(error.code()).isEqualTo(code);
			assertThat(error.status()).isEqualTo(409);
			return error;
		}
	}

	// ---------- K12.3：并发抢预算 ----------

	@Test
	void concurrentPrepareForSingleVariantGrantAdmitsExactlyOne() {
		GrantRow grant = grant(1, "5.00", Instant.now().plus(Duration.ofMinutes(10)));
		List<Object> outcomes = Flux.range(0, 2)
				.flatMap(i -> bridge.prepare(request(grant.id(), UUID.randomUUID(), "a".repeat(64)))
						.map(permit -> (Object) permit)
						.onErrorResume(IntelligenceException.class, error -> Mono.just((Object) error)), 2)
				.collectList().block(Duration.ofSeconds(30));
		// 两个并发 prepare：恰好一个许可，另一个 capacity 409；库里恰好一行 execution。
		assertThat(outcomes).hasSize(2);
		long conflicts = outcomes.stream()
				.filter(o -> o instanceof IntelligenceException error && "hypit_capacity_exceeded".equals(error.code()))
				.count();
		long permits = outcomes.stream().filter(o -> o instanceof ExecutionPermit).count();
		assertThat(permits).isEqualTo(1);
		assertThat(conflicts).as("并发领取 variant=1 的授权必须恰好拒绝一个").isEqualTo(1);
		Long rows = db.sql("SELECT count(*) AS n FROM hypit_execution WHERE grant_id = CAST(:g AS uuid)")
				.bind("g", grant.id().toString()).map((r, m) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(rows).isEqualTo(1L);
	}

	// ---------- K12.3：grant 校验 ----------

	@Test
	void prepareRejectsMissingRevokedExpiredMismatchedAndOverpricedRequests() {
		// 假 grant：409 hypit_not_found，零副作用。
		expectConflict(bridge.prepare(request(UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64))),
				"hypit_not_found");

		GrantRow revoked = grant(2, "5.00", Instant.now().plus(Duration.ofMinutes(10)));
		executions.revokeGrant(revoked.id()).block(Duration.ofSeconds(10));
		expectConflict(bridge.prepare(request(revoked.id(), UUID.randomUUID(), "a".repeat(64))),
				"hypit_state_conflict");

		GrantRow expired = grant(2, "5.00", Instant.now().minus(Duration.ofMinutes(1)));
		expectConflict(bridge.prepare(request(expired.id(), UUID.randomUUID(), "a".repeat(64))),
				"hypit_state_conflict");

		// 工程不匹配：授权绑定 project，不能跨工程复用。
		GrantRow bound = grant(2, "5.00", Instant.now().plus(Duration.ofMinutes(10)));
		PrepareRequest crossProject = new PrepareRequest(UUID.randomUUID(), UUID.randomUUID(), null, null, "need-x",
				"video.clone", "seedance-2-mini", "hypihub.default", "a".repeat(64), bound.id(),
				new BigDecimal("0.50"));
		expectConflict(bridge.prepare(crossProject), "hypit_state_conflict");

		// 估价超上限：K12.7 缺价不可默认无限；超价必须拒。
		GrantRow capped = grant(2, "0.10", Instant.now().plus(Duration.ofMinutes(10)));
		expectConflict(bridge.prepare(request(capped.id(), UUID.randomUUID(), "a".repeat(64))),
				"hypit_capacity_exceeded");

		Long rows = db
				.sql("SELECT count(*) AS n FROM hypit_execution WHERE grant_id IN"
						+ " (CAST(:r AS uuid), CAST(:e AS uuid), CAST(:b AS uuid), CAST(:c AS uuid))")
				.bind("r", revoked.id().toString()).bind("e", expired.id().toString()).bind("b", bound.id().toString())
				.bind("c", capped.id().toString()).map((r, m) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(rows).as("全部拒绝路径必须零副作用").isZero();
	}

	// ---------- K12.6：同 operation 重放 ----------

	@Test
	void sameOperationReplaysIdempotentlyAndHashChangeConflicts() {
		GrantRow grant = grant(3, "5.00", Instant.now().plus(Duration.ofMinutes(10)));
		UUID operationId = UUID.randomUUID();
		ExecutionPermit first = bridge.prepare(request(grant.id(), operationId, "a".repeat(64)))
				.block(Duration.ofSeconds(20));
		assertThat(first.operationId()).isEqualTo(operationId);
		assertThat(first.credentialStore()).isEqualTo("file");
		assertThat(first.credentialKey()).isEqualTo("hypihub.default.apiKey");

		// 同 operation + 同 hash 重放：新许可（许可过期后重新 prepare 的真实路径），不涨行。
		ExecutionPermit replay = bridge.prepare(request(grant.id(), operationId, "a".repeat(64)))
				.block(Duration.ofSeconds(20));
		assertThat(replay.operationId()).isEqualTo(operationId);
		assertThat(replay.permitId()).isNotEqualTo(first.permitId());

		// 模型/请求变更（requestHash 不同）→ 409，绝不改写已冻结的执行。
		expectConflict(bridge.prepare(request(grant.id(), operationId, "b".repeat(64))), "hypit_idempotency_conflict");

		Long rows = db.sql("SELECT count(*) AS n FROM hypit_execution WHERE grant_id = CAST(:g AS uuid)")
				.bind("g", grant.id().toString()).map((r, m) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(rows).isEqualTo(1L);
	}

	// ---------- K12.6/K12.8：回执幂等与终态 ----------

	@Test
	void receiptSettlesOnceAndTerminalStateIsFinal() {
		GrantRow grant = grant(3, "5.00", Instant.now().plus(Duration.ofMinutes(10)));
		UUID operationId = UUID.randomUUID();
		bridge.prepare(request(grant.id(), operationId, "a".repeat(64))).block(Duration.ofSeconds(20));

		// 未 prepare 的 operation：回执不能无中生有。
		expectConflict(bridge.complete(UUID.randomUUID(), "{}", BigDecimal.ONE), "hypit_not_found");

		Settlement first = bridge.complete(operationId, "{\"requestId\":\"r1\"}", new BigDecimal("2.50"))
				.block(Duration.ofSeconds(20));
		assertThat(first.settled()).isTrue();
		assertThat(first.execution().state()).isEqualTo("succeeded");

		// 重复回执：幂等不再结算，receipt/actual_cost 只补不清。
		Settlement replay = bridge.complete(operationId, "{\"requestId\":\"r2\"}", new BigDecimal("9.99"))
				.block(Duration.ofSeconds(20));
		assertThat(replay.settled()).isFalse();
		assertThat(replay.execution().receiptJson()).contains("r1").doesNotContain("r2");
		assertThat(replay.execution().actualCost()).isEqualByComparingTo("2.50");

		// 终态不可改判。
		expectConflict(bridge.fail(operationId, null, "late"), "hypit_state_conflict");
		expectConflict(bridge.cancel(operationId, null), "hypit_state_conflict");

		var row = db
				.sql("SELECT state, actual_cost, receipt::text AS receipt FROM hypit_execution"
						+ " WHERE operation_id = CAST(:op AS uuid)")
				.bind("op", operationId.toString())
				.map((r, m) -> java.util.Map.of("state", String.valueOf(r.get("state", String.class)), "actual_cost",
						String.valueOf(r.get("actual_cost", BigDecimal.class)), "receipt",
						String.valueOf(r.get("receipt", String.class))))
				.one().block(Duration.ofSeconds(10));
		assertThat(row.get("state")).isEqualTo("succeeded");
		assertThat(new BigDecimal(row.get("actual_cost"))).as("numeric(20,6) 按值比较（2.500000）")
				.isEqualByComparingTo("2.50");
	}

	/** K12.8：unknown 保留占用（计入 variant 额度）但可被真实回执收口。 */
	@Test
	void unknownKeepsCapacityUntilDefiniteReceipt() {
		GrantRow grant = grant(1, "5.00", Instant.now().plus(Duration.ofMinutes(10)));
		UUID operationId = UUID.randomUUID();
		bridge.prepare(request(grant.id(), operationId, "a".repeat(64))).block(Duration.ofSeconds(20));
		Settlement unknown = bridge.markUnknown(operationId, "{\"timeout\":true}").block(Duration.ofSeconds(20));
		assertThat(unknown.settled()).isTrue();
		assertThat(unknown.execution().state()).isEqualTo("unknown");

		// unknown 占用额度：variant=1 已被占，新 operation 不得再领。
		expectConflict(bridge.prepare(request(grant.id(), UUID.randomUUID(), "a".repeat(64))),
				"hypit_capacity_exceeded");

		// 真实回执把 unknown 收口为 succeeded。
		Settlement resolved = bridge.complete(operationId, "{\"requestId\":\"late\"}", new BigDecimal("1.00"))
				.block(Duration.ofSeconds(20));
		assertThat(resolved.settled()).isTrue();
		assertThat(resolved.execution().state()).isEqualTo("succeeded");

		// cancel 释放额度：variant 口径排除 cancelled。
		GrantRow grant2 = grant(1, "5.00", Instant.now().plus(Duration.ofMinutes(10)));
		UUID operation2 = UUID.randomUUID();
		bridge.prepare(request(grant2.id(), operation2, "c".repeat(64))).block(Duration.ofSeconds(20));
		bridge.cancel(operation2, "user aborted").block(Duration.ofSeconds(20));
		ExecutionPermit afterCancel = bridge.prepare(request(grant2.id(), UUID.randomUUID(), "d".repeat(64)))
				.block(Duration.ofSeconds(20));
		assertThat(afterCancel).isNotNull();
	}

	// ---------- HTTP 面：内部 token ----------

	@Test
	void internalEndpointsRequireExactBearerTokenWithoutSideEffects() {
		GrantRow grant = grant(3, "5.00", Instant.now().plus(Duration.ofMinutes(10)));
		String body = "{\"operationId\":\"" + UUID.randomUUID() + "\",\"projectId\":\"" + projectId
				+ "\",\"needId\":\"need-http\",\"capability\":\"video.clone\",\"model\":\"seedance-2-mini\","
				+ "\"endpointId\":\"hypihub.default\",\"requestHash\":\"" + "e".repeat(64) + "\",\"grantId\":\""
				+ grant.id() + "\",\"estimatedCost\":0.5}";

		// 缺 token / 错 token：401，且不产生任何 execution 行。
		client().post().uri("/internal/hypit/executions/prepare").contentType(MediaType.APPLICATION_JSON)
				.bodyValue(body).exchange().expectStatus().isUnauthorized();
		client().post().uri("/internal/hypit/executions/prepare")
				.header("Authorization", "Bearer wrong-token-000000000000000000000000")
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isUnauthorized();
		Long rows = db.sql("SELECT count(*) AS n FROM hypit_execution WHERE grant_id = CAST(:g AS uuid)")
				.bind("g", grant.id().toString()).map((r, m) -> r.get("n", Long.class)).one()
				.block(Duration.ofSeconds(10));
		assertThat(rows).as("401 拒绝必须零副作用").isZero();

		// 正确 token：HTTP 契约闭环（许可含凭据引用、不曝光 secret）。
		client().post().uri("/internal/hypit/executions/prepare").header("Authorization", "Bearer " + TOKEN)
				.contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.credentialRef.store").isEqualTo("file").jsonPath("$.credentialRef.key")
				.isEqualTo("hypihub.default.apiKey").jsonPath("$.permitId").isNotEmpty().jsonPath("$.expiresAt")
				.isNotEmpty();

		// token 不属于任何用户身份：401 不触发任何「换账户重试」语义（K12.6 拒绝即终局）。
		client().post().uri("/internal/hypit/executions/complete").header("Authorization", "Bearer " + TOKEN)
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue("{\"operationId\":\"" + UUID.randomUUID() + "\",\"receipt\":{}}").exchange().expectStatus()
				.isEqualTo(409);
	}
}
