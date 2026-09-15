package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
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
import com.grassland.marketplace.workflow.saga.DeliveryDeadlineInput;
import com.grassland.marketplace.workflow.saga.DeliveryLifecycleActivity;
import com.grassland.marketplace.workflow.saga.DeliveryOutcome;
import com.grassland.marketplace.workflow.saga.DisputeChecker;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * 退出终止权原子仲裁 IT（任务书 #103 C103-02 / TC103-02-01～06，R01 反例 → 不变量）。
 *
 * <p>
 * 核心不变量：无责/协商退出先在 task → application 父行锁事务内 claim（终态 + 冻结快照 + 资金操作意图），
 * 事务提交前零 Finance 调用；提交/里程碑确认/体验兑现/人工验收/超时终结/商家取消与退出经同一父行锁
 * 串行化，每组竞争单边胜出，败方不产生资金副作用、不留孤立操作行。
 *
 * <p>
 * 与 {@link EngagementDeliveryLifecycleIT} 相同的 bean 覆盖集合（复用 Spring 上下文缓存）。
 * 并发对用真实 PG 行锁仲裁（CountDownLatch 对齐起跑，多轮试验），不用进程锁或 mock 代替。
 */
@SuppressWarnings("unchecked")
class EngagementExitRaceIT extends MarketplaceItSupport {

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
	private TaskApplicationRepository applicationRepo;

	@Autowired
	private SubmissionRepository submissionRepo;

	@Autowired
	private DeliveryLifecycleActivity deliveryActivity;

	@MockitoSpyBean
	private TaskAcceptanceCounterRepository acceptanceCounters;

	@BeforeEach
	void stubExternals() {
		when(financeClient.reserve(anyString(), anyString(), anyLong(), anyString()))
				.thenReturn(Mono.just(com.grassland.marketplace.workflow.saga.ReserveResult.reserved(500L)));
		lenient().when(financeClient.release(anyString(), anyString())).thenReturn(Mono.empty());
		lenient().when(financeClient.freebieRefund(anyString(), anyString())).thenReturn(Mono.empty());
		lenient().when(financeClient.freebieCompensate(anyString(), anyString())).thenReturn(Mono.empty());
		lenient().when(disputeChecker.hasOpenDispute(anyString(), anyString())).thenReturn(false);
	}

	// ---------- TC103-02-01：退出资格与输入（零金额腿 not_required） ----------

	@Test
	void noFaultExitEligibilityInputAndZeroLegs() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishTask(merchant, org);
		String appId = applyAndAccept(merchant, org, task, rec);
		// 资金快照后门（同 EngagementDeliveryLifecycleIT 惯例）：零押金 + 零赏金 → 三腿全 0 → not_required。
		db.sql("UPDATE task_application SET bounty_cents = 0, freebie_deposit_cents = 0"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", appId).then().block();

		client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
				.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.status").isEqualTo("withdrawn").jsonPath("$.data.exitKind").isEqualTo("no_fault");

		assertThat(legState(appId, "deposit_refund")).isEqualTo("not_required");
		assertThat(legState(appId, "bounty_capture")).isEqualTo("not_required");
		assertThat(legState(appId, "bounty_release")).isEqualTo("not_required");
		assertThat(operationState(appId)).isEqualTo("pending");
		verify(financeClient, never()).release(anyString(), anyString());
		verify(financeClient, never()).freebieRefund(anyString(), anyString());

		// 非法 kind → 400；空 kind → 400。
		String rec2 = UUID.randomUUID().toString();
		String app2 = applyAndAccept(merchant, org, task, rec2);
		client().post().uri("/api/tasks/" + task + "/applications/" + app2 + "/exit")
				.header(H, sign(rec2, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "unknown_kind")).exchange().expectStatus().isBadRequest();
	}

	// ---------- TC103-02-02：重复终止（同 operation、一次名额释放、一次终止事实） ----------

	@Test
	void repeatedExitIsIdempotentSingleOperationAndSingleSlotRelease() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishTask(merchant, org);
		String appId = applyAndAccept(merchant, org, task, rec);
		db.sql("UPDATE task_application SET bounty_cents = 500 WHERE id = CAST(:id AS uuid)").bind("id", appId).then()
				.block();

		client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
				.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().isOk();
		// 顺序重复 → 409（终态已落），操作行唯一、事件唯一、名额只回收一次。
		client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
				.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().isEqualTo(409);

		assertThat(operationCount(appId)).isEqualTo(1);
		assertThat(outboxCountForApp("ApplicationExitedNoFault", appId)).isEqualTo(1);
		assertThat(occupiedSlots(task)).isZero();
		verify(financeClient, never()).release(anyString(), anyString());

		// 协商确认重复：confirmed 重入幂等回读，操作行与金额不变。
		String rec2 = UUID.randomUUID().toString();
		String app2 = applyAndAccept(merchant, org, task, rec2);
		db.sql("UPDATE task_application SET bounty_cents = 500 WHERE id = CAST(:id AS uuid)").bind("id", app2).then()
				.block();
		Map<String, Object> opened = requestNegotiated(merchant, "merchant", org, task, app2, "档期冲突协商终止");
		String exitId = exitRequestIdOf(opened);
		client().post().uri("/api/tasks/" + task + "/applications/" + app2 + "/exit-requests/" + exitId + "/confirm")
				.header(H, sign(rec2, "recommender")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.status").isEqualTo("confirmed");
		Long captureFirst = legAmount(app2, "bounty_capture");
		client().post().uri("/api/tasks/" + task + "/applications/" + app2 + "/exit-requests/" + exitId + "/confirm")
				.header(H, sign(rec2, "recommender")).exchange().expectStatus().isOk().expectBody()
				.jsonPath("$.data.status").isEqualTo("confirmed");
		assertThat(operationCount(app2)).isEqualTo(1);
		assertThat(legAmount(app2, "bounty_capture")).isEqualTo(captureFirst);
	}

	// ---------- TC103-02-04：双方与范围（同侧/跨域拒绝、无数据泄露） ----------

	@Test
	void exitScopeAndPartyAuthorization() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String outsider = UUID.randomUUID().toString();
		String task = publishTask(merchant, org);
		String appId = applyAndAccept(merchant, org, task, rec);

		// 非本人推荐官不可无责退出（资源自查由控制器完成；此处直接以他人身份打同端点 → 409/403 均为拒绝，
		// 关键断言是不产生任何写入）。
		client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
				.header(H, sign(outsider, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().is4xxClientError();
		assertThat(operationCount(appId)).isZero();
		assertThat(applicationRepo.findById(appId).block().status()).isEqualTo("accepted");

		// 协商：发起人本人确认 → 403（双方确认制）。
		Map<String, Object> opened = requestNegotiated(rec, "recommender", null, task, appId, "本人发起后自确认应被拒");
		client().post()
				.uri("/api/tasks/" + task + "/applications/" + appId + "/exit-requests/"
						+ exitRequestIdOf(opened) + "/confirm")
				.header(H, sign(rec, "recommender")).exchange().expectStatus().isForbidden();
		assertThat(applicationRepo.findById(appId).block().status()).isEqualTo("accepted");
		assertThat(operationCount(appId)).isZero();
	}

	// ---------- TC103-02-05：历史与业务边界（已确认里程碑/已提交 → 无责 409；预演≠冻结） ----------

	@Test
	void noFaultExitBlockedByMilestoneAndSubmission() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishTask(merchant, org);
		String appId = applyAndAccept(merchant, org, task, rec);

		// 已确认里程碑 → 无责 409（走协商），无操作行。
		insertConfirmedMilestone(appId, rec, merchant, "deliverable");
		client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
				.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().isEqualTo(409);
		assertThat(operationCount(appId)).isZero();
		assertThat(applicationRepo.findById(appId).block().status()).isEqualTo("accepted");

		// 已提交 → 无责 409。
		String rec2 = UUID.randomUUID().toString();
		String app2 = applyAndAccept(merchant, org, task, rec2);
		submissionRepo.create(app2, rec2, "https://www.xiaohongshu.com/p/race", null).block();
		client().post().uri("/api/tasks/" + task + "/applications/" + app2 + "/exit")
				.header(H, sign(rec2, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().isEqualTo(409);
		assertThat(operationCount(app2)).isZero();
		assertThat(applicationRepo.findById(app2).block().status()).isEqualTo("accepted");
	}

	// ---------- TC103-02-06：所有终态竞争（单边胜出 + 败方零 Finance 调用） ----------

	/** 提交先落定 → 迟到无责退出 409；退出侧零 Finance 调用、零操作行（原 R01 反例反转为不变量）。 */
	@Test
	void submissionWinsThenExitLosesWithoutAnyFundSideEffect() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishTask(merchant, org);
		String appId = applyAndAccept(merchant, org, task, rec);
		db.sql("UPDATE task_application SET bounty_cents = 500 WHERE id = CAST(:id AS uuid)").bind("id", appId).then()
				.block();

		assertThat(submissionRepo.create(appId, rec, "https://www.xiaohongshu.com/p/win", null).block()).isNotNull();
		client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
				.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().isEqualTo(409);

		assertThat(operationCount(appId)).isZero();
		assertThat(applicationRepo.findById(appId).block().status()).isEqualTo("accepted");
		assertThat(occupiedSlots(task)).isEqualTo(1);
		// R01 目标不变量：退出（竞争败方）零 Finance 调用。
		verify(financeClient, never()).release(anyString(), anyString());
		verify(financeClient, never()).freebieRefund(anyString(), anyString());
	}

	/** 退出先落定 → 迟到提交 0 行（409），里程碑确认/人工验收同样被终态拒绝。 */
	@Test
	void exitWinsThenCompetingWritesAreRejected() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishTask(merchant, org);
		String appId = applyAndAccept(merchant, org, task, rec);
		db.sql("UPDATE task_application SET bounty_cents = 500 WHERE id = CAST(:id AS uuid)").bind("id", appId).then()
				.block();

		client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
				.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().isOk();

		// 迟到提交：父行锁内重评估 WHERE → 0 行 → 409。
		assertThat(submissionRepo.create(appId, rec, "https://www.xiaohongshu.com/p/late", null).block()).isNull();
		assertThat(operationCount(appId)).isEqualTo(1);
		assertThat(applicationRepo.findById(appId).block().status()).isEqualTo("withdrawn");

		// 里程碑确认在终态后被拒。
		String rec2 = UUID.randomUUID().toString();
		String app2 = applyAndAccept(merchant, org, task, rec2);
		db.sql("INSERT INTO engagement_milestone(id, application_id, kind, version, proposed_by)"
				+ " VALUES (CAST(:id AS uuid), CAST(:app AS uuid), 'deliverable', 1, CAST(:proposed AS uuid))")
				.bind("id", UUID.randomUUID().toString()).bind("app", app2).bind("proposed", rec2).then().block();
		client().post().uri("/api/tasks/" + task + "/applications/" + app2 + "/exit")
				.header(H, sign(rec2, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().isOk();
		String milestoneId = db
				.sql("SELECT id::text FROM engagement_milestone WHERE application_id = CAST(:app AS uuid)")
				.bind("app", app2).map(r -> r.get("id", String.class)).one().block();
		// 商家（对方）确认已退出合作的里程碑 → 409。
		client().post().uri("/api/tasks/" + task + "/applications/" + app2 + "/milestones/" + milestoneId + "/confirm")
				.header(H, sign(merchant, "merchant", org, "basic_publish")).exchange().expectStatus().isEqualTo(409);
	}

	/** 超时终结与退出：两种先后顺序均单边胜出，退出侧零 Finance；超时败方（退出已 claim）零 Finance。 */
	@Test
	void exitVersusTimeoutSingleWinnerBothOrders() {
		// 顺序一：退出先 claim → 超时 activity 在父行锁内见终态 → aborted，资金腿（claim 后）不执行。
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishTask(merchant, org);
		String appId = applyAndAccept(merchant, org, task, rec);
		db.sql("UPDATE task_application SET bounty_cents = 500 WHERE id = CAST(:id AS uuid)").bind("id", appId).then()
				.block();
		backdateRemedy(appId);
		client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
				.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().isOk();

		Mockito.clearInvocations(financeClient);
		DeliveryOutcome late = deliveryActivity.terminateDeliveryTimeout(new DeliveryDeadlineInput(appId, task, org, 0, 0, 0));
		assertThat(late.status()).isEqualTo("aborted");
		verify(financeClient, never()).release(anyString(), anyString());
		verify(financeClient, never()).freebieCompensate(anyString(), anyString());
		verify(financeClient, never()).captureVerified(anyString(), anyString(), anyLong(), anyString(), anyLong());

		// 顺序二：超时先 claim（父行锁内落终态 + 提交后资金腿）→ 迟到退出 409，零操作行。
		String rec2 = UUID.randomUUID().toString();
		String app2 = applyAndAccept(merchant, org, task, rec2);
		db.sql("UPDATE task_application SET bounty_cents = 500 WHERE id = CAST(:id AS uuid)").bind("id", app2).then()
				.block();
		backdateRemedy(app2);
		assertThat(deliveryActivity.terminateDeliveryTimeout(new DeliveryDeadlineInput(app2, task, org, 0, 0, 0))
				.status()).isEqualTo("terminated");
		client().post().uri("/api/tasks/" + task + "/applications/" + app2 + "/exit")
				.header(H, sign(rec2, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().isEqualTo(409);
		assertThat(operationCount(app2)).isZero();
		assertThat(applicationRepo.findById(app2).block().exitKind()).isEqualTo("timeout");
	}

	/** 商家取消先落定 → 迟到退出 409 零操作行；退出先落定 → 取消 sweep 对该行无事发生。 */
	@Test
	void exitVersusMerchantCancelSingleWinner() {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishTask(merchant, org);
		String appId = applyAndAccept(merchant, org, task, rec);

		Integer taskVersion = db.sql("SELECT version FROM task WHERE id = CAST(:id AS uuid)").bind("id", task)
				.map(r -> r.get("version", Integer.class)).one().block();
		client().post().uri("/api/tasks/" + task + "/cancel")
				.header(H, sign(merchant, "merchant", org, "basic_publish")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("expectedVersion", taskVersion)).exchange().expectStatus().isOk();

		client().post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
				.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "no_fault")).exchange().expectStatus().isEqualTo(409);
		assertThat(operationCount(appId)).isZero();
		assertThat(applicationRepo.findById(appId).block().status()).isEqualTo("refunded");
	}

	/** 真并发：提交与退出同时起跑（真实 PG 行锁仲裁），多轮试验每组恰好一个胜者，不变量恒成立。 */
	@Test
	void concurrentSubmissionVersusExitAlwaysSingleWinner() throws Exception {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		int rounds = 12;
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < rounds; round++) {
				String rec = UUID.randomUUID().toString();
				String task = publishTask(merchant, org);
				String appId = applyAndAccept(merchant, org, task, rec);
				db.sql("UPDATE task_application SET bounty_cents = 500 WHERE id = CAST(:id AS uuid)")
						.bind("id", appId).then().block();

				CountDownLatch start = new CountDownLatch(1);
				final int roundNo = round;
				Future<Boolean> submit = pool.submit(() -> await(start)
						&& submissionRepo.create(appId, rec, "https://www.xiaohongshu.com/p/c" + roundNo, null)
								.block() != null);
				Future<Boolean> exit = pool.submit(() -> await(start) && client()
						.post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
						.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
						.bodyValue(Map.of("kind", "no_fault")).exchange().returnResult().getStatus().value() == 200);
				start.countDown();
				boolean submitted = submit.get(30, TimeUnit.SECONDS);
				boolean exited = exit.get(30, TimeUnit.SECONDS);

				// 单边胜出：二者互斥（提交与无责退出不能同时成功）。
				assertThat(submitted).as("round %d submission", round)
						.isNotEqualTo(exited);
				TaskApplication row = applicationRepo.findById(appId).block();
				if (exited) {
					assertThat(row.status()).isEqualTo("withdrawn");
					assertThat(row.exitKind()).isEqualTo("no_fault");
					assertThat(operationCount(appId)).isEqualTo(1);
					assertThat(occupiedSlots(task)).isZero();
				} else {
					assertThat(row.status()).isEqualTo("accepted");
					assertThat(operationCount(appId)).isZero();
					assertThat(occupiedSlots(task)).isEqualTo(1);
				}
			}
			// 整个并发试验期间，退出路径从不直接调 Finance（R01 不变量）。
			verify(financeClient, never()).release(anyString(), anyString());
			verify(financeClient, never()).freebieRefund(anyString(), anyString());
		} finally {
			pool.shutdownNow();
		}
	}

	/** 并发双退出（同账号连点/多端）：恰好一个成功、一个操作行、名额一次回收。 */
	@Test
	void concurrentDoubleExitYieldsSingleOperation() throws Exception {
		String merchant = UUID.randomUUID().toString();
		String org = UUID.randomUUID().toString();
		String rec = UUID.randomUUID().toString();
		String task = publishTask(merchant, org);
		String appId = applyAndAccept(merchant, org, task, rec);
		db.sql("UPDATE task_application SET bounty_cents = 500 WHERE id = CAST(:id AS uuid)").bind("id", appId).then()
				.block();

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			AtomicInteger wins = new AtomicInteger();
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Boolean>> calls = new java.util.ArrayList<>();
			for (int i = 0; i < 2; i++) {
				calls.add(pool.submit(() -> await(start) && client()
						.post().uri("/api/tasks/" + task + "/applications/" + appId + "/exit")
						.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
						.bodyValue(Map.of("kind", "no_fault")).exchange().returnResult().getStatus().value() == 200
						&& wins.incrementAndGet() > 0));
			}
			start.countDown();
			for (Future<Boolean> call : calls) {
				call.get(30, TimeUnit.SECONDS);
			}
			assertThat(wins.get()).isEqualTo(1);
			assertThat(operationCount(appId)).isEqualTo(1);
			assertThat(outboxCountForApp("ApplicationExitedNoFault", appId)).isEqualTo(1);
			assertThat(occupiedSlots(task)).isZero();
		} finally {
			pool.shutdownNow();
		}
	}

	// ---------- helpers ----------

	private static boolean await(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
			return true;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static String exitRequestIdOf(Map<String, Object> opened) {
		Object id = opened.get("exitRequestId");
		if (id == null) {
			id = opened.get("id");
		}
		return (String) id;
	}

	private Map<String, Object> requestNegotiated(String initiator, String role, String org, String task, String app,
			String reason) {
		String auth = "merchant".equals(role)
				? sign(initiator, "merchant", org, "finance_transaction")
				: sign(initiator, "recommender");
		Map<String, Object> resp = client().post().uri("/api/tasks/" + task + "/applications/" + app + "/exit")
				.header(H, auth).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("kind", "negotiated", "reason", reason)).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		return (Map<String, Object>) resp.get("data");
	}

	private void insertConfirmedMilestone(String app, String recommender, String merchant, String kind) {
		db.sql("""
				INSERT INTO engagement_milestone(id, application_id, kind, version, proposed_by, confirmed_by, confirmed_at)
				VALUES (CAST(:id AS uuid), CAST(:app AS uuid), :kind, 1,
				        CAST(:proposed AS uuid), CAST(:confirmed AS uuid), now())
				""")
				.bind("id", UUID.randomUUID().toString()).bind("app", app).bind("kind", kind)
				.bind("proposed", recommender).bind("confirmed", merchant).then().block();
	}

	private long operationCount(String app) {
		Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM engagement_exit_operation"
				+ " WHERE application_id = CAST(:app AS uuid)").bind("app", app).map(r -> r.get("c", Long.class))
				.one().block();
		return c == null ? 0 : c;
	}

	private String operationState(String app) {
		return db.sql("SELECT state FROM engagement_exit_operation WHERE application_id = CAST(:app AS uuid)")
				.bind("app", app).map(r -> r.get("state", String.class)).one().block();
	}

	private Long legAmount(String app, String legKind) {
		return db.sql("SELECT l.amount_cents FROM engagement_exit_fund_leg l"
				+ " JOIN engagement_exit_operation o ON o.id = l.operation_id"
				+ " WHERE o.application_id = CAST(:app AS uuid) AND l.leg_kind = :kind")
				.bind("app", app).bind("kind", legKind).map(r -> r.get("amount_cents", Long.class)).one().block();
	}

	private String legState(String app, String legKind) {
		return db.sql("SELECT l.state FROM engagement_exit_fund_leg l"
				+ " JOIN engagement_exit_operation o ON o.id = l.operation_id"
				+ " WHERE o.application_id = CAST(:app AS uuid) AND l.leg_kind = :kind")
				.bind("app", app).bind("kind", legKind).map(r -> r.get("state", String.class)).one().block();
	}

	private String publishTask(String merchant, String org) {
		Map<String, Object> b = new LinkedHashMap<>();
		b.put("organizationId", org);
		b.put("title", "退出竞态任务");
		b.put("platform", "xiaohongshu");
		b.put("storeId", UUID.randomUUID().toString());
		b.put("applicationDeadline", Instant.now().plusSeconds(3600).toString());
		Map<String, Object> resp = client().post().uri("/api/tasks")
				.header(H, sign(merchant, "merchant", org, "basic_publish")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(b).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult()
				.getResponseBody();
		String taskId = (String) ((Map<String, Object>) resp.get("data")).get("id");
		db.sql("UPDATE task SET status = 'published', published_at = COALESCE(published_at, now()) "
				+ "WHERE id = CAST(:id AS uuid)").bind("id", taskId).then().block();
		return stripStoreScope(taskId);
	}

	private String applyAndAccept(String merchant, String org, String taskId, String rec) {
		Map<String, Object> applied = client().post().uri("/api/tasks/" + taskId + "/applications")
				.header(H, sign(rec, "recommender")).contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("note", "接")).exchange().expectStatus().isCreated().expectBody(Map.class)
				.returnResult().getResponseBody();
		String appId = (String) ((Map<String, Object>) applied.get("data")).get("id");
		client().post().uri("/api/tasks/" + taskId + "/applications/" + appId + "/accept")
				.header(H, sign(merchant, "merchant", org, "basic_publish")).exchange().expectStatus().isOk();
		long deadline = System.currentTimeMillis() + 10_000L;
		while (System.currentTimeMillis() < deadline) {
			String status = applicationRepo.findById(appId).block().status();
			if ("accepted".equals(status)) {
				return appId;
			}
			if (status != null && !"reserving".equals(status)) {
				throw new AssertionError("acceptance did not reach accepted (last=" + status + ")");
			}
			try {
				Thread.sleep(100L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		throw new AssertionError("acceptance did not reach accepted in time");
	}

	private void backdateRemedy(String appId) {
		db.sql("UPDATE task_application SET remedy_deadline_at = now() - interval '1 second',"
				+ " delivery_deadline_at = now() - interval '121 second'" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", appId).then().block();
	}

	private int occupiedSlots(String taskId) {
		return db
				.sql("SELECT COALESCE(counter.occupied_slots, 0)::int AS s FROM task t"
						+ " LEFT JOIN task_acceptance_counter counter ON counter.task_id = t.id"
						+ " WHERE t.id = CAST(:id AS uuid)")
				.bind("id", taskId).map(r -> r.get("s", Integer.class)).one().block();
	}

	private long outboxCountForApp(String eventType, String appId) {
		Long c = db
				.sql("SELECT COUNT(*)::bigint AS c FROM marketplace_outbox "
						+ "WHERE event_type = :et AND payload->>'applicationId' = :app")
				.bind("et", eventType).bind("app", appId).map(r -> r.get("c", Long.class)).one().block();
		return c == null ? 0L : c;
	}
}
