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

        // 任务书 #74 卡 E（V14 资格谓词）：可抽地板=Lv5（或 Lv4+考试）——合格样本用 Lv5，低阶样本用 Lv3。
        String platform = seedJudge(UUID.randomUUID().toString(), null, 5);          // 入选（平台级）
        String otherOrgJudge = seedJudge(UUID.randomUUID().toString(), otherOrg, 5); // 入选（他组织）
        String sameOrg = seedJudge(UUID.randomUUID().toString(), disputeOrg, 5);     // 排除（同组织）
        String conflicted = seedJudge(UUID.randomUUID().toString(), null, 5);        // 排除（显式冲突）
        seedConflict(conflicted, disputeOrg);
        String lowTier = seedJudge(UUID.randomUUID().toString(), null, 3);           // 排除（tier<4 且无考试）

        List<String> drawn = judges.drawEligiblePool(1, disputeOrg, 10).map(Judge::accountId).collectList().block();

        assertThat(drawn).containsExactlyInAnyOrder(platform, otherOrgJudge);
        assertThat(drawn).doesNotContain(sameOrg, conflicted, lowTier);
    }

    @Test
    void drawEligiblePoolRequiresOperationalAdmissionAndLv5() {
        db.sql("TRUNCATE judge_conflict").then().block();
        db.sql("TRUNCATE judge CASCADE").then().block();
        String disputeOrg = UUID.randomUUID().toString();
        String eligible = seedJudge(UUID.randomUUID().toString(), null, 5, true, true);
        String pending = seedJudge(UUID.randomUUID().toString(), null, 5, true, false);
        String lowTier = seedJudge(UUID.randomUUID().toString(), null, 4, true, true);
        String inactive = seedJudge(UUID.randomUUID().toString(), null, 5, false, true);

        List<String> drawn = judges.drawEligiblePool(5, disputeOrg, 10).map(Judge::accountId).collectList().block();

        assertThat(drawn).containsExactly(eligible);
        assertThat(drawn).doesNotContain(pending, lowTier, inactive);
    }

    @Test
    void assignPanelIsIdempotent() {
        String org = UUID.randomUUID().toString();
        String disputeId = openDispute(org);
        String a = seedJudge(UUID.randomUUID().toString(), null, 5);
        String b = seedJudge(UUID.randomUUID().toString(), null, 5);

        assertThat(judges.assignPanel(disputeId, 1, List.of(a, b)).block()).isEqualTo(2);
        assertThat(judges.assignPanel(disputeId, 1, List.of(a, b)).block()).isEqualTo(0);  // 已存在
        assertThat(judges.countPanel(disputeId, 1).block()).isEqualTo(2);
        assertThat(judges.isPanelMember(disputeId, 1, a).block()).isTrue();
        assertThat(judges.isPanelMember(disputeId, 1, UUID.randomUUID().toString()).block()).isFalse();
    }

    @Test
    void assignPanelRechecksOrganizationAndDeclaredConflictsAtFinalInsert() {
        String disputeOrg = UUID.randomUUID().toString();
        String disputeId = openDispute(disputeOrg);
        String sameOrgAfterDraw = seedJudge(UUID.randomUUID().toString(), null, 5);
        String conflictedAfterDraw = seedJudge(UUID.randomUUID().toString(), null, 5);

        List<String> initiallyEligible = judges.drawEligiblePool(5, disputeOrg, 10)
                .map(Judge::accountId).collectList().block();
        assertThat(initiallyEligible).contains(sameOrgAfterDraw, conflictedAfterDraw);

        db.sql("UPDATE judge SET organization_id=CAST(:org AS uuid)"
                        + " WHERE account_id=CAST(:accountId AS uuid)")
                .bind("org", disputeOrg).bind("accountId", sameOrgAfterDraw).then().block();
        seedConflict(conflictedAfterDraw, disputeOrg);

        assertThat(judges.assignPanel(disputeId, 1,
                List.of(sameOrgAfterDraw, conflictedAfterDraw)).block()).isZero();
        assertThat(judges.countPanel(disputeId, 1).block()).isZero();
    }

    @Test
    void recordVoteAndTally() {
        String org = UUID.randomUUID().toString();
        String disputeId = openDispute(org);
        String a = seedJudge(UUID.randomUUID().toString(), null, 5);
        String b = seedJudge(UUID.randomUUID().toString(), null, 5);
        String c = seedJudge(UUID.randomUUID().toString(), null, 5);
        disputes.startAdjudication(disputeId, 1).block();
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

    @Test
    void recordVoteRechecksLocalAdmissionAtInsertTime() {
        String disputeId = openDispute(UUID.randomUUID().toString());
        String accountId = seedJudge(UUID.randomUUID().toString(), null, 5);
        disputes.startAdjudication(disputeId, 1).block();
        assertThat(judges.assignPanel(disputeId, 1, List.of(accountId)).block()).isEqualTo(1);
        db.sql("UPDATE judge SET ops_admitted=false, ops_admitted_at=NULL, ops_admitted_by=NULL"
                        + " WHERE account_id=CAST(:accountId AS uuid)")
                .bind("accountId", accountId).then().block();

        assertThat(judges.recordVote(disputeId, 1, accountId, "for_merchant", null).block()).isNull();
        Integer votes = db.sql("SELECT COUNT(*)::int AS count FROM dispute_vote"
                        + " WHERE dispute_id=CAST(:disputeId AS uuid)")
                .bind("disputeId", disputeId)
                .map(row -> row.get("count", Integer.class)).one().block();
        assertThat(votes).isZero();
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
        return seedJudge(accountId, orgId, tier, true, true);
    }

    private String seedJudge(String accountId, String orgId, int tier, boolean active, boolean admitted) {
        var spec = db.sql("INSERT INTO judge(id, account_id, organization_id, eligibility_tier, active,"
                        + " ops_admitted, ops_admitted_at, ops_admitted_by)"
                + " VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), CAST(:org AS uuid), :tier, :active, :admitted,"
                        + " CASE WHEN :admitted THEN now() ELSE NULL END,"
                        + " CASE WHEN :admitted THEN CAST(:actor AS uuid) ELSE NULL END)")
                .bind("id", UUID.randomUUID().toString()).bind("acct", accountId).bind("tier", tier);
        spec = spec.bind("active", active).bind("admitted", admitted).bind("actor", UUID.randomUUID().toString());
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
