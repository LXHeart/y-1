package com.grassland.trust.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;

import com.grassland.trust.TrustItSupport;
import com.grassland.trust.dispute.DisputeCaseRepository;
import com.grassland.messaging.EventEnvelope;
import com.grassland.messaging.outbox.OutboxRepository;
import com.grassland.trust.judge.JudgeRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import reactor.core.publisher.Mono;

/**
 * Slice 7C-2：证明 trust 写活动（{@link AdjudicationActivityImpl}）的「领域写 + outbox
 * append」在同一 R2DBC 事务。
 *
 * <p>
 * 直接调 activity bean（不经 workflow）：{@code @MockitoSpyBean} 把
 * {@link OutboxRepository#append} 针对目标 事件类型注入失败，断言领域写（状态迁移 / 面板分配）随之回滚。覆盖
 * assignPanel / recordDecision / escalate。
 *
 * <p>
 * {@code assignPanel} 将状态迁移、条件面板分配和 outbox 纳入同一事务；任一步失败都不得留下 voting 状态或部分面板。
 */
class ActivityOutboxAtomicityIT extends TrustItSupport {

	@MockitoSpyBean
	OutboxRepository outbox;

	@Autowired
	AdjudicationActivityImpl adjudicationActivity;

	@Autowired
	DisputeCaseRepository disputes;

	@Autowired
	JudgeRepository judges;

	@Test
	void assignPanelRollsBackWhenOutboxFails() {
		String merchant = UUID.randomUUID().toString();
		String org = MARKETPLACE_ORG;
		seedJudges(7);
		String id = open(merchant, org); // open

		failOutboxOn("DisputeAssigned");
		assertThatThrownBy(() -> adjudicationActivity.assignPanel(id, 1)).isInstanceOf(RuntimeException.class);
		assertThat(statusOf(id)).isEqualTo("open"); // open→voting 回滚
		assertThat(panelJudges(id, 1)).isEmpty(); // 面板分配 INSERT 回滚
	}

	@Test
	void recordDecisionRollsBackWhenOutboxFails() {
		String merchant = UUID.randomUUID().toString();
		String org = MARKETPLACE_ORG;
		String id = open(merchant, org);
		disputes.startAdjudication(id, 1).block(); // open→voting

		failOutboxOn("DisputeDecided");
		assertThatThrownBy(() -> adjudicationActivity.recordDecision(id, "for_merchant"))
				.isInstanceOf(RuntimeException.class);
		assertThat(statusOf(id)).isEqualTo("voting"); // voting→decided 回滚
	}

	@Test
	void closeVotingRoundRollsBackDecisionWhenOutboxFails() {
		String merchant = UUID.randomUUID().toString();
		String id = open(merchant, MARKETPLACE_ORG);
		String judge = seedJudge();
		disputes.startAdjudication(id, 1).block();
		judges.assignPanel(id, 1, List.of(judge)).block();
		judges.recordVote(id, 1, judge, "for_merchant", null).block();

		failOutboxOn("DisputeDecided");
		assertThatThrownBy(() -> adjudicationActivity.closeVotingRound(id, 1, false))
				.isInstanceOf(RuntimeException.class);

		assertThat(statusOf(id)).isEqualTo("voting");
		assertThat(disputes.findById(id).block().decision()).isNull();
	}

	@Test
	void escalateRollsBackWhenOutboxFails() {
		String merchant = UUID.randomUUID().toString();
		String org = MARKETPLACE_ORG;
		String id = open(merchant, org);
		disputes.startAdjudication(id, 1).block(); // voting

		failOutboxOn("AdjudicationEscalated");
		assertThatThrownBy(() -> adjudicationActivity.escalate(id)).isInstanceOf(RuntimeException.class);
		assertThat(appealStateOf(id)).isNotEqualTo("escalated"); // markEscalated 回滚
	}

	private void failOutboxOn(String eventType) {
		doReturn(Mono.<Void>error(new RuntimeException("outbox injected failure"))).when(outbox)
				.append(argThat((EventEnvelope e) -> e != null && eventType.equals(e.eventType())));
	}

	private void seedJudges(int count) {
		for (int i = 0; i < count; i++) {
			seedJudge();
		}
	}

	private String seedJudge() {
		String accountId = UUID.randomUUID().toString();
		db.sql("INSERT INTO judge(id, account_id, organization_id, eligibility_tier, active,"
				+ " ops_admitted, ops_admitted_at, ops_admitted_by)"
				+ " VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), NULL, 5, true, true, now(), CAST(:actor AS uuid))")
				.bind("id", UUID.randomUUID().toString()).bind("acct", accountId)
				.bind("actor", UUID.randomUUID().toString()).then().block();
		return accountId;
	}

	@SuppressWarnings("unchecked")
	private String open(String merchant, String org) {
		Map<String, Object> resp = client().post().uri("/api/trust/disputes")
				.header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
				.contentType(MediaType.APPLICATION_JSON)
				.bodyValue(Map.of("engagementRef", UUID.randomUUID().toString())).exchange().expectStatus().isCreated()
				.expectBody(Map.class).returnResult().getResponseBody();
		return (String) ((Map<String, Object>) resp.get("data")).get("id");
	}

	private List<String> panelJudges(String disputeId, int round) {
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

	private String appealStateOf(String disputeId) {
		return db.sql("SELECT appeal_state FROM dispute_case WHERE id = CAST(:id AS uuid)").bind("id", disputeId)
				.map(r -> r.get("appeal_state", String.class)).one().block();
	}
}
