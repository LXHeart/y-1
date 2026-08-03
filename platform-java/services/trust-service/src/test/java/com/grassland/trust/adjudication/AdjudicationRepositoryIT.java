package com.grassland.trust.adjudication;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.trust.TrustItSupport;
import com.grassland.trust.dispute.DisputeCase;
import com.grassland.trust.dispute.DisputeCaseRepository;
import com.grassland.trust.judge.Judge;
import com.grassland.trust.judge.JudgeRepository;
import com.grassland.trust.judge.VoteTally;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 审判数据层（草场 Epic 6 Slice 6C Phase B）。继承 {@link TrustItSupport}，直接注 {@link DisputeCaseRepository} /
 * {@link JudgeRepository}，覆盖端点脱敏后不可见、且 Phase C workflow 才会调的：
 * <ul>
 *   <li>审判官池抽签——同组织 / 显式 {@code judge_conflict} / 低 tier 排除。</li>
 *   <li>状态机 5 态迁移 + 守卫（startAdjudication/reopen/recordDecision/markAppealed/finalize）。</li>
 *   <li>投票幂等 + 计票（assignPanel/recordVote/tallyVotes）。</li>
 * </ul>
 */
class AdjudicationRepositoryIT extends TrustItSupport {

    @Autowired
    private DisputeCaseRepository disputes;

    @Autowired
    private JudgeRepository judges;

    // ---------- 审判官池抽签（冲突 / tier 排除）----------

    @Test
    void drawEligiblePoolExcludesSameOrgAndDeclaredConflictsAndLowTier() {
        // 共享 testcontainer 跨测试累积 judge 行——本例需确定性池，先清空。
        db.sql("TRUNCATE judge_conflict").then().block();
        db.sql("TRUNCATE judge CASCADE").then().block();
        String disputeOrg = UUID.randomUUID().toString();
        String otherOrg = UUID.randomUUID().toString();

        String platform = seedJudge(UUID.randomUUID().toString(), null, 1);          // 入选（平台级）
        String otherOrgJudge = seedJudge(UUID.randomUUID().toString(), otherOrg, 1); // 入选（他组织）
        String sameOrg = seedJudge(UUID.randomUUID().toString(), disputeOrg, 1);     // 排除（同组织）
        String conflicted = seedJudge(UUID.randomUUID().toString(), null, 1);        // 排除（显式冲突）
        seedConflict(conflicted, disputeOrg);
        String lowTier = seedJudge(UUID.randomUUID().toString(), null, 0);           // 排除（tier<1）

        List<String> drawn = judges.drawEligiblePool(1, disputeOrg, 10).map(Judge::accountId).collectList().block();

        assertThat(drawn).containsExactlyInAnyOrder(platform, otherOrgJudge);
        assertThat(drawn).doesNotContain(sameOrg, conflicted, lowTier);
    }

    @Test
    void assignPanelIsIdempotent() {
        String org = UUID.randomUUID().toString();
        String disputeId = openDispute(org);
        String a = seedJudge(UUID.randomUUID().toString(), null, 1);
        String b = seedJudge(UUID.randomUUID().toString(), null, 1);

        assertThat(judges.assignPanel(disputeId, 1, List.of(a, b)).block()).isEqualTo(2);
        assertThat(judges.assignPanel(disputeId, 1, List.of(a, b)).block()).isEqualTo(0);  // 已存在
        assertThat(judges.countPanel(disputeId, 1).block()).isEqualTo(2);
        assertThat(judges.isPanelMember(disputeId, 1, a).block()).isTrue();
        assertThat(judges.isPanelMember(disputeId, 1, UUID.randomUUID().toString()).block()).isFalse();
    }

    @Test
    void recordVoteAndTally() {
        String org = UUID.randomUUID().toString();
        String disputeId = openDispute(org);
        String a = seedJudge(UUID.randomUUID().toString(), null, 1);
        String b = seedJudge(UUID.randomUUID().toString(), null, 1);
        String c = seedJudge(UUID.randomUUID().toString(), null, 1);
        judges.assignPanel(disputeId, 1, List.of(a, b, c)).block();

        assertThat(judges.recordVote(disputeId, 1, a, "for_merchant", null).block().vote()).isEqualTo("for_merchant");
        assertThat(judges.recordVote(disputeId, 1, a, "for_recommender", null).block()).isNull();  // 幂等：既有不覆盖
        assertThat(judges.recordVote(disputeId, 1, b, "for_recommender", "r").block().vote()).isEqualTo("for_recommender");

        VoteTally tally = judges.tallyVotes(disputeId, 1).block();
        assertThat(tally.forMerchant()).isEqualTo(1);
        assertThat(tally.forRecommender()).isEqualTo(1);
        assertThat(tally.panelSize()).isEqualTo(3);
        assertThat(tally.hasMajority()).isFalse();  // 1-of-3、1-of-3，无人过半
    }

    // ---------- 状态机 5 态迁移 + 守卫 ----------

    @Test
    void stateMachineOpenToFinalViaAdjudication() {
        String org = UUID.randomUUID().toString();
        String id = openDispute(org);
        long v0 = disputes.findById(id).block().version();

        DisputeCase voting = disputes.startAdjudication(id, 1).block();   // open→voting round 1
        assertThat(voting.status()).isEqualTo("voting");
        assertThat(voting.round()).isEqualTo(1);
        assertThat(voting.version()).isEqualTo(v0 + 1);

        DisputeCase reopened = disputes.reopen(id, 2).block();             // 平票重开 round 2
        assertThat(reopened.round()).isEqualTo(2);
        assertThat(reopened.version()).isEqualTo(v0 + 2);

        DisputeCase decided = disputes.recordDecision(id, "for_merchant").block();  // voting→decided
        assertThat(decided.status()).isEqualTo("decided");
        assertThat(decided.decision()).isEqualTo("for_merchant");

        DisputeCase appealed = disputes.markAppealed(id).block();          // decided→appealed
        assertThat(appealed.status()).isEqualTo("appealed");
        assertThat(appealed.appealState()).isEqualTo("filed");

        String csAgent = UUID.randomUUID().toString();
        DisputeCase finalized = disputes.finalize(id, "for_recommender", csAgent).block();  // →final
        assertThat(finalized.status()).isEqualTo("final");
        assertThat(finalized.finalDecision()).isEqualTo("for_recommender");
        assertThat(finalized.finalDecidedBy()).isEqualTo(csAgent);

        // 终局后活跃争议槽释放：可再开新争议（同 engagement）
        assertThat(disputes.findActiveByEngagementRef(voting.engagementRef()).block()).isNull();
    }

    @Test
    void startAdjudicationGuardsOnOpen() {
        String org = UUID.randomUUID().toString();
        String id = openDispute(org);
        disputes.startAdjudication(id, 1).block();  // →voting
        // 再次 start（非 open）→ empty
        assertThat(disputes.startAdjudication(id, 1).block()).isNull();
    }

    @Test
    void finalizeGuardsOnDecidedOrAppealed() {
        String org = UUID.randomUUID().toString();
        String id = openDispute(org);
        // 未到 decided/appealed → finalize empty
        assertThat(disputes.finalize(id, "for_merchant", UUID.randomUUID().toString()).block()).isNull();
        disputes.startAdjudication(id, 1).block();
        // 仍 voting（未 decided）→ finalize empty
        assertThat(disputes.finalize(id, "for_merchant", UUID.randomUUID().toString()).block()).isNull();
    }

    // ---------- helpers ----------

    private String openDispute(String org) {
        return disputes.create("eng-" + UUID.randomUUID(), org, UUID.randomUUID().toString(), "merchant", "未履约", "standard")
                .block().id();
    }

    private String seedJudge(String accountId, String orgId, int tier) {
        var spec = db.sql("INSERT INTO judge(id, account_id, organization_id, eligibility_tier, active)"
                + " VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), CAST(:org AS uuid), :tier, true)")
                .bind("id", UUID.randomUUID().toString()).bind("acct", accountId).bind("tier", tier);
        spec = (orgId == null) ? spec.bindNull("org", String.class) : spec.bind("org", orgId);
        spec.then().block();
        return accountId;
    }

    private void seedConflict(String judgeAccountId, String orgId) {
        String judgeId = db.sql("SELECT id::text AS i FROM judge WHERE account_id = CAST(:acct AS uuid)")
                .bind("acct", judgeAccountId).map(r -> r.get("i", String.class)).one().block();
        db.sql("INSERT INTO judge_conflict(judge_id, organization_id) VALUES (CAST(:j AS uuid), CAST(:o AS uuid))")
                .bind("j", judgeId).bind("o", orgId).then().block();
    }
}
