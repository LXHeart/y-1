package com.grassland.trust.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.grassland.trust.TrustItSupport;
import com.grassland.trust.workflow.AdjudicationActivityImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

/**
 * 当事人回避回归（审查修复 02 / R04，验收 TC02-01~04）。
 *
 * <p>
 * 原告 openedByAccountId 与被告 respondentAccountId 即使具备合格审判官资格，也不得进入本案面板； 初审（工作流
 * activity）、手动开庭、补席自愈、发回重审共用同一冲突判定 （{@link DisputeConflictContextResolver}
 * 统一上下文）；服务层抽签排除与数据库触发器（V17）双保险。 存量空被告：按履约授权接口补解析回填，解析失败保持可解释待处理，不把 null
 * 当「无冲突」放行。
 */
class PanelRecusalIT extends TrustItSupport {

	private static final String H = "X-Grassland-Identity";

	@Autowired
	private JudgeRepository judges;

	@Autowired
	private AdjudicationActivityImpl activity;

	// ---------- TC02-01：原告/被告均为合格审判官，均不能取得本案席位 ----------

	@Test
	void claimantAndRespondentCannotJudgeTheirOwnDispute() {
		String claimant = seedJudge(); // 合格 Lv5 + 运营准入
		String respondent = seedJudge(); // 同为合格审判官
		seedJudges(7);
		String dispute = insertDispute(claimant, respondent, true);

		activity.assignPanel(dispute, 1);

		List<String> panel = panelOf(dispute, 1);
		assertThat(panel).hasSize(7);
		assertThat(panel).as("原告与被告都不得担任本案审判官").doesNotContain(claimant, respondent);
		assertThat(statusOf(dispute)).isEqualTo("voting");
	}

	/** 手动 HTTP 入口与工作流共用同一回避（TC02-04 的手动路径面）。 */
	@Test
	void manualAdjudicationPathAppliesSameRecusal() {
		String claimant = seedJudge();
		seedJudges(7);
		String respondent = UUID.randomUUID().toString();
		String dispute = insertDispute(claimant, respondent, true);

		client().post().uri("/api/trust/disputes/" + dispute + "/adjudicate")
				.header(H, sign(claimant, "merchant", MARKETPLACE_ORG, "basic_publish")).exchange().expectStatus()
				.isAccepted().expectBody().jsonPath("$.data.status").isEqualTo("voting");

		assertThat(panelOf(dispute, 1)).doesNotContain(claimant);
	}

	/** 残缺面板补席（自愈）同样回避当事人：已入席的无冲突成员保留，补位不引入原告。 */
	@Test
	void panelRefillSkipsParties() {
		String claimant = seedJudge();
		seedJudges(8);
		String respondent = UUID.randomUUID().toString();
		String dispute = insertDispute(claimant, respondent, true);

		activity.assignPanel(dispute, 1);
		List<String> round1 = panelOf(dispute, 1);
		assertThat(round1).doesNotContain(claimant);
		// 模拟残缺：移走一名，手动自愈补位后仍不含原告，且面板恢复满员。
		// 共享容器候选池含其他测试种子，补位来源不限定本用例账号（回避断言与人数才是语义）。
		db.sql("DELETE FROM dispute_panel_assignment WHERE ctid = (SELECT ctid FROM dispute_panel_assignment"
				+ " WHERE dispute_id = CAST(:d AS uuid) AND round = 1 LIMIT 1)").bind("d", dispute).then().block();
		activity.assignPanel(dispute, 1);
		List<String> refilled = panelOf(dispute, 1);
		assertThat(refilled).hasSize(7);
		assertThat(refilled).doesNotContain(claimant);
		assertThat(refilled).doesNotContain(respondent);
	}

	// ---------- TC02-02：排除当事人后人数不足 → 可重试待处理，无半面板/错误 outbox ----------

	@Test
	void exclusionShortageLeavesNoPartialPanelOrOutbox() {
		clearJudges();
		String claimant = seedJudge();
		seedJudges(6); // 共 7 名合格候选，其中 1 人是原告 → 排除后仅 6，不足 7 席
		String dispute = insertDispute(claimant, UUID.randomUUID().toString(), true);

		assertThatThrownBy(() -> activity.assignPanel(dispute, 1)).as("排除当事人后人数不足应失败而非提交半个面板")
				.isInstanceOf(com.grassland.trust.security.TrustException.class);
		assertThat(panelOf(dispute, 1)).isEmpty();
		assertThat(statusOf(dispute)).isEqualTo("evidence"); // 可重试的待处理状态
		assertThat(outboxCount("DisputeAssigned", dispute)).isZero();
	}

	// ---------- TC02-03：既有排除规则（同组织/显式冲突/降级/挂起/撤销准入）不被本修复削弱 ----------

	@Test
	void existingExclusionRulesStillApply() {
		String disputeOrg = UUID.randomUUID().toString();
		String claimant = seedJudge();
		seedJudges(7); // 干净候选
		// 五类应被排除的候选（各自合格维度之外的问题）
		String sameOrg = seedJudge(disputeOrg);
		String conflicted = seedJudge();
		db.sql("INSERT INTO judge_conflict(judge_id, organization_id)"
				+ " SELECT id, CAST(:org AS uuid) FROM judge WHERE account_id = CAST(:a AS uuid)")
				.bind("org", disputeOrg).bind("a", conflicted).then().block();
		String downgraded = seedJudge();
		when(reputationClient.getLevel(downgraded))
				.thenReturn(Mono.just(new MarketplaceReputationClient.LevelResult(downgraded, "Lv4", 4, false, 9L)));
		String suspended = seedJudge();
		db.sql("UPDATE judge SET suspended_until = now() + interval '1 hour' WHERE account_id = CAST(:a AS uuid)")
				.bind("a", suspended).then().block();
		String revoked = seedJudge();
		db.sql("UPDATE judge SET ops_admitted = false, ops_admitted_at = NULL, ops_admitted_by = NULL"
				+ " WHERE account_id = CAST(:a AS uuid)").bind("a", revoked).then().block();

		String dispute = db.sql("""
				INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id,
				                         opened_by_role, status, reason, respondent_account_id, evidence_deadline)
				VALUES (CAST(:id AS uuid), :ref, CAST(:org AS uuid), CAST(:claimant AS uuid),
				        'recommender', 'evidence', 'recusal matrix', NULL, now() - interval '1 second')
				RETURNING id::text
				""").bind("id", UUID.randomUUID().toString()).bind("ref", UUID.randomUUID().toString())
				.bind("org", disputeOrg).bind("claimant", claimant).map(r -> r.get(0, String.class)).one().block();

		activity.assignPanel(dispute, 1);

		List<String> panel = panelOf(dispute, 1);
		assertThat(panel).hasSize(7);
		assertThat(panel).doesNotContain(claimant, sameOrg, conflicted, downgraded, suspended, revoked);
	}

	// ---------- TC02-04：数据库纵深——直插冲突席位被触发器拒绝；重审同样回避 ----------

	@Test
	void directDatabaseInsertOfPartySeatIsRejected() {
		String claimant = seedJudge();
		seedJudges(2);
		String respondent = seedJudge();
		String dispute = insertDispute(claimant, respondent, true);

		assertThatThrownBy(() -> db
				.sql("INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id)"
						+ " VALUES (CAST(:d AS uuid), 1, CAST(:a AS uuid))")
				.bind("d", dispute).bind("a", claimant).then().block())
				.hasStackTraceContaining("judge is not eligible");
		assertThatThrownBy(() -> db
				.sql("INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id)"
						+ " VALUES (CAST(:d AS uuid), 1, CAST(:a AS uuid))")
				.bind("d", dispute).bind("a", respondent).then().block())
				.hasStackTraceContaining("judge is not eligible");
		assertThat(panelOf(dispute, 1)).isEmpty();
	}

	@Test
	void retrialRedrawExcludesPartiesAndPreviousPanel() {
		String claimant = seedJudge(); // 合格审判官同时是本案原告
		seedJudges(15); // 15 名无冲突候选（两轮 7+7 + 余量）
		String dispute = insertDispute(claimant, UUID.randomUUID().toString(), true);

		activity.assignPanel(dispute, 1);
		List<String> round1 = panelOf(dispute, 1);
		assertThat(round1).hasSize(7).doesNotContain(claimant);

		db.sql("UPDATE dispute_case SET status = 'decided', decision = 'for_merchant', decided_at = now()"
				+ " WHERE id = CAST(:id AS uuid)").bind("id", dispute).then().block();
		db.sql("UPDATE dispute_case SET status = 'appealed', appeal_state = 'filed'" + " WHERE id = CAST(:id AS uuid)")
				.bind("id", dispute).then().block();
		db.sql("INSERT INTO dispute_appeal(dispute_id, appealed_by, status)"
				+ " VALUES (CAST(:d AS uuid), CAST(:by AS uuid), 'filed')").bind("d", dispute).bind("by", claimant)
				.then().block();

		client().post().uri("/api/trust/disputes/" + dispute + "/final-decision").header(H, signCs())
				.contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("action", "retrial")).exchange()
				.expectStatus().isOk().expectBody().jsonPath("$.data.status").isEqualTo("voting")
				.jsonPath("$.data.round").isEqualTo(2);

		List<String> round2 = panelOf(dispute, 2);
		assertThat(round2).hasSize(7);
		assertThat(round2).as("重审面板回避当事人与历轮成员").doesNotContain(claimant);
		assertThat(round2).doesNotContainAnyElementsOf(round1);
	}

	// ---------- 存量空被告（C02-A）：按履约授权补解析并回避；失败保持待处理 ----------

	@Test
	void nullRespondentIsResolvedBackfilledAndExcluded() {
		String claimant = seedJudge();
		seedJudges(7);
		String respondent = UUID.randomUUID().toString();
		// 商家开案的存量空被告行（respondent_account_id IS NULL）。
		String dispute = db.sql("""
				INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id,
				                         opened_by_role, status, reason, evidence_deadline)
				VALUES (CAST(:id AS uuid), :ref, CAST(:org AS uuid), CAST(:claimant AS uuid),
				        'merchant', 'evidence', 'legacy null respondent', now() - interval '1 second')
				RETURNING id::text
				""").bind("id", UUID.randomUUID().toString()).bind("ref", UUID.randomUUID().toString())
				.bind("org", MARKETPLACE_ORG).bind("claimant", claimant).map(r -> r.get(0, String.class)).one().block();

		when(authorizer.authorize(anyString(), anyString(), anyString())).thenReturn(
				Mono.just(new com.grassland.trust.dispute.MarketplaceEngagementAuthorizationClient.Authorization(
						dispute, MARKETPLACE_ORG, respondent, false)));

		activity.assignPanel(dispute, 1);

		assertThat(respondentOf(dispute)).as("补解析的被告账号回填落库").isEqualTo(respondent);
		List<String> panel = panelOf(dispute, 1);
		assertThat(panel).hasSize(7).doesNotContain(claimant, respondent);
	}

	@Test
	void nullRespondentResolutionFailureKeepsExplainablePendingState() {
		String claimant = seedJudge();
		seedJudges(7);
		String dispute = db.sql("""
				INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id,
				                         opened_by_role, status, reason, evidence_deadline)
				VALUES (CAST(:id AS uuid), :ref, CAST(:org AS uuid), CAST(:claimant AS uuid),
				        'merchant', 'evidence', 'legacy null respondent', now() - interval '1 second')
				RETURNING id::text
				""").bind("id", UUID.randomUUID().toString()).bind("ref", UUID.randomUUID().toString())
				.bind("org", MARKETPLACE_ORG).bind("claimant", claimant).map(r -> r.get(0, String.class)).one().block();

		when(authorizer.authorize(anyString(), anyString(), anyString())).thenReturn(Mono.empty());

		assertThatThrownBy(() -> activity.assignPanel(dispute, 1))
				.isInstanceOf(com.grassland.trust.security.TrustException.class).hasMessageContaining("被诉方账号未能解析");
		assertThat(panelOf(dispute, 1)).isEmpty();
		assertThat(statusOf(dispute)).isEqualTo("evidence");
	}

	/** 推荐官开案的空被告行：被诉方=商家组织（组织级冲突），无需账号补解析，正常开庭。 */
	@Test
	void recommenderOpenedNullRespondentUsesOrgLevelConflict() {
		String claimant = seedJudge(); // 推荐官原告（合格审判官）
		seedJudges(7);
		String dispute = db.sql("""
				INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id,
				                         opened_by_role, status, reason, evidence_deadline)
				VALUES (CAST(:id AS uuid), :ref, CAST(:org AS uuid), CAST(:claimant AS uuid),
				        'recommender', 'evidence', 'rec opened', now() - interval '1 second')
				RETURNING id::text
				""").bind("id", UUID.randomUUID().toString()).bind("ref", UUID.randomUUID().toString())
				.bind("org", MARKETPLACE_ORG).bind("claimant", claimant).map(r -> r.get(0, String.class)).one().block();

		activity.assignPanel(dispute, 1);

		assertThat(panelOf(dispute, 1)).hasSize(7).doesNotContain(claimant);
		assertThat(statusOf(dispute)).isEqualTo("voting");
	}

	// ---------- helpers ----------

	private String insertDispute(String claimant, String respondent, boolean expired) {
		return db.sql("""
				INSERT INTO dispute_case(id, engagement_ref, organization_id, opened_by_account_id,
				                         opened_by_role, status, reason, respondent_account_id, evidence_deadline)
				VALUES (CAST(:id AS uuid), :ref, CAST(:org AS uuid), CAST(:claimant AS uuid),
				        'merchant', 'evidence', 'recusal', CAST(:respondent AS uuid),
				        now() %s)
				RETURNING id::text
				""".formatted(expired ? "- interval '1 second'" : "+ interval '48 hours'"))
				.bind("id", UUID.randomUUID().toString()).bind("ref", UUID.randomUUID().toString())
				.bind("org", MARKETPLACE_ORG).bind("claimant", claimant).bind("respondent", respondent)
				.map(r -> r.get(0, String.class)).one().block();
	}

	private String seedJudge() {
		return seedJudge(null);
	}

	private String seedJudge(String organizationId) {
		String account = UUID.randomUUID().toString();
		var spec = db
				.sql("INSERT INTO judge(id, account_id, organization_id, eligibility_tier, active,"
						+ " ops_admitted, ops_admitted_at, ops_admitted_by, admission_level)"
						+ " VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), CAST(:org AS uuid), 5, true, true,"
						+ " now(), CAST(:actor AS uuid), 'full')")
				.bind("id", UUID.randomUUID().toString()).bind("acct", account)
				.bind("actor", UUID.randomUUID().toString());
		spec = organizationId == null ? spec.bindNull("org", String.class) : spec.bind("org", organizationId);
		spec.then().block();
		return account;
	}

	private List<String> seedJudges(int count) {
		List<String> accounts = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			accounts.add(seedJudge(null));
		}
		return accounts;
	}

	private void clearJudges() {
		db.sql("TRUNCATE judge_conflict").then().block();
		db.sql("TRUNCATE judge CASCADE").then().block();
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

	private String respondentOf(String disputeId) {
		return db.sql("SELECT respondent_account_id::text AS r FROM dispute_case WHERE id = CAST(:id AS uuid)")
				.bind("id", disputeId).map(r -> r.get("r", String.class)).one().block();
	}

	private long outboxCount(String eventType, String disputeId) {
		return db
				.sql("SELECT COUNT(*)::int AS c FROM trust_outbox"
						+ " WHERE event_type = :et AND payload->>'disputeId' = :id")
				.bind("et", eventType).bind("id", disputeId).map(r -> r.get("c", Integer.class)).one().block()
				.longValue();
	}

	private String signCs() {
		java.time.Instant now = java.time.Instant.now();
		return com.grassland.identity.assertion.TestAssertionHelper.userSigner("edge-bff", "grassland-trust")
				.sign(new com.grassland.identity.assertion.IdentityAssertion(UUID.randomUUID().toString(), null,
						"sid-cs", null, null, "cookie-session", "level2", now, "r", "t", "grassland-trust", now,
						now.plusSeconds(60), null, null, "customer_service"));
	}
}
