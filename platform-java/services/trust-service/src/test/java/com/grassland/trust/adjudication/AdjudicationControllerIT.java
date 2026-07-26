package com.grassland.trust.adjudication;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.trust.TrustItSupport;
import com.grassland.trust.dispute.DisputeCaseRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 审判端到端（草场 Epic 6 Slice 6C Phase B + C-2）。继承 {@link TrustItSupport}。
 *
 * <p>覆盖：adjudicate（202 分配面板 + DisputeAssigned / 幂等 200 / 角色门禁 / 409 终局 / 503 无审判官）、
 * votes（201 + tally 累计 / 幂等 200 / 非面板 403 / 非审判官 403 / 非 voting 409 / 非法选项 400 / 4-of-7 多数）、
 * getAdjudication（快照 + 脱敏 + 服务断言 / 跨 org 403 / 404）、
 * appeal（decided→appealed / 非 decided 409 / 重复 409）、final-decision（客服覆盖 appealed/escalated + MFA / 非客服 403 / MFA 过期 403）。
 */
class AdjudicationControllerIT extends TrustItSupport {

    @Autowired
    private DisputeCaseRepository disputes;

    private static final int PANEL_SIZE = 7;

    @Test
    void adjudicateAssignsPanelAndFlipsToVoting() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String eng = "app-" + UUID.randomUUID();
        seedJudges(PANEL_SIZE);
        String id = open(merchant, org, eng);

        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted().expectBody()
                .jsonPath("$.data.status").isEqualTo("voting")
                .jsonPath("$.data.round").isEqualTo(1)
                .jsonPath("$.data.panel.size").isEqualTo(PANEL_SIZE)
                .jsonPath("$.data.panel.voted").isEqualTo(0)
                .jsonPath("$.data.tallies.majority").isEmpty();

        assertThat(panelJudges(id, 1)).hasSize(PANEL_SIZE);
        assertThat(outboxCount("DisputeAssigned", id)).isEqualTo(1);
    }

    @Test
    void adjudicateIsIdempotent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String eng = "app-" + UUID.randomUUID();
        seedJudges(PANEL_SIZE);
        String id = open(merchant, org, eng);
        adjudicate(merchant, org, id);  // 202 首启
        // 第二次：幂等 200，面板不变，事件不重发（确定性 eventId）
        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("voting")
                .jsonPath("$.data.panel.size").isEqualTo(PANEL_SIZE);
        assertThat(outboxCount("DisputeAssigned", id)).isEqualTo(1);
    }

    @Test
    void adjudicateRejectsOtherOrg() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        seedJudges(PANEL_SIZE);
        String id = open(merchant, org, "app-" + UUID.randomUUID());
        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", UUID.randomUUID().toString(), "basic_publish"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void adjudicateRejectsFinalDispute() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = open(merchant, org, "app-" + UUID.randomUUID());
        // 手动 decide → final
        client().post().uri("/api/trust/disputes/" + id + "/decide")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "in_merchant_favor"))
                .exchange().expectStatus().isOk();
        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void adjudicateRejectsNonParty() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = open(merchant, org, "app-" + UUID.randomUUID());
        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "consumer", null, null))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void adjudicateReturns503WhenNoJudges() {
        // 共享容器跨测试累积 judge 行——本例需空池触发 503，先清空。
        db.sql("TRUNCATE judge_conflict").then().block();
        db.sql("TRUNCATE judge CASCADE").then().block();
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = open(merchant, org, "app-" + UUID.randomUUID());  // 不 seed 任何审判官
        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isEqualTo(503);
        // 503 后争议仍 open（可重试，未半提交到 voting）
        assertThat(statusOf(id)).isEqualTo("open");
    }

    @Test
    void judgeCastsVoteAndTallyAccumulates() {
        Panel panel = startPanel();
        String first = panel.judges.get(0);
        String second = panel.judges.get(1);
        vote(panel.id, first, "for_merchant", null).expectStatus().isCreated().expectBody()
                .jsonPath("$.data.vote").isEqualTo("for_merchant")
                .jsonPath("$.data.tallies.forMerchant").isEqualTo(1)
                .jsonPath("$.data.tallies.majority").isEmpty();
        vote(panel.id, second, "for_recommender", "证据不足").expectStatus().isCreated().expectBody()
                .jsonPath("$.data.tallies.forRecommender").isEqualTo(1)
                .jsonPath("$.data.tallies.forMerchant").isEqualTo(1);
    }

    @Test
    void voteIsIdempotentPerJudge() {
        Panel panel = startPanel();
        String judge = panel.judges.get(0);
        vote(panel.id, judge, "for_merchant", null).expectStatus().isCreated();
        // 同官再投（同选项）→ 200 既有，tally 不变
        vote(panel.id, judge, "for_merchant", null).expectStatus().isOk().expectBody()
                .jsonPath("$.data.tallies.forMerchant").isEqualTo(1);
    }

    @Test
    void voteRejectsNonPanelJudge() {
        Panel panel = startPanel();  // panel 取 7，第 8 名被排除
        String outsider = panel.leftOut;
        vote(panel.id, outsider, "for_merchant", null).expectStatus().isForbidden();
    }

    @Test
    void voteRejectsNonJudgeCaller() {
        Panel panel = startPanel();
        // 商家试图投票 → 403
        client().post().uri("/api/trust/disputes/" + panel.id + "/votes")
                .header("X-Grassland-Identity", sign(panel.merchant, "merchant", panel.org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("vote", "for_merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void voteRejectsOpenDispute() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = open(merchant, org, "app-" + UUID.randomUUID());  // 未 adjudicate → open
        String judge = UUID.randomUUID().toString();
        seedJudge(judge);  // 须已入池，否则先被入池门禁拦成 403，测不到「非投票阶段」这条
        client().post().uri("/api/trust/disputes/" + id + "/votes")
                .header("X-Grassland-Identity", sign(judge, "recommender", null, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("vote", "for_merchant"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void voteRejectsJudgeNotInPool() {
        // 新门禁（e2e 联调修正）：审判官 = 推荐官 + 已入池。未入池的推荐官不可投票。
        Panel panel = startPanel();
        client().post().uri("/api/trust/disputes/" + panel.id + "/votes")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender", null, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("vote", "for_merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void voteRejectsJudgeWhoLeftPool() {
        Panel panel = startPanel();
        String judge = panel.judges.get(0);
        // 退池后即失去投票权（入池状态即权限开关）
        db.sql("UPDATE judge SET active = false WHERE account_id = CAST(:a AS uuid)")
                .bind("a", judge).then().block();
        client().post().uri("/api/trust/disputes/" + panel.id + "/votes")
                .header("X-Grassland-Identity", sign(judge, "recommender", null, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("vote", "for_merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void voteRejectsInvalidChoice() {
        Panel panel = startPanel();
        client().post().uri("/api/trust/disputes/" + panel.id + "/votes")
                .header("X-Grassland-Identity", sign(panel.judges.get(0), "recommender", null, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("vote", "bogus"))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    void fourOfSevenReachesMerchantMajority() {
        Panel panel = startPanel();
        for (int i = 0; i < 4; i++) {
            vote(panel.id, panel.judges.get(i), "for_merchant", null);
        }
        for (int i = 4; i < 7; i++) {
            vote(panel.id, panel.judges.get(i), "for_recommender", null);
        }
        client().get().uri("/api/trust/disputes/" + panel.id + "/adjudication")
                .header("X-Grassland-Identity", sign(panel.merchant, "merchant", panel.org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.tallies.forMerchant").isEqualTo(4)
                .jsonPath("$.data.tallies.forRecommender").isEqualTo(3)
                .jsonPath("$.data.tallies.majority").isEqualTo("for_merchant");
    }

    @Test
    void getAdjudicationSnapshotIsDesensitizedAndServiceAccessible() {
        Panel panel = startPanel();
        vote(panel.id, panel.judges.get(0), "for_merchant", null);
        String body = client().get().uri("/api/trust/disputes/" + panel.id + "/adjudication")
                .header("X-Grassland-Identity", sign(panel.merchant, "merchant", panel.org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"status\":\"voting\"").contains("\"forMerchant\":1");
        // 脱敏：不暴露审判官 account_id / rationale
        assertThat(body).doesNotContain(panel.judges.get(0));
        // marketplace 服务断言也可查
        client().get().uri("/api/trust/disputes/" + panel.id + "/adjudication")
                .header("X-Grassland-Identity", signService(panel.org, "marketplace"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void getAdjudicationRejectsOtherOrg() {
        Panel panel = startPanel();
        client().get().uri("/api/trust/disputes/" + panel.id + "/adjudication")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", UUID.randomUUID().toString(), "basic_publish"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void getAdjudication404WhenUnknown() {
        client().get().uri("/api/trust/disputes/" + UUID.randomUUID() + "/adjudication")
                .header("X-Grassland-Identity", signService(UUID.randomUUID().toString(), "marketplace"))
                .exchange().expectStatus().isNotFound();
    }

    // ----- Phase C-2: appeal + 客服终审 -----

    @Test
    void partyAppealsDecidedDispute() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = open(merchant, org, "app-" + UUID.randomUUID());
        toDecided(id);  // open→voting→decided（repo 直置，绕过 24h Timer）

        client().post().uri("/api/trust/disputes/" + id + "/appeal")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "不服判决"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("appealed");
        assertThat(outboxCount("DisputeAppealed", id)).isEqualTo(1);
    }

    @Test
    void appealRejectsNonDecided() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = open(merchant, org, "app-" + UUID.randomUUID());  // open，未判决
        client().post().uri("/api/trust/disputes/" + id + "/appeal")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void appealIsOncePerDispute() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = open(merchant, org, "app-" + UUID.randomUUID());
        toDecided(id);
        client().post().uri("/api/trust/disputes/" + id + "/appeal")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange().expectStatus().isOk();
        client().post().uri("/api/trust/disputes/" + id + "/appeal")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void customerServiceFinalDecisionOverridesAppealed() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = open(merchant, org, "app-" + UUID.randomUUID());
        toAppealed(id);  // decided→appealed
        String cs = UUID.randomUUID().toString();

        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", signCs(cs, Instant.now()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "for_recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("final")
                .jsonPath("$.data.finalDecision").isEqualTo("for_recommender");
        assertThat(outboxCount("DisputeFinalized", id)).isEqualTo(1);
    }

    @Test
    void customerServiceFinalDecisionOnEscalated() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = open(merchant, org, "app-" + UUID.randomUUID());
        toEscalated(id);  // voting + appeal_state=escalated（超轮无判决）
        String cs = UUID.randomUUID().toString();

        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", signCs(cs, Instant.now()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "for_merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("final");
    }

    @Test
    void finalDecisionRejectsNonCustomerService() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = open(merchant, org, "app-" + UUID.randomUUID());
        toAppealed(id);
        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "for_merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void finalDecisionRequiresRecentMfa() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String id = open(merchant, org, "app-" + UUID.randomUUID());
        toAppealed(id);
        // reauthenticatedAt 为 1 小时前（超出 5 分钟窗口）→ 403
        Instant stale = Instant.now().minusSeconds(3600);
        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", signCs(UUID.randomUUID().toString(), stale))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "for_merchant"))
                .exchange().expectStatus().isForbidden();
    }

    // ---------- helpers ----------

    private record Panel(String id, String merchant, String org, List<String> judges, String leftOut) {}

    /** seed PANEL_SIZE+1 审判官，开争议，adjudicate，返回 panel（7 名）+ 被排除的 1 名。 */
    private Panel startPanel() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        List<String> all = new ArrayList<>();
        for (int i = 0; i < PANEL_SIZE + 1; i++) {
            String acct = UUID.randomUUID().toString();
            seedJudge(acct);
            all.add(acct);
        }
        String id = open(merchant, org, "app-" + UUID.randomUUID());
        adjudicate(merchant, org, id);
        List<String> drawn = panelJudges(id, 1);
        assertThat(drawn).hasSize(PANEL_SIZE);
        String leftOut = all.stream().filter(a -> !drawn.contains(a)).findFirst().orElseThrow();
        return new Panel(id, merchant, org, drawn, leftOut);
    }

    private void adjudicate(String merchant, String org, String id) {
        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
    }

    private org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec vote(
            String disputeId, String judgeAccountId, String vote, String rationale) {
        Map<String, Object> body = rationale == null
                ? Map.of("vote", vote)
                : Map.of("vote", vote, "rationale", rationale);
        return client().post().uri("/api/trust/disputes/" + disputeId + "/votes")
                .header("X-Grassland-Identity", sign(judgeAccountId, "recommender", null, null))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange();
    }

    private void seedJudges(int count) {
        for (int i = 0; i < count; i++) {
            seedJudge(UUID.randomUUID().toString());
        }
    }

    private void seedJudge(String accountId) {
        db.sql("INSERT INTO judge(id, account_id, organization_id, eligibility_tier, active)"
                + " VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), NULL, 1, true)")
                .bind("id", UUID.randomUUID().toString()).bind("acct", accountId)
                .then().block();
    }

    @SuppressWarnings("unchecked")
    private String open(String merchant, String org, String eng) {
        Map<String, Object> resp = client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) ((Map<String, Object>) resp.get("data")).get("id");
    }

    private List<String> panelJudges(String disputeId, int round) {
        return db.sql("SELECT judge_account_id::text AS a FROM dispute_panel_assignment"
                + " WHERE dispute_id = CAST(:id AS uuid) AND round = :round")
                .bind("id", disputeId).bind("round", round)
                .map(r -> r.get("a", String.class)).all().collectList().block();
    }

    private String statusOf(String disputeId) {
        return db.sql("SELECT status FROM dispute_case WHERE id = CAST(:id AS uuid)")
                .bind("id", disputeId)
                .map(r -> r.get("status", String.class)).one().block();
    }

    private long outboxCount(String eventType, String disputeId) {
        return db.sql("SELECT COUNT(*)::int AS c FROM trust_outbox"
                        + " WHERE event_type = :et AND payload->>'disputeId' = :id")
                .bind("et", eventType).bind("id", disputeId)
                .map(r -> r.get("c", Integer.class)).one().block().longValue();
    }

    // ----- 状态前置（绕过 24h Timer，repo 直置）-----

    private void toDecided(String id) {
        disputes.startAdjudication(id, 1).block();          // open→voting
        disputes.recordDecision(id, "for_merchant").block(); // voting→decided
    }

    private void toAppealed(String id) {
        toDecided(id);
        disputes.markAppealed(id).block();                  // decided→appealed
    }

    private void toEscalated(String id) {
        disputes.startAdjudication(id, 1).block();          // open→voting
        disputes.markEscalated(id).block();                 // appeal_state=escalated（保持 voting）
    }

    /** 签一个客服断言（activeIdentityType=customer_service），reauthenticatedAt 控制近期性（MFA）。 */
    private String signCs(String accountId, Instant reauthenticatedAt) {
        Instant now = Instant.now();
        return signer.sign(new IdentityAssertion(
                accountId, "customer_service", "sid-" + accountId, null, null,
                "cookie-session", "level2", reauthenticatedAt, "r", "t",
                "grassland-internal", now, now.plusSeconds(60), null, null));
    }
}
