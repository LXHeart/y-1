package com.grassland.trust.adjudication;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.trust.TrustItSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

/**
 * 开庭条件回归（审查修复 02 / R06，验收 TC02-07~10）。
 *
 * <p>
 * 仅 court 案件可进面板；evidence/open → voting 只能由
 * {@code DisputeCaseRepository.startAdjudication} 的 guarded 条件翻转：
 * 双方质证完毕，或冻结的质证截止已到。手动 adjudicate 是自愈/重试入口—— 未到期且未双 done 时只唤醒工作流并返回当前快照；到期、done
 * 信号、手动重试并发时至多 一个完整面板与一次 DisputeAssigned。空 deadline 存量行按 created_at+质证窗 兼容锚点，
 * 不默认立即开庭。cs_direct / merchant_rejection / final 走既有专用流程或 409。
 */
class HearingGateIT extends TrustItSupport {

	private static final String H = "X-Grassland-Identity";
	private static final int PANEL_SIZE = 7;

	@Autowired
	private com.grassland.trust.workflow.AdjudicationActivityImpl activity;

	@Autowired
	private com.grassland.trust.dispute.DisputeCaseRepository disputes;

	// ---------- TC02-07：未来截止 + 双方未 done，手动调用仍在质证 ----------

	@Test
	void manualAdjudicationDoesNotBypassFutureEvidenceDeadline() {
		String merchant = UUID.randomUUID().toString();
		seedJudges(PANEL_SIZE);
		String id = open(merchant); // court 默认通道：deadline = now + 48h（IT 基座默认窗口）

		client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
				.header(H, sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange().expectStatus()
				.isAccepted().expectBody().jsonPath("$.data.status").isEqualTo("evidence").jsonPath("$.data.workflowId")
				.isEqualTo("adjudicate-" + id);

		assertThat(panelOf(id, 1)).as("未开庭不得分配面板").isEmpty();
		assertThat(outboxCount("DisputeAssigned", id)).isZero();
	}

	// ---------- TC02-08：仅一方 done 仍等待；双方 done 或期限届满可开庭 ----------

	@Test
	void oneSideDoneStillWaitsForHearing() {
		String merchant = UUID.randomUUID().toString();
		seedJudges(PANEL_SIZE);
		String id = open(merchant);
		db.sql("UPDATE dispute_case SET claimant_done_at = now() WHERE id = CAST(:id AS uuid)").bind("id", id).then()
				.block();

		client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
				.header(H, sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange().expectStatus()
				.isAccepted().expectBody().jsonPath("$.data.status").isEqualTo("evidence");
		assertThat(panelOf(id, 1)).isEmpty();
	}

	@Test
	void bothPartiesDoneOpensCourtEarly() {
		String merchant = UUID.randomUUID().toString();
		seedJudges(PANEL_SIZE);
		String id = open(merchant);
		db.sql("UPDATE dispute_case SET claimant_done_at = now(), respondent_done_at = now()"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", id).then().block();

		client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
				.header(H, sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange().expectStatus()
				.isAccepted().expectBody().jsonPath("$.data.status").isEqualTo("voting").jsonPath("$.data.panel.size")
				.isEqualTo(PANEL_SIZE);
	}

	@Test
	void expiredDeadlineOpensCourt() {
		String merchant = UUID.randomUUID().toString();
		seedJudges(PANEL_SIZE);
		String id = open(merchant);
		backdateEvidenceDeadline(id);

		client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
				.header(H, sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange().expectStatus()
				.isAccepted().expectBody().jsonPath("$.data.status").isEqualTo("voting").jsonPath("$.data.round")
				.isEqualTo(1);
		assertThat(panelOf(id, 1)).hasSize(PANEL_SIZE);
		assertThat(outboxCount("DisputeAssigned", id)).isEqualTo(1);
	}

	// ---------- TC02-09：到期 + 手动重试并发，面板/事件只生成一次 ----------

	@Test
	void concurrentDeadlineAndManualRetriesProduceSinglePanelAndEvent() throws Exception {
		String merchant = UUID.randomUUID().toString();
		seedJudges(PANEL_SIZE);
		String id = open(merchant);
		backdateEvidenceDeadline(id);

		// 三路并发：两个 HTTP 手动重试 + 一个工作流 activity 路径（同一共用实现）。
		java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
		java.util.List<java.util.concurrent.Callable<Integer>> calls = List.of(() -> postAdjudicateStatus(merchant, id),
				() -> postAdjudicateStatus(merchant, id),
				() -> activity.assignPanelReactive(id, 1).thenReturn(200).block());
		List<Integer> statuses;
		try {
			statuses = pool.invokeAll(calls).stream().map(f -> {
				try {
					return f.get();
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			}).toList();
		} finally {
			pool.shutdownNow();
		}

		assertThat(statuses).as("并发输家经幂等分支收敛，不互相打崩").allMatch(s -> s == 200 || s == 202);
		assertThat(statusOf(id)).isEqualTo("voting");
		assertThat(panelOf(id, 1)).as("至多一个完整面板").hasSize(PANEL_SIZE);
		assertThat(outboxCount("DisputeAssigned", id)).as("至多一次开庭事件").isEqualTo(1);
	}

	// ---------- TC02-10：兼容分支（存量空 deadline / 专用通道 / 终局） ----------

	@Test
	void legacyNullDeadlineFreshRowStillWaitsForCompatWindow() {
		String merchant = UUID.randomUUID().toString();
		seedJudges(PANEL_SIZE);
		String id = db.sql("""
				INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id,
				                         opened_by_role, status, reason, channel)
				VALUES (CAST(:id AS uuid), :ref, CAST(:org AS uuid), CAST(:by AS uuid),
				        'recommender', 'evidence', 'legacy no deadline', 'court')
				RETURNING id::text
				""").bind("id", UUID.randomUUID().toString()).bind("ref", UUID.randomUUID().toString())
				.bind("org", MARKETPLACE_ORG).bind("by", merchant).map(r -> r.get(0, String.class)).one().block();

		client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
				.header(H, sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange().expectStatus()
				.isAccepted().expectBody().jsonPath("$.data.status").isEqualTo("evidence");
		assertThat(panelOf(id, 1)).as("空 deadline 不默认立即开庭（锚点 created_at+窗口在未来）").isEmpty();
	}

	@Test
	void legacyNullDeadlineLongExpiredRowOpens() {
		String merchant = UUID.randomUUID().toString();
		seedJudges(PANEL_SIZE);
		String id = db.sql("""
				INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id,
				                         opened_by_role, status, reason, channel, created_at)
				VALUES (CAST(:id AS uuid), :ref, CAST(:org AS uuid), CAST(:by AS uuid),
				        'recommender', 'evidence', 'legacy overdue', 'court', now() - interval '49 hours')
				RETURNING id::text
				""").bind("id", UUID.randomUUID().toString()).bind("ref", UUID.randomUUID().toString())
				.bind("org", MARKETPLACE_ORG).bind("by", merchant).map(r -> r.get(0, String.class)).one().block();

		client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
				.header(H, sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange().expectStatus()
				.isAccepted().expectBody().jsonPath("$.data.status").isEqualTo("voting");
		assertThat(panelOf(id, 1)).hasSize(PANEL_SIZE);
	}

	/** merchant_rejection 恒 open、直送客服终审（既有专用流程不被本修复改变）。 */
	@Test
	void merchantRejectionCannotAdjudicate() {
		String merchant = UUID.randomUUID().toString();
		String id = db.sql("""
				INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id,
				                         opened_by_role, status, reason, kind)
				VALUES (CAST(:id AS uuid), :ref, CAST(:org AS uuid), CAST(:by AS uuid),
				        'merchant', 'open', 'rejection', 'merchant_rejection')
				RETURNING id::text
				""").bind("id", UUID.randomUUID().toString()).bind("ref", UUID.randomUUID().toString())
				.bind("org", MARKETPLACE_ORG).bind("by", merchant).map(r -> r.get(0, String.class)).one().block();

		client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
				.header(H, sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange().expectStatus()
				.isEqualTo(409);
		assertThat(statusOf(id)).isEqualTo("open");
	}

	/** 已 voting 的手动自愈幂等（补面板不重复、事件不重发）。 */
	@Test
	void alreadyVotingSelfHealIsIdempotent() {
		String merchant = UUID.randomUUID().toString();
		seedJudges(PANEL_SIZE);
		String id = open(merchant);
		backdateEvidenceDeadline(id);
		client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
				.header(H, sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange().expectStatus()
				.isAccepted(); // 202 首开
		adjudicate(merchant, id); // 200 幂等自愈

		assertThat(statusOf(id)).isEqualTo("voting");
		assertThat(panelOf(id, 1)).hasSize(PANEL_SIZE);
		assertThat(outboxCount("DisputeAssigned", id)).isEqualTo(1);
	}

	/** repo 直调（唯一转换）在未满足条件时 0 行返回——服务端口径与 SQL 守卫一致；到期后放行。 */
	@Test
	void repositoryTransitionIsGuardedByHearingConditions() {
		String merchant = UUID.randomUUID().toString();
		String id = open(merchant);
		// 未来截止 + 双方未 done → 0 行
		assertThat(disputes.startAdjudication(id, 1, 48 * 3600L).block()).isNull();
		assertThat(statusOf(id)).isEqualTo("evidence");
		// 到期 → 放行；再次调用（已 voting）幂等 0 行
		backdateEvidenceDeadline(id);
		assertThat(disputes.startAdjudication(id, 1, 48 * 3600L).block().status()).isEqualTo("voting");
		assertThat(disputes.startAdjudication(id, 1, 48 * 3600L).block()).isNull();
	}

	// ---------- helpers ----------

	private int postAdjudicateStatus(String merchant, String id) {
		return client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
				.header(H, sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange().expectStatus()
				.is2xxSuccessful().returnResult(String.class).getStatus().value();
	}

	private void adjudicate(String merchant, String id) {
		client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
				.header(H, sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange().expectStatus()
				.isOk();
	}

	@SuppressWarnings("unchecked")
	private String open(String merchant) {
		Map<String, Object> resp = client().post().uri("/api/trust/disputes")
				.header(H, sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("engagementRef", UUID.randomUUID().toString())).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		return (String) ((Map<String, Object>) resp.get("data")).get("id");
	}

	private void seedJudges(int count) {
		for (int i = 0; i < count; i++) {
			db.sql("INSERT INTO judge(id, account_id, organization_id, eligibility_tier, active,"
					+ " ops_admitted, ops_admitted_at, ops_admitted_by, admission_level)"
					+ " VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), NULL, 5, true, true, now(),"
					+ " CAST(:actor AS uuid), 'full')").bind("id", UUID.randomUUID().toString())
					.bind("acct", UUID.randomUUID().toString()).bind("actor", UUID.randomUUID().toString()).then()
					.block();
		}
	}

	private List<String> panelOf(String disputeId, int round) {
		return db
				.sql("SELECT judge_account_id::text AS a FROM dispute_panel_assignment"
						+ " WHERE dispute_id = CAST(:id AS uuid) AND round = :round")
				.bind("id", disputeId).bind("round", round).map(r -> r.get("a", String.class)).all().collectList()
				.block();
	}

	private String statusOf(String disputeId) {
		return db.sql("SELECT status FROM dispute_case WHERE id = CAST(:id AS uuid)").bind("id", disputeId)
				.map(r -> r.get("status", String.class)).one().block();
	}

	private long outboxCount(String eventType, String disputeId) {
		return db
				.sql("SELECT COUNT(*)::int AS c FROM trust_outbox"
						+ " WHERE event_type = :et AND payload->>'disputeId' = :id")
				.bind("et", eventType).bind("id", disputeId).map(r -> r.get("c", Integer.class)).one().block()
				.longValue();
	}
}
