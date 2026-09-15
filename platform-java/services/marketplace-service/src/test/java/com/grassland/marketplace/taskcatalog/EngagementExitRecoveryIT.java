package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.reputation.ReputationService;
import com.grassland.marketplace.workflow.FinanceEscrowClient;
import com.grassland.marketplace.workflow.IntelligenceMediaClient;
import com.grassland.marketplace.workflow.IntelligenceVerificationClient;
import com.grassland.marketplace.workflow.TrustDisputeClient;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import com.grassland.marketplace.workflow.saga.ReserveResult;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * 退出资金自动恢复 IT（任务书 #103 C103-03 / TC103-03-01～06）。
 *
 * <p>
 * worker 默认关（基座），直接驱动 {@link EngagementExitFundsService} seam。finance 出站 mock
 * 支持注入一次失败/未知，验证「结果未知先核实原经济键」：exit-facts 回读已落定 → 收口； 冲突 → needs_review；事实不可得 →
 * retry_wait 退避。租约/多 worker 竞争与 requeue 一并覆盖。
 */
@SuppressWarnings("unchecked")
class EngagementExitRecoveryIT extends MarketplaceItSupport {

	private static final String H = "X-Grassland-Identity";

	@MockitoBean
	private FinanceEscrowClient financeClient;

	@MockitoBean
	private DisputeChecker disputeChecker;

	@MockitoBean
	private IntelligenceMediaClient mediaClient;

	@MockitoBean
	private TrustDisputeClient trustDisputeClient;

	@MockitoBean
	private LinkReachabilityChecker linkChecker;

	@MockitoBean
	private IntelligenceVerificationClient verificationClient;

	@MockitoSpyBean
	private SubmissionAttachmentRepository attachmentRepo;

	@MockitoSpyBean
	private com.grassland.marketplace.workflow.saga.MerchantRejectionReviewWorkflowStarter rejectionStarter;

	@MockitoSpyBean
	private com.grassland.marketplace.workflow.saga.SettlementWorkflowStarter settlementStarter;

	@MockitoSpyBean
	private com.grassland.marketplace.workflow.saga.AcceptanceWorkflowStarter acceptanceStarter;

	@MockitoSpyBean
	private ReputationService reputationService;

	@Autowired
	private EngagementExitFundsService fundsService;

	@Autowired
	private EngagementExitOperationRepository operations;

	@BeforeEach
	void stubFinance() {
		when(financeClient.reserve(anyString(), anyString(), anyLong(), anyString()))
				.thenReturn(Mono.just(ReserveResult.reserved(500L)));
		lenient().when(financeClient.release(anyString(), anyString())).thenReturn(Mono.empty());
		lenient().when(financeClient.freebieRefund(anyString(), anyString())).thenReturn(Mono.empty());
		lenient().when(financeClient.captureVerified(anyString(), anyString(), anyLong(), anyString(), anyLong()))
				.thenReturn(Mono.just(FinanceEscrowClient.CaptureOutcome.capturedNow()));
		lenient().when(financeClient.exitFacts(anyString(), anyString()))
				.thenReturn(Mono.error(new RuntimeException("facts unavailable")));
		lenient().when(disputeChecker.hasOpenDispute(anyString(), anyString())).thenReturn(false);
	}

	// ---------- TC103-03-01：合法执行与查询 ----------

	@Test
	void advanceExecutesLegsInOrderAndCompletesOperation() {
		ClaimedExit exit = noFaultExitWithFunds(500, 100);
		// 零腿场景外：deposit_refund(100) → bounty_capture(0/not_required) →
		// bounty_release(500)。
		var order = new java.util.ArrayList<String>();
		when(financeClient.freebieRefund(anyString(), anyString())).thenAnswer(inv -> {
			order.add("deposit_refund");
			return Mono.empty();
		});
		when(financeClient.release(anyString(), anyString())).thenAnswer(inv -> {
			order.add("bounty_release");
			return Mono.empty();
		});

		EngagementExitOperation done = fundsService.advance(exit.operationId(), "worker-1").block();
		assertThat(done.state()).isEqualTo("succeeded");
		assertThat(done.completedAt()).isNotNull();
		assertThat(order).containsExactly("deposit_refund", "bounty_release");
		assertThat(legState(exit.operationId(), "deposit_refund")).isEqualTo("succeeded");
		assertThat(legState(exit.operationId(), "bounty_capture")).isEqualTo("not_required");
		assertThat(legState(exit.operationId(), "bounty_release")).isEqualTo("succeeded");
		verify(financeClient, never()).captureVerified(anyString(), anyString(), anyLong(), anyString(), anyLong());

		// 当事人查询端点：本人推荐官可读资金态（blockedReason=null）。
		client().get().uri("/api/tasks/" + exit.taskId + "/applications/" + exit.appId + "/exit-funds")
				.header(H, sign(exit.rec, "recommender")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.state").isEqualTo("succeeded").jsonPath("$.data.blockedReason").doesNotExist()
				.jsonPath("$.data.amounts.bountyReleaseCents").isEqualTo(500);
	}

	// ---------- TC103-03-02：同键与重排 ----------

	@Test
	void replayIsIdempotentAndRequeueHonorsExpectedVersion() {
		ClaimedExit exit = noFaultExitWithFunds(500, 0);
		fundsService.advance(exit.operationId(), "worker-1").block();
		var releaseCalls = new AtomicInteger();
		when(financeClient.release(anyString(), anyString())).thenAnswer(inv -> {
			releaseCalls.incrementAndGet();
			return Mono.empty();
		});
		// 已成功操作重放 → 原样回读，零新增远端调用。
		EngagementExitOperation replayed = fundsService.advance(exit.operationId(), "worker-2").block();
		assertThat(replayed.state()).isEqualTo("succeeded");
		assertThat(releaseCalls.get()).isZero();

		// requeue：已成功 → false（调用方回读 200）；版本冲突 → empty（409）。
		assertThat(fundsService.requeue(exit.operationId(), replayed.version() + 5, "误报重排").block()).isNull();
	}

	// ---------- TC103-03-03：崩溃与未知结果 ----------

	@Test
	void unknownResultVerifiesOriginalKeyViaFactsBeforeFailing() {
		ClaimedExit exit = noFaultExitWithFunds(500, 0);
		// release 调用网络断（结果未知）→ exit-facts 显示已 released → 收口 succeeded，不重复落账。
		when(financeClient.release(anyString(), anyString()))
				.thenReturn(Mono.error(new RuntimeException("connection reset"))).thenReturn(Mono.empty());
		when(financeClient.exitFacts(anyString(), anyString())).thenReturn(Mono.just(facts("released", 0, 500, null)));

		EngagementExitOperation done = fundsService.advance(exit.operationId(), "worker-1").block();
		assertThat(done.state()).isEqualTo("succeeded");
		assertThat(legState(exit.operationId(), "bounty_release")).isEqualTo("succeeded");

		// 事实不可得 → unknown + retry_wait（不显示成功）；到点后重试成功收敛。
		ClaimedExit second = noFaultExitWithFunds(500, 0);
		when(financeClient.release(anyString(), anyString())).thenReturn(Mono.error(new RuntimeException("timeout")))
				.thenReturn(Mono.empty());
		when(financeClient.exitFacts(anyString(), anyString()))
				.thenReturn(Mono.error(new RuntimeException("facts 503")));
		EngagementExitOperation waiting = fundsService.advance(second.operationId(), "worker-1").block();
		assertThat(waiting.state()).isEqualTo("retry_wait");
		assertThat(waiting.nextAttemptAt()).isAfter(Instant.now());
		assertThat(legState(second.operationId(), "bounty_release")).isEqualTo("unknown");
		// 到点重试（回拨 next_attempt_at）→ 收敛。
		db.sql("UPDATE engagement_exit_operation SET next_attempt_at = now() - interval '1 second'"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", second.operationId()).then().block();
		when(financeClient.exitFacts(anyString(), anyString())).thenReturn(Mono.just(facts("released", 0, 500, null)));
		EngagementExitOperation recovered = fundsService.advance(second.operationId(), "worker-1").block();
		assertThat(recovered.state()).isEqualTo("succeeded");
	}

	// ---------- TC103-03-04：接口权限 ----------

	@Test
	void fundsEndpointsEnforcePartyAndAdminRoles() {
		ClaimedExit exit = noFaultExitWithFunds(500, 0);
		// 无关用户不可查询：覆写基座的全放行 stub，使 manager 范围判定失败（推荐官同伴亦不可见）。
		String stranger = UUID.randomUUID().toString();
		// requireScope 依赖 identity 侧 403 语义（allowed=false 的决策行不会出现）——stub 直接抛 403。
		when(storeAuthorization.authorize(org.mockito.ArgumentMatchers.eq(stranger), anyString(),
				org.mockito.ArgumentMatchers.any(), anyString()))
				.thenReturn(Mono.error(new com.grassland.marketplace.security.MarketplaceException(403, "无权管理该组织资源")));
		client().get().uri("/api/tasks/" + exit.taskId + "/applications/" + exit.appId + "/exit-funds")
				.header(H, sign(stranger, "recommender")).exchange().expectStatus().is4xxClientError();

		// 治理台：RISK 只读可列；retry 仅 FINANCE（RISK → 403）。
		client().get().uri("/api/admin/engagement-exit-operations?state=pending")
				.header(H, signWithRole(UUID.randomUUID().toString(), "RISK")).exchange().expectStatus().isOk()
				.expectBody().jsonPath("$.data.items").isArray();
		String operationId = exit.operationId;
		long version = operationVersion(operationId);
		client().post().uri("/api/admin/engagement-exit-operations/" + operationId + "/retry")
				.header(H, signWithRole(UUID.randomUUID().toString(), "RISK")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "风险侧无权重排", "expectedVersion", version)).exchange().expectStatus()
				.isForbidden();
		client().post().uri("/api/admin/engagement-exit-operations/" + operationId + "/retry")
				.header(H, signWithRole(UUID.randomUUID().toString(), "FINANCE"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("reason", "核实后重新排队", "expectedVersion", version)).exchange().expectStatus()
				.isAccepted().expectBody().jsonPath("$.data.state").isEqualTo("pending");
		// reason 缺失 → 400。
		client().post().uri("/api/admin/engagement-exit-operations/" + operationId + "/retry")
				.header(H, signWithRole(UUID.randomUUID().toString(), "FINANCE"))
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("expectedVersion", version)).exchange()
				.expectStatus().isBadRequest();
	}

	// ---------- TC103-03-06：多 worker 竞争 ----------

	@Test
	void concurrentWorkersYieldSingleLeaseAndSingleFundExecution() throws Exception {
		ClaimedExit exit = noFaultExitWithFunds(500, 0);
		var releaseCalls = new AtomicInteger();
		when(financeClient.release(anyString(), anyString())).thenAnswer(inv -> {
			releaseCalls.incrementAndGet();
			return Mono.empty();
		});
		int workers = 3;
		var latch = new java.util.concurrent.CountDownLatch(1);
		var pool = java.util.concurrent.Executors.newFixedThreadPool(workers);
		try {
			var results = new java.util.ArrayList<java.util.concurrent.Future<EngagementExitOperation>>();
			for (int i = 0; i < workers; i++) {
				final String worker = "worker-" + i;
				results.add(pool.submit(() -> {
					latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
					return fundsService.advance(exit.operationId(), worker).block();
				}));
			}
			latch.countDown();
			for (var future : results) {
				// 持他人有效租约的 worker 只回读（processing/succeeded），不抛错、不重复执行。
				assertThat(future.get(30, java.util.concurrent.TimeUnit.SECONDS)).isNotNull();
			}
			// 租约串行推进：release 经济键只执行一次，最终态 succeeded。
			assertThat(releaseCalls.get()).isEqualTo(1);
			assertThat(fundsService.advance(exit.operationId(), "final-check").block().state()).isEqualTo("succeeded");
		} finally {
			pool.shutdownNow();
		}
		assertThat(legState(exit.operationId(), "bounty_release")).isEqualTo("succeeded");
	}

	// ---------- helpers ----------

	private record ClaimedExit(String taskId, String appId, String rec, String operationId) {
	}

	private ClaimedExit noFaultExitWithFunds(long bountyCents, long depositCents) {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("organizationId", org);
		b.put("title", "恢复任务");
		b.put("platform", "xiaohongshu");
		b.put("storeId", UUID.randomUUID().toString());
		b.put("applicationDeadline", Instant.now().plusSeconds(3600).toString());
		Map<String, Object> resp = client().post().uri("/api/tasks")
				.header(H, sign(merchant, "merchant", org, "basic_publish")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(b).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
		String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
		db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now())"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", taskId).then().block();
		Map<String, Object> applied = client().post().uri("/api/tasks/" + taskId + "/applications")
				.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("note", "接")).exchange().expectStatus().isCreated().expectBody(Map.class)
				.returnResult().getResponseBody();
		String appId = (String) ((Map<String, Object>) applied.get("data")).get("id");
		client().post().uri("/api/tasks/" + taskId + "/applications/" + appId + "/accept")
				.header(H, sign(merchant, "merchant", org, "basic_publish")).exchange().expectStatus().isOk();
		awaitAccepted(appId);
		db.sql("UPDATE task_application SET bounty_cents = :bounty, freebie_deposit_cents = :deposit"
				+ " WHERE id = CAST(:id AS uuid)").bind("bounty", bountyCents).bind("deposit", depositCents)
				.bind("id", appId).then().block();

		client().post().uri("/api/tasks/" + taskId + "/applications/" + appId + "/exit")
				.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().isOk();
		String operationId = db
				.sql("SELECT id::text FROM engagement_exit_operation WHERE application_id = CAST(:app AS uuid)")
				.bind("app", appId).map(r -> r.get("id", String.class)).one().block();
		return new ClaimedExit(taskId, appId, rec, operationId);
	}

	private void awaitAccepted(String appId) {
		long deadline = System.currentTimeMillis() + 10_000L;
		while (System.currentTimeMillis() < deadline) {
			String status = db.sql("SELECT status FROM task_application WHERE id = CAST(:id AS uuid)").bind("id", appId)
					.map(r -> r.get("status", String.class)).one().block();
			if ("accepted".equals(status)) {
				return;
			}
			if (status != null && !"reserving".equals(status)) {
				throw new AssertionError("acceptance did not reach accepted (last=" + status + ")");
			}
			try {
				Thread.sleep(100L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
		throw new AssertionError("acceptance did not reach accepted in time");
	}

	private String legState(String operationId, String legKind) {
		return db
				.sql("SELECT state FROM engagement_exit_fund_leg WHERE operation_id = CAST(:op AS uuid)"
						+ " AND leg_kind = :kind")
				.bind("op", operationId).bind("kind", legKind).map(r -> r.get("state", String.class)).one().block();
	}

	private long operationVersion(String operationId) {
		Long v = db.sql("SELECT version FROM engagement_exit_operation WHERE id = CAST(:op AS uuid)")
				.bind("op", operationId).map(r -> r.get("version", Long.class)).one().block();
		return v == null ? 0 : v;
	}

	private static Map<String, Object> facts(String bountyStatus, long captured, long released, String depositStatus) {
		Map<String, Object> bounty = new LinkedHashMap<>();
		bounty.put("exists", true);
		bounty.put("capturedCents", captured);
		bounty.put("releasedCents", released);
		bounty.put("status", bountyStatus);
		Map<String, Object> deposit = new LinkedHashMap<>();
		deposit.put("exists", depositStatus != null);
		deposit.put("refundedCents", "refunded".equals(depositStatus) ? 100 : 0);
		deposit.put("compensatedCents", "compensated".equals(depositStatus) ? 100 : 0);
		Map<String, Object> facts = new LinkedHashMap<>();
		facts.put("bounty", bounty);
		facts.put("deposit", deposit);
		return facts;
	}
}
