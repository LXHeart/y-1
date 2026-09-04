package com.grassland.trust.dispute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.grassland.trust.TrustItSupport;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * 任务书 #74 争议小法庭重构——卡 A（通道 cs_direct + SLA）/ 卡 B（质证期轮次规则 + 质证完毕）/
 * 卡 F（客服终审三选）/ 卡 G（脱敏判例库）端到端。继承 {@link TrustItSupport}。
 *
 * <p>SLA workflow 的 Timer 由 Temporal 驱动，IT（test-server 慢时钟）不等 5 天——
 * 到点自动终局直接调 activity 实现（幂等语义同 workflow 调用）；编排正确性由
 * {@code CsDirectSlaWorkflowReplayTest}（TestWorkflowEnvironment 时间快进）覆盖。
 */
class DisputeSmallCourtIT extends TrustItSupport {

    @Autowired
    private DisputeCaseRepository disputes;

    @Autowired
    @Qualifier("adjudicationActivityImpl")
    private com.grassland.trust.workflow.AdjudicationActivityImpl activity;

    // ---------- 卡 A：通道选择 + cs_direct ----------

    @Test
    void csDirectDisputePersistsChannelAndDueAt() {
        String merchant = UUID.randomUUID().toString();
        String eng = UUID.randomUUID().toString();
        String body = client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", eng, "reason", "客服直裁通道", "channel", "cs_direct"))
                .exchange().expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"channel\":\"cs_direct\"");
        DisputeCase d = disputes.findActiveByEngagementRef(eng).block();
        assertThat(d).isNotNull();
        assertThat(d.effectiveChannel()).isEqualTo("cs_direct");
        assertThat(d.csDueAt()).isNotNull(); // = 受理时刻 + SLA（默认 120h）
        assertThat(d.csDueAt()).isAfter(Instant.now().plusSeconds(119 * 3600));
        assertThat(d.status()).isEqualTo("open"); // cs_direct 不质证
        // 开争议事件载荷带 channel（identity 按通道分流文案）
        assertThat(outboxPayloadField("DisputeOpened", d.id(), "channel")).isEqualTo("cs_direct");
    }

    @Test
    void courtChannelDefaultsToEvidencePhaseWithDeadline() {
        String merchant = UUID.randomUUID().toString();
        String eng = UUID.randomUUID().toString();
        String body = client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng))
                .exchange().expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"channel\":\"court\"").contains("\"status\":\"evidence\"");
        DisputeCase d = disputes.findActiveByEngagementRef(eng).block();
        assertThat(d.evidenceDeadline()).isNotNull();
        assertThat(d.effectiveChannel()).isEqualTo("court");
    }

    @Test
    void csDirectDisputeCannotAdjudicate() {
        String merchant = UUID.randomUUID().toString();
        String id = openCsDirect(merchant);
        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").isEqualTo("客服直裁争议不进入审判面板");
    }

    @Test
    void csFinalDecisionOnOpenCsDirectDispute() {
        String merchant = UUID.randomUUID().toString();
        String id = openCsDirect(merchant);
        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", signCs(UUID.randomUUID().toString(), Instant.now()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("action", "maintain", "decision", "for_recommender"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("final")
                .jsonPath("$.data.finalDecision").isEqualTo("for_recommender");
        assertThat(outboxCount("DisputeFinalized", id)).isEqualTo(1);
        // 卡 G：cs_direct 终局即判例（final_via=cs、vote_summary 空）
        assertThat(precedentCount(id)).isEqualTo(1);
    }

    @Test
    void csDirectSlaActivityAutoFinalizesIdempotently() {
        String merchant = UUID.randomUUID().toString();
        String id = openCsDirect(merchant);
        // 到点自动终局：默认维持系统核实结果 for_recommender，事件附 auto:true
        activity.autoFinalizeCsDirect(id);
        DisputeCase fin = disputes.findById(id).block();
        assertThat(fin.status()).isEqualTo("final");
        assertThat(fin.finalDecision()).isEqualTo("for_recommender");
        assertThat(outboxCount("DisputeFinalized", id)).isEqualTo(1);
        assertThat(outboxPayloadField("DisputeFinalized", id, "auto")).isEqualTo("true");
        assertThat(precedentCount(id)).isEqualTo(1);
        // 幂等：已终局再触发不动（无第二个事件/判例）
        activity.autoFinalizeCsDirect(id);
        assertThat(outboxCount("DisputeFinalized", id)).isEqualTo(1);
        assertThat(precedentCount(id)).isEqualTo(1);
    }

    // ---------- 卡 B：举证质证期 ----------

    @Test
    void answerRoundRulesEnforcedWithHumanReadable409() {
        String merchant = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String eng = UUID.randomUUID().toString();
        String id = openBy(merchant, "merchant", eng);
        stubParty(eng, recommender, "recommender");

        // 非被诉方（开争议的 merchant 本人）不能 answer → 409
        client().post().uri("/api/trust/disputes/" + id + "/evidence")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("phase", "answer",
                        "items", java.util.List.of(Map.of("kind", "text", "contentRef", "我的答辩说明"))))
                .exchange().expectStatus().isEqualTo(409);

        // 被诉方（recommender）答辩 → 201 + respondent_answered 置位
        client().post().uri("/api/trust/disputes/" + id + "/evidence")
                .header("X-Grassland-Identity", sign(recommender, "recommender", null, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("phase", "answer",
                        "items", java.util.List.of(Map.of("kind", "text", "contentRef", "凭证已按约定提交并验收"))))
                .exchange().expectStatus().isCreated();
        assertThat(disputes.findById(id).block().respondentAnswered()).isTrue();

        // 每案至多一次 → 409
        client().post().uri("/api/trust/disputes/" + id + "/evidence")
                .header("X-Grassland-Identity", sign(recommender, "recommender", null, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("phase", "answer",
                        "items", java.util.List.of(Map.of("kind", "text", "contentRef", "第二次答辩"))))
                .exchange().expectStatus().isEqualTo(409);

        // 原告补充质证：已有 answer → 可补一次
        client().post().uri("/api/trust/disputes/" + id + "/evidence")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("phase", "rebuttal",
                        "items", java.util.List.of(Map.of("kind", "text", "contentRef", "针对答辩的补充说明"))))
                .exchange().expectStatus().isCreated();
        // 再补 → 409
        client().post().uri("/api/trust/disputes/" + id + "/evidence")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("phase", "rebuttal",
                        "items", java.util.List.of(Map.of("kind", "text", "contentRef", "第三次补充"))))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void evidenceDoneMarksBothPartiesAndStaysIdempotent() {
        String merchant = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String eng = UUID.randomUUID().toString();
        String id = openBy(merchant, "merchant", eng);
        stubParty(eng, recommender, "recommender");

        // 原告标记
        client().post().uri("/api/trust/disputes/" + id + "/evidence-done")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.bothDone").isEqualTo(false);
        // 重复标记 → 409
        client().post().uri("/api/trust/disputes/" + id + "/evidence-done")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .exchange().expectStatus().isEqualTo(409);
        // 被告标记 → bothDone
        client().post().uri("/api/trust/disputes/" + id + "/evidence-done")
                .header("X-Grassland-Identity", sign(recommender, "recommender", null, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.bothDone").isEqualTo(true);
        DisputeCase d = disputes.findById(id).block();
        assertThat(d.claimantDoneAt()).isNotNull();
        assertThat(d.respondentDoneAt()).isNotNull();
    }

    @Test
    void respondentAbsentAnnotatedInSnapshotWithoutPenalty() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String id = openBy(merchant, "merchant", UUID.randomUUID().toString());
        seedJudges(7);
        adjudicate(merchant, org, id);
        // 被告未答辩 → 快照标注缺席（不判负、无惩罚性字段）
        client().get().uri("/api/trust/disputes/" + id + "/adjudication")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.respondentAbsent").isEqualTo(true)
                .jsonPath("$.data.respondentAnswered").isEqualTo(false);
    }

    // ---------- 卡 F：客服终审三选 ----------

    @Test
    void csRetrialRestartsVotingWithFreshPanelAndHoldsFunds() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String id = openBy(merchant, "merchant", UUID.randomUUID().toString());
        seedJudges(16);
        adjudicate(merchant, org, id);
        var round1 = panelJudges(id, 1);
        toDecided(id);
        toAppealedByHttp(merchant, org, id);

        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", signCs(UUID.randomUUID().toString(), Instant.now()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("action", "retrial"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("voting")
                .jsonPath("$.data.round").isEqualTo(2);

        DisputeCase d = disputes.findById(id).block();
        assertThat(d.appealState()).isEqualTo("none");
        // 资金仍 hold：非 final 占活跃槽（DisputeChecker 口径）
        client().get().uri("/api/trust/engagements/" + d.engagementRef() + "/open-dispute")
                .header("X-Grassland-Identity", signService(org, "marketplace"))
                .exchange().expectStatus().isOk();
        // 新面板无旧成员
        var round2 = panelJudges(id, 2);
        assertThat(round2).hasSize(7);
        assertThat(round2).doesNotContainAnyElementsOf(round1);
        // AdjudicationReopened 事件
        assertThat(outboxCount("AdjudicationReopened", id)).isGreaterThanOrEqualTo(1);
        // 上诉行落 decided/retrial（判例 final_via 依据）
        String appealDecision = db.sql("SELECT final_decision FROM dispute_appeal WHERE dispute_id = CAST(:id AS uuid)")
                .bind("id", id).map(r -> r.get("final_decision", String.class)).one().block();
        assertThat(appealDecision).isEqualTo("retrial");
    }

    @Test
    void retrialRejectedOnEscalatedDispute() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String id = openBy(merchant, "merchant", UUID.randomUUID().toString());
        seedJudges(7);
        adjudicate(merchant, org, id);
        toEscalated(id);
        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", signCs(UUID.randomUUID().toString(), Instant.now()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("action", "retrial"))
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.error").value(v ->
                        assertThat(String.valueOf(v)).contains("发回重审"));
    }

    @Test
    void legacyDecisionOnlyPayloadBehavesAsOverturn() {
        String merchant = UUID.randomUUID().toString();
        String org = MARKETPLACE_ORG;
        String id = openBy(merchant, "merchant", UUID.randomUUID().toString());
        toDecided(id);
        toAppealedByHttp(merchant, org, id);
        // 老表单只传 decision（无 action）→ 视作 overturn，兼容不破
        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", signCs(UUID.randomUUID().toString(), Instant.now()))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("decision", "for_merchant"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.status").isEqualTo("final")
                .jsonPath("$.data.finalDecision").isEqualTo("for_merchant");
    }

    // ---------- 卡 G：脱敏判例库 ----------

    @Test
    void precedentGeneratedOnFinalizeAndDesensitized() {
        String merchant = UUID.randomUUID().toString();
        String recommender = UUID.randomUUID().toString();
        String eng = UUID.randomUUID().toString();
        String id = openBy(merchant, "merchant", eng);
        stubParty(eng, recommender, "recommender");
        // 被告答辩 caption 进 claims_summary
        client().post().uri("/api/trust/disputes/" + id + "/evidence")
                .header("X-Grassland-Identity", sign(recommender, "recommender", null, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("phase", "answer",
                        "items", java.util.List.of(Map.of("kind", "text", "contentRef",
                                "履约数据已达标的完整说明", "caption", "验收记录完整，履约达标"))))
                .exchange().expectStatus().isCreated();
        toDecided(id);
        toAppealedByHttp(merchant, MARKETPLACE_ORG, id);
        client().post().uri("/api/trust/disputes/" + id + "/final-decision")
                .header("X-Grassland-Identity", signCs(UUID.randomUUID().toString(), Instant.now()))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("action", "maintain", "decision", "for_merchant"))
                .exchange().expectStatus().isOk();

        // 登录即可读（无 org 限定：与当事 org 无关的第三方账号）
        String listBody = client().get().uri("/api/trust/precedents")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender", null, "basic"))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        String body = listBody;
        assertThat(body).contains("\"focus\"").contains("履约争议");
        // 构造性脱敏：响应不含账号/组织/金额
        assertThat(body).doesNotContain(MARKETPLACE_ORG).doesNotContain(merchant).doesNotContain(recommender);
        // 详情
        String precedentId = db.sql("SELECT id::text AS id FROM precedent_case WHERE dispute_id = CAST(:id AS uuid)")
                .bind("id", id).map(r -> r.get("id", String.class)).one().block();
        client().get().uri("/api/trust/precedents/" + precedentId)
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "merchant", null, "basic"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.finalVia").isEqualTo("cs");
        assertThat(precedentCount(id)).isEqualTo(1);
    }

    @Test
    void precedentRequiresLogin() {
        client().get().uri("/api/trust/precedents").exchange().expectStatus().isUnauthorized();
    }

    // ---------- helpers ----------

    private String openCsDirect(String merchant) {
        String eng = UUID.randomUUID().toString();
        String body = client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", eng, "channel", "cs_direct"))
                .exchange().expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"channel\":\"cs_direct\"");
        return disputes.findActiveByEngagementRef(eng).block().id();
    }

    /** 指定角色开启争议（merchant/recommender 皆可当原告）。 */
    private String openBy(String accountId, String role, String eng) {
        String body = client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(accountId, role, MARKETPLACE_ORG, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("engagementRef", eng))
                .exchange().expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        return disputes.findActiveByEngagementRef(eng).block().id();
    }

    /** 被诉方 authorizer 复验桩（answer/rebuttal 写路径用）。 */
    private void stubParty(String eng, String accountId, String identity) {
        when(authorizer.authorize(eng, accountId, identity)).thenReturn(Mono.just(
                new MarketplaceEngagementAuthorizationClient.Authorization(eng, MARKETPLACE_ORG, accountId, false)));
    }

    private void adjudicate(String merchant, String org, String id) {
        client().post().uri("/api/trust/disputes/" + id + "/adjudicate")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isAccepted();
    }

    private void toDecided(String id) {
        disputes.startAdjudication(id, disputes.findById(id).block().round() == 0
                ? 1 : disputes.findById(id).block().round()).block();
        disputes.recordDecision(id, "for_merchant").block();
    }

    private void toAppealedByHttp(String merchant, String org, String id) {
        client().post().uri("/api/trust/disputes/" + id + "/appeal")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("note", "不服"))
                .exchange().expectStatus().isOk();
    }

    private void toEscalated(String id) {
        disputes.markEscalated(id).block();
    }

    private java.util.List<String> panelJudges(String disputeId, int round) {
        return db.sql("SELECT judge_account_id::text AS a FROM dispute_panel_assignment"
                        + " WHERE dispute_id = CAST(:id AS uuid) AND round = :round")
                .bind("id", disputeId).bind("round", round)
                .map(r -> r.get("a", String.class)).all().collectList().block();
    }

    private void seedJudges(int count) {
        for (int i = 0; i < count; i++) {
            seedJudge(UUID.randomUUID().toString());
        }
    }

    private void seedJudge(String accountId) {
        db.sql("INSERT INTO judge(id, account_id, organization_id, eligibility_tier, active,"
                        + " ops_admitted, ops_admitted_at, ops_admitted_by)"
                + " VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), NULL, 5, true, true, now(), CAST(:actor AS uuid))")
                .bind("id", UUID.randomUUID().toString()).bind("acct", accountId)
                .bind("actor", UUID.randomUUID().toString())
                .then().block();
    }

    private long precedentCount(String disputeId) {
        Long c = db.sql("SELECT COUNT(*)::bigint AS c FROM precedent_case WHERE dispute_id = CAST(:id AS uuid)")
                .bind("id", disputeId).map(r -> r.get("c", Long.class)).one().block();
        return c == null ? 0 : c;
    }

    private String outboxPayloadField(String eventType, String disputeId, String field) {
        return db.sql("SELECT payload->>'" + field + "' AS v FROM trust_outbox"
                        + " WHERE event_type = :et AND payload->>'disputeId' = :id")
                .bind("et", eventType).bind("id", disputeId)
                .map(r -> r.get("v", String.class)).one().block();
    }

    private long outboxCount(String eventType, String disputeId) {
        Integer c = db.sql("SELECT COUNT(*)::int AS c FROM trust_outbox"
                        + " WHERE event_type = :et AND payload->>'disputeId' = :id")
                .bind("et", eventType).bind("id", disputeId)
                .map(r -> r.get("c", Integer.class)).one().block();
        return c == null ? 0L : c.longValue();
    }

    private String signCs(String accountId, Instant reauthenticatedAt) {
        Instant now = Instant.now();
        return com.grassland.identity.assertion.TestAssertionHelper
                .userSigner("edge-bff", "grassland-trust").sign(new com.grassland.identity.assertion.IdentityAssertion(
                        accountId, null, "sid-" + accountId, null, null,
                        "cookie-session", "level2", reauthenticatedAt, "r", "t",
                        "grassland-trust", now, now.plusSeconds(60), null, null, "customer_service"));
    }
}
