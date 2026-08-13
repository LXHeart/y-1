package com.grassland.trust.adjudication;

import static org.assertj.core.api.Assertions.assertThat;
import static com.grassland.identity.assertion.TestAssertionHelper.userSigner;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.grassland.identity.assertion.IdentityAssertion;
import com.grassland.trust.TrustItSupport;
import com.grassland.trust.dispute.DeferredDisputeRequest;
import com.grassland.trust.dispute.DeferredDisputeRequestRepository;
import com.grassland.trust.dispute.DisputeCase;
import com.grassland.trust.dispute.DisputeCaseRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

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

    @Autowired
    private DeferredDisputeRequestRepository deferredRequests;

    private static final int PANEL_SIZE = 7;

    @Test
    void adjudicateAssignsPanelAndFlipsToVoting() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();
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
        // Slice 12 Stage 3：发射端补齐开启人，identity 通知中心不反查 trust。
        assertThat(outboxPayloadField("DisputeAssigned", id, "openedByAccountId")).isEqualTo(merchant);
        assertThat(outboxPayloadField("DisputeAssigned", id, "openedByRole")).isEqualTo("merchant");
    }

    @Test
    void adjudicateIsIdempotent() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String eng = UUID.randomUUID().toString();
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
    void adjudicateRepairsIncompletePanelToExactConfiguredSize() {
        clearJudges();
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < PANEL_SIZE + 1; i++) {
            String accountId = UUID.randomUUID().toString();
            seedJudge(accountId);
            candidates.add(accountId);
        }
        String id = open(merchant, org, UUID.randomUUID().toString());
        disputes.startAdjudication(id, 1).block();
        db.sql("INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id)"
                        + " VALUES (CAST(:disputeId AS uuid), 1, CAST(:accountId AS uuid))")
                .bind("disputeId", id).bind("accountId", candidates.get(0)).then().block();

        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.panel.size").isEqualTo(PANEL_SIZE);

        assertThat(panelJudges(id, 1)).hasSize(PANEL_SIZE);
        assertThat(outboxCount("DisputeAssigned", id)).isEqualTo(1);
    }

    @Test
    void adjudicateRejectsOtherOrg() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        seedJudges(PANEL_SIZE);
        String id = open(merchant, org, UUID.randomUUID().toString());
        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", UUID.randomUUID().toString(), "basic_publish"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void adjudicateRejectsFinalDispute() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());
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
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());
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
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());  // 不 seed 任何审判官
        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isEqualTo(503);
        // 503 后争议仍 open（可重试，未半提交到 voting）
        assertThat(statusOf(id)).isEqualTo("open");
    }

    @Test
    void adjudicateRequiresACompleteSevenJudgePanel() {
        clearJudges();
        String merchant = UUID.randomUUID().toString();
        String id = open(merchant, MARKETPLACE_ORG, UUID.randomUUID().toString());
        seedJudges(PANEL_SIZE - 1);

        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .exchange().expectStatus().isEqualTo(503);

        assertThat(statusOf(id)).isEqualTo("open");
        assertThat(panelJudges(id, 1)).isEmpty();
    }

    @Test
    void adjudicateRevalidatesEveryCandidateAndExcludesDowngradedJudge() {
        clearJudges();
        String merchant = UUID.randomUUID().toString();
        String id = open(merchant, MARKETPLACE_ORG, UUID.randomUUID().toString());
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < PANEL_SIZE; i++) {
            String accountId = UUID.randomUUID().toString();
            seedJudge(accountId);
            candidates.add(accountId);
        }
        String downgraded = candidates.get(0);
        when(reputationClient.getLevel(downgraded)).thenReturn(Mono.just(
                new com.grassland.trust.judge.MarketplaceReputationClient.LevelResult(
                        downgraded, "Lv4", 4, false, 9L)));

        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .exchange().expectStatus().isEqualTo(503);

        assertThat(statusOf(id)).isEqualTo("open");
        assertThat(panelJudges(id, 1)).isEmpty();
    }

    @Test
    void adjudicateFailsClosedWhenCandidateRevalidationFails() {
        clearJudges();
        String merchant = UUID.randomUUID().toString();
        String id = open(merchant, MARKETPLACE_ORG, UUID.randomUUID().toString());
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < PANEL_SIZE; i++) {
            String accountId = UUID.randomUUID().toString();
            seedJudge(accountId);
            candidates.add(accountId);
        }
        when(reputationClient.getLevel(candidates.get(0)))
                .thenReturn(Mono.error(new RuntimeException("marketplace unavailable")));

        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .exchange().expectStatus().isEqualTo(503);

        assertThat(statusOf(id)).isEqualTo("open");
        assertThat(panelJudges(id, 1)).isEmpty();
    }

    @Test
    void adjudicateRollsBackWhenAdmissionIsRevokedAfterRemoteValidation() {
        clearJudges();
        String merchant = UUID.randomUUID().toString();
        String id = open(merchant, MARKETPLACE_ORG, UUID.randomUUID().toString());
        List<String> candidates = new ArrayList<>();
        for (int i = 0; i < PANEL_SIZE; i++) {
            String accountId = UUID.randomUUID().toString();
            seedJudge(accountId);
            candidates.add(accountId);
        }
        String revoked = candidates.get(0);
        var eligible = new com.grassland.trust.judge.MarketplaceReputationClient.LevelResult(
                revoked, "Lv5", 5, true, 12L);
        when(reputationClient.getLevel(revoked)).thenReturn(
                db.sql("UPDATE judge SET ops_admitted=false, ops_admitted_at=NULL, ops_admitted_by=NULL,"
                                + " version=version+1 WHERE account_id=CAST(:a AS uuid)")
                        .bind("a", revoked).then().thenReturn(eligible));

        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .exchange().expectStatus().isEqualTo(503);

        assertThat(statusOf(id)).isEqualTo("open");
        assertThat(panelJudges(id, 1)).isEmpty();
        assertThat(outboxCount("DisputeAssigned", id)).isZero();
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
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());  // 未 adjudicate → open
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
    void voteRejectsJudgeWhoseOperationsAdmissionWasRevoked() {
        Panel panel = startPanel();
        String judge = panel.judges.get(0);
        db.sql("UPDATE judge SET ops_admitted=false, ops_admitted_at=NULL, ops_admitted_by=NULL, version=version+1"
                        + " WHERE account_id=CAST(:a AS uuid)")
                .bind("a", judge).then().block();

        vote(panel.id, judge, "for_merchant", null).expectStatus().isForbidden();
    }

    @Test
    void voteUsesAssignmentSnapshotWhenMarketplaceEligibilityChanges() {
        Panel panel = startPanel();
        String judge = panel.judges.get(0);
        clearInvocations(reputationClient);
        when(reputationClient.getLevel(judge)).thenReturn(Mono.just(
                new com.grassland.trust.judge.MarketplaceReputationClient.LevelResult(
                        judge, "Lv4", 4, false, 11L)));

        vote(panel.id, judge, "for_merchant", null).expectStatus().isCreated();
        verify(reputationClient, never()).getLevel(judge);
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

    /**
     * 面板审判官必须能读快照——否则「能投票却看不见要投什么」。
     *
     * 浏览器实测抓到的集成缺口：castVote 用 requireJudge + 面板成员放行，
     * 而 getAdjudication 只认「当事方 org / marketplace 服务」，审判官恒 403，
     * 前端看板显示「无权查询该争议」，投票按钮组渲染不出来。
     * 这与 judge 身份、customer_service 角色属同一类跨服务不一致——写路径放行、读路径没跟上。
     */
    @Test
    void getAdjudicationAllowsPanelJudge() {
        Panel panel = startPanel();
        String judge = panel.judges.get(0);

        String body = client().get().uri("/api/trust/disputes/" + panel.id + "/adjudication")
                // 审判官 = 推荐官 + 已入池，且不属于当事方 org
                .header("X-Grassland-Identity", sign(judge, "recommender", null, null))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"status\":\"voting\"");
        assertThat(body).doesNotContain(judge);  // 对审判官本人也保持脱敏
    }

    /**
     * 客服必须能读快照——否则「能覆盖判决却看不见判的是什么」。
     *
     * 浏览器实测抓到：客服既非 merchant 也非 recommender，被 resolvePartyOrService 直接过滤，
     * 前端看板恒显示「无权查询争议」，连「客服终审」折叠区都渲染不出来，终审在 UI 上完全不可达。
     * 这是 judge 身份、customer_service 角色之后，同一类「写路径放行、读路径没跟上」的第三次出现。
     */
    @Test
    void getAdjudicationAllowsCustomerService() {
        Panel panel = startPanel();

        String body = client().get().uri("/api/trust/disputes/" + panel.id + "/adjudication")
                // 客服无 org，跨 org 亦须可读（平台职能，HLD §11.2 兜底）
                .header("X-Grassland-Identity", signCs(UUID.randomUUID().toString(), Instant.now()))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"status\":\"voting\"");
    }

    /** 放行仅限**本轮面板成员**：非面板推荐官仍须 403（不能靠改 URL 围观任意争议）。 */
    @Test
    void getAdjudicationRejectsNonPanelRecommender() {
        Panel panel = startPanel();
        client().get().uri("/api/trust/disputes/" + panel.id + "/adjudication")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender", null, null))
                .exchange().expectStatus().isForbidden();
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
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());
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
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());  // open，未判决
        client().post().uri("/api/trust/disputes/" + id + "/appeal")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of())
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void appealIsOncePerDispute() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());
        toDecided(id);
        client().post().uri("/api/trust/disputes/" + id + "/appeal")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange().expectStatus().isOk();
        client().post().uri("/api/trust/disputes/" + id + "/appeal")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of()).exchange().expectStatus().isEqualTo(409);
    }

    /** D-03：merchant_rejection 不走 7 官面板，open 态可由近期 MFA 客服直接终审。 */
    @Test
    void customerServiceFinalDecisionOnOpenMerchantRejection() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String id = disputes.create(UUID.randomUUID().toString(), org, merchant,
                "merchant", "系统核实与实际不符", "merchant_rejection").block().id();

        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", signCs(UUID.randomUUID().toString(), Instant.now()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "for_merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("final")
                .jsonPath("$.data.finalDecision").isEqualTo("for_merchant");
        assertThat(outboxCount("DisputeFinalized", id)).isEqualTo(1);
    }

    @Test
    void customerServiceFinalDecisionPromotesDeferredObjection() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String recommender = UUID.randomUUID().toString();
        String engagement = UUID.randomUUID().toString();
        DisputeCase source = disputes.create(engagement, org, merchant,
                "merchant", "商家异议", "merchant_rejection").block();
        DeferredDisputeRequest request = deferredRequests
                .createOrFind(source, recommender, "  人工终审后仍需七官裁定  ").block();

        client().post().uri("/api/trust/disputes/" + source.id() + "/final-decision")
                .header("X-Grassland-Identity", signCs(UUID.randomUUID().toString(), Instant.now()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "for_merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("final")
                .jsonPath("$.data.finalDecision").isEqualTo("for_merchant");

        DeferredDisputeRequest promoted = deferredRequests.findById(request.id()).block();
        DisputeCase successor = disputes.findById(promoted.promotedDisputeId()).block();
        assertThat(promoted.status()).isEqualTo("promoted");
        assertThat(promoted.adjudicationWorkflowId()).isEqualTo("adjudicate-" + successor.id());
        assertThat(successor.kind()).isEqualTo("standard");
        assertThat(successor.openedByAccountId()).isEqualTo(recommender);
        assertThat(successor.openedByRole()).isEqualTo("recommender");
        assertThat(successor.reason()).isEqualTo("  人工终审后仍需七官裁定  ");
        assertThat(disputes.findActiveByEngagementRef(engagement).block().id()).isEqualTo(successor.id());
        assertThat(outboxPayloadField("DisputeFinalized", source.id(), "settlementDeferred")).isEqualTo("true");
        assertThat(outboxPayloadField("DisputeFinalized", source.id(), "successorDisputeId"))
                .isEqualTo(successor.id());

        // HTTP 重试按既有 final-decision 契约返回 409，且不生成第二个 successor / 终局事件。
        client().post().uri("/api/trust/disputes/" + source.id() + "/final-decision")
                .header("X-Grassland-Identity", signCs(UUID.randomUUID().toString(), Instant.now()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "for_merchant"))
                .exchange().expectStatus().isEqualTo(409);
        Integer disputeCount = db.sql("SELECT COUNT(*)::int AS c FROM dispute_case WHERE engagement_ref = :ref")
                .bind("ref", engagement).map(row -> row.get("c", Integer.class)).one().block();
        assertThat(disputeCount).isEqualTo(2);
        assertThat(outboxCount("DisputeFinalized", source.id())).isEqualTo(1);
    }

    @Test
    void customerServiceFinalDecisionOverridesAppealed() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());
        toAppealed(id);  // decided→appealed
        String cs = UUID.randomUUID().toString();

        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", signCs(cs, Instant.now()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "for_recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("final")
                .jsonPath("$.data.finalDecision").isEqualTo("for_recommender");
        assertThat(outboxCount("DisputeFinalized", id)).isEqualTo(1);
        assertThat(outboxPayloadField("DisputeFinalized", id, "openedByAccountId")).isEqualTo(merchant);
    }

    @Test
    void customerServiceFinalDecisionOnEscalated() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());
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
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());
        toAppealed(id);
        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "for_merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void adminRoleCanAlsoFinalize() {
        // admin 是客服超集（平台管理员可执行客服动作）
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());
        toAppealed(id);
        Instant now = Instant.now();
        String adminAssertion = userSigner("edge-bff", "grassland-trust").sign(new IdentityAssertion(
                UUID.randomUUID().toString(), null, "sid-admin", null, null,
                "cookie-session", "level2", now, "r", "t",
                "grassland-trust", now, now.plusSeconds(60), null, null, "admin"));

        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", adminAssertion)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "for_merchant"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void serviceAssertionCannotImpersonateCustomerService() {
        // 防冒充：服务断言即便带 role 也不得执行客服动作（服务不是人）
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());
        toAppealed(id);
        Instant now = Instant.now();
        String serviceWithRole = signServiceWithRole(org, "marketplace", "customer_service");

        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", serviceWithRole)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "for_merchant"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void finalDecisionRequiresRecentMfa() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String id = open(merchant, org, UUID.randomUUID().toString());
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
        String org = MARKETPLACE_ORG;
        List<String> all = new ArrayList<>();
        for (int i = 0; i < PANEL_SIZE + 1; i++) {
            String acct = UUID.randomUUID().toString();
            seedJudge(acct);
            all.add(acct);
        }
        String id = open(merchant, org, UUID.randomUUID().toString());
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

    private void clearJudges() {
        db.sql("TRUNCATE judge_conflict").then().block();
        db.sql("TRUNCATE judge CASCADE").then().block();
    }

    private void seedJudge(String accountId) {
        db.sql("INSERT INTO judge(id, account_id, organization_id, eligibility_tier, active,"
                        + " ops_admitted, ops_admitted_at, ops_admitted_by)"
                + " VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), NULL, 5, true, true, now(), CAST(:actor AS uuid))")
                .bind("id", UUID.randomUUID().toString()).bind("acct", accountId)
                .bind("actor", UUID.randomUUID().toString())
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

    /** 读取按 disputeId 限定的事件 payload 顶层字段（Slice 12 Stage 3 收件人字段断言）。 */
    private String outboxPayloadField(String eventType, String disputeId, String field) {
        return db.sql("SELECT payload->>'" + field + "' AS v FROM trust_outbox"
                        + " WHERE event_type = :et AND payload->>'disputeId' = :id")
                .bind("et", eventType).bind("id", disputeId)
                .map(r -> r.get("v", String.class)).one().block();
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
    /**
     * 客服断言：按<b>平台角色</b>（末位 role 参数）而非 activeIdentityType——
     * customer_service 不是 identity 支持的业务身份（与 judge 同类问题，e2e 联调发现）。
     */
    private String signCs(String accountId, Instant reauthenticatedAt) {
        Instant now = Instant.now();
        return userSigner("edge-bff", "grassland-trust").sign(new IdentityAssertion(
                accountId, null, "sid-" + accountId, null, null,
                "cookie-session", "level2", reauthenticatedAt, "r", "t",
                "grassland-trust", now, now.plusSeconds(60), null, null, "customer_service"));
    }
}
