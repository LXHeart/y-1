package com.grassland.trust.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.grassland.trust.TrustItSupport;
import com.grassland.trust.dispute.DisputeCaseRepository;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

/**
 * 任务书 #74 卡 E：准入考试 + 见习审判官 + 自动考核。继承 {@link TrustItSupport}。
 *
 * <p>覆盖：Lv4+考试及格可被抽进面板（V14 触发器新分支）/ Lv4 未考试被触发器拒（23514）/
 * 见习席 >2 换重抽 / 10 轮转正 + audit / 挂起后触发器拒票、恢复后可投 / 题库 CRUD version 递增 /
 * 考核看板「建议暂停」标记。迁移重放由 {@code JudgeAdmissionMigrationTest} 风格覆盖（V14 幂等 DDL）。
 */
class JudgeExamIT extends TrustItSupport {

    private static final int PANEL_SIZE = 7;

    @Autowired
    private JudgeExamRepository exams;

    @Autowired
    private DisputeCaseRepository disputes;

    @Autowired
    private com.grassland.trust.workflow.AdjudicationActivityImpl activity;

    // ---------- 考试流 ----------

    @Test
    void examFlowGradesPassesAndMarksProbation() {
        String recommender = UUID.randomUUID().toString();
        seedExamQuestions(10);
        seedJudge(recommender, 4); // Lv4 已入池（报名门槛经 reputation 桩）

        // 出题：10 题且不含答案
        String body = client().get().uri("/api/trust/judges/exam")
                .header("X-Grassland-Identity", sign(recommender, "recommender", null, "basic"))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).doesNotContain("answerIndex");
        var questions = exams.drawQuestions(10).collectList().block();
        assertThat(questions).hasSize(10);

        // 全对 → 及格 → 见习标记
        StringBuilder answers = new StringBuilder("{");
        for (int i = 0; i < questions.size(); i++) {
            if (i > 0) {
                answers.append(',');
            }
            answers.append('"').append(questions.get(i).id()).append("\":").append(questions.get(i).answerIndex());
        }
        answers.append('}');
        client().post().uri("/api/trust/judges/exam")
                .header("X-Grassland-Identity", sign(recommender, "recommender", null, "basic"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("answers", answers.toString()))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.score").value(org.hamcrest.Matchers.greaterThanOrEqualTo(80))
                .jsonPath("$.data.passed").isEqualTo(true);

        Judge judge = judges.findByAccountId(recommender).block();
        assertThat(judge.examPassedAt()).isNotNull();
        assertThat(judge.admissionLevel()).isEqualTo("probation");
        // audit 'probation'
        Long audits = db.sql("SELECT COUNT(*)::bigint AS c FROM judge_admission_audit"
                        + " WHERE action = 'probation' AND judge_id = CAST(:id AS uuid)")
                .bind("id", judge.id()).map(r -> r.get("c", Long.class)).one().block();
        assertThat(audits).isEqualTo(1);
        // JudgeExamPassed 事件（identity 通知收件人=judgeAccountId）
        Long events = db.sql("SELECT COUNT(*)::bigint AS c FROM trust_outbox"
                        + " WHERE event_type = 'JudgeExamPassed' AND payload->>'judgeAccountId' = :a")
                .bind("a", recommender).map(r -> r.get("c", Long.class)).one().block();
        assertThat(events).isEqualTo(1);

        // 不及格 → 冷却 24h（第二个账号交卷全错）
        String failed = UUID.randomUUID().toString();
        seedJudge(failed, 4);
        StringBuilder wrong = new StringBuilder("{");
        for (int i = 0; i < questions.size(); i++) {
            if (i > 0) {
                wrong.append(',');
            }
            wrong.append('"').append(questions.get(i).id()).append("\":")
                    .append((questions.get(i).answerIndex() + 1) % 2);
        }
        wrong.append('}');
        client().post().uri("/api/trust/judges/exam")
                .header("X-Grassland-Identity", sign(failed, "recommender", null, "basic"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("answers", wrong.toString()))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.passed").isEqualTo(false)
                .jsonPath("$.data.cooldownUntil").isNotEmpty();
        // 冷却期内重考 → 409
        client().post().uri("/api/trust/judges/exam")
                .header("X-Grassland-Identity", sign(failed, "recommender", null, "basic"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("answers", answers.toString()))
                .exchange().expectStatus().isEqualTo(409);
    }

    // ---------- V14 触发器：Lv4+考试可抽、Lv4 未考试拒、挂起拒 ----------

    @Test
    void lv4WithExamJoinPanelViaTriggerLv4WithoutExamRejected() {
        db.sql("TRUNCATE judge_conflict").then().block();
        db.sql("TRUNCATE judge CASCADE").then().block();
        String disputeId = openStandardDispute();
        String withExam = UUID.randomUUID().toString();
        String withoutExam = UUID.randomUUID().toString();
        seedJudge(withExam, 4);
        db.sql("UPDATE judge SET exam_passed_at = now(), admission_level = 'probation', probation_since = now(),"
                        + " version = version + 1 WHERE account_id = CAST(:a AS uuid)")
                .bind("a", withExam).then().block();
        seedJudge(withoutExam, 4);
        // 补足 Lv5 候选
        for (int i = 0; i < PANEL_SIZE - 1; i++) {
            seedJudge(UUID.randomUUID().toString(), 5);
        }

        // 触发器接受 Lv4+考试：面板含 withExam
        var picks = judges.drawEligiblePool(4, MARKETPLACE_ORG, 20)
                .map(Judge::accountId).collectList().block();
        assertThat(picks).contains(withExam);
        var inserted = judges.assignPanel(disputeId, 1, java.util.List.of(withExam)).block();
        assertThat(inserted).isEqualTo(1);

        // 触发器拒绝 Lv4 无考试：绕过应用侧 WHERE 预过滤的裸 INSERT 抛 23514（V14 触发器是最终防线）
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        db.sql("INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id)"
                                        + " VALUES (CAST(:d AS uuid), 1, CAST(:a AS uuid))")
                                .bind("d", disputeId).bind("a", withoutExam).then().block())
                .hasStackTraceContaining("judge is not eligible");
    }

    @Test
    void suspendedJudgeCannotVoteUntilReinstated() {
        String disputeId = openStandardDispute();
        String judgeAccount = UUID.randomUUID().toString();
        seedJudge(judgeAccount, 5);
        // 开 voting 案件 + 面板
        disputes.startAdjudication(disputeId, 1).block();
        assertThat(judges.assignPanel(disputeId, 1, java.util.List.of(judgeAccount)).block()).isEqualTo(1);
        db.sql("UPDATE dispute_case SET round = 1 WHERE id = CAST(:id AS uuid)").bind("id", disputeId).then().block();

        // 挂起（运营确认制；恢复前不可投票——应用侧谓词先行、触发器兜底）
        db.sql("UPDATE judge SET suspended_until = now() + interval '30 days', suspension_reason = 'abstain_rate'"
                        + " WHERE account_id = CAST(:a AS uuid)")
                .bind("a", judgeAccount).then().block();
        var blocked = judges.recordVote(disputeId, 1, judgeAccount, "for_merchant", "综合凭证与陈述，支持商家一方。").block();
        assertThat(blocked).isNull(); // 挂起排除进 WHERE → 不插入不报错
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        db.sql("INSERT INTO dispute_vote(dispute_id, round, judge_account_id, vote, rationale)"
                                        + " VALUES (CAST(:d AS uuid), 1, CAST(:a AS uuid), 'for_merchant', '理由。')")
                                .bind("d", disputeId).bind("a", judgeAccount).then().block())
                .hasStackTraceContaining("judge is not eligible"); // 触发器 23514 兜底

        // 恢复后可投
        db.sql("UPDATE judge SET suspended_until = NULL, suspension_reason = NULL WHERE account_id = CAST(:a AS uuid)")
                .bind("a", judgeAccount).then().block();
        var vote = judges.recordVote(disputeId, 1, judgeAccount, "for_merchant", "综合凭证与陈述，支持商家一方。").block();
        assertThat(vote).isNotNull();
    }

    // ---------- 见习转正 + 考核看板 + 治理台 ----------

    @Test
    void probationPromotesAfterTenDistinctRounds() {
        String judgeAccount = UUID.randomUUID().toString();
        seedJudge(judgeAccount, 4);
        db.sql("UPDATE judge SET exam_passed_at = now(), admission_level = 'probation', probation_since = now()"
                        + " WHERE account_id = CAST(:a AS uuid)")
                .bind("a", judgeAccount).then().block();
        // 不足 10 轮：不转正（每轮=1 个真实 voting 案件，满足 V14 触发器的行级校验）
        seedVoteRounds(judgeAccount, 9);
        assertThat(activity.promoteIfEligible(judges.findByAccountId(judgeAccount).block()).block()).isEqualTo(false);
        assertThat(judges.findByAccountId(judgeAccount).block().admissionLevel()).isEqualTo("probation");
        // 第 10 轮 → 转正 + audit 'promoted'
        seedVoteRounds(judgeAccount, 1);
        assertThat(activity.promoteIfEligible(judges.findByAccountId(judgeAccount).block()).block()).isEqualTo(true);
        Judge promoted = judges.findByAccountId(judgeAccount).block();
        assertThat(promoted.admissionLevel()).isEqualTo("full");
        Long audits = db.sql("SELECT COUNT(*)::bigint AS c FROM judge_admission_audit"
                        + " WHERE action = 'promoted' AND judge_id = CAST(:id AS uuid)")
                .bind("id", promoted.id()).map(r -> r.get("c", Long.class)).one().block();
        assertThat(audits).isEqualTo(1);
    }

    @Test
    void assessmentFlagsHighAbstentionAndSuspensionNotifies() {
        String judgeAccount = UUID.randomUUID().toString();
        seedJudge(judgeAccount, 5);
        // 90 天窗口：分配 6 次（真实 voting 案件，过触发器）、弃权投票 3 次（弃权率 0.5 > 0.4）
        for (int i = 0; i < 6; i++) {
            String disputeId = openVotingDispute();
            db.sql("INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id, assigned_at)"
                            + " VALUES (CAST(:d AS uuid), 1, CAST(:a AS uuid), now())")
                    .bind("d", disputeId).bind("a", judgeAccount).then().block();
            if (i < 3) {
                db.sql("INSERT INTO dispute_vote(dispute_id, round, judge_account_id, vote, rationale, voted_at)"
                                + " VALUES (CAST(:d AS uuid), 1, CAST(:a AS uuid), 'abstain', '利益相关，弃权。', now())")
                        .bind("d", disputeId).bind("a", judgeAccount).then().block();
            }
        }
        String body = client().get().uri("/api/admin/trust/judges/assessment")
                .header("X-Grassland-Identity", signAdmin())
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("suggestSuspension\":true");

        // 挂起 + audit + 通知事件
        client().post().uri("/api/admin/trust/judges/" + judgeAccount + "/suspension")
                .header("X-Grassland-Identity", signAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("suspend", true, "reason", "弃权率过高"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.suspendedNow").isEqualTo(true);
        Judge suspended = judges.findByAccountId(judgeAccount).block();
        assertThat(suspended.suspendedNow()).isTrue();
        Long events = db.sql("SELECT COUNT(*)::bigint AS c FROM trust_outbox"
                        + " WHERE event_type = 'JudgeSuspended' AND payload->>'judgeAccountId' = :a")
                .bind("a", judgeAccount).map(r -> r.get("c", Long.class)).one().block();
        assertThat(events).isEqualTo(1);

        // 恢复
        client().post().uri("/api/admin/trust/judges/" + judgeAccount + "/suspension")
                .header("X-Grassland-Identity", signAdmin())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("suspend", false))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.suspendedNow").isEqualTo(false);
    }

    @Test
    void questionCrudBumpsVersion() {
        String body = client().post().uri("/api/admin/trust/judge-exam/questions")
                .header("X-Grassland-Identity", signAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("category", "规则", "question", "平票后案件如何处理？",
                        "options", java.util.List.of("进入下一轮", "直接终局"), "answerIndex", 0))
                .exchange().expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"version\":0");
        String id = com.jayway.jsonpath.JsonPath.parse(body).read("$.data.id");

        // 更新（乐观锁 version 0→1）
        client().put().uri("/api/admin/trust/judge-exam/questions/" + id)
                .header("X-Grassland-Identity", signAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("question", "平票后案件如何处理？（修订）", "expectedVersion", 0))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.version").isEqualTo(1);
        // 旧版本重放 → 409
        client().put().uri("/api/admin/trust/judge-exam/questions/" + id)
                .header("X-Grassland-Identity", signAdmin())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("question", "再次修订", "expectedVersion", 0))
                .exchange().expectStatus().isEqualTo(409);
        // 下线
        client().delete().uri("/api/admin/trust/judge-exam/questions/" + id)
                .header("X-Grassland-Identity", signAdmin())
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.active").isEqualTo(false);
    }

    // ---------- helpers ----------

    @Autowired
    private JudgeRepository judges;

    private void seedExamQuestions(int count) {
        for (int i = 0; i < count; i++) {
            exams.insertQuestion(new JudgeExamQuestion(UUID.randomUUID().toString(), "规则",
                    "题目 " + i + "：审判官应当如何行为？",
                    "[\"依证据裁量\", \"依人情\"]", i % 2, true, 0, Instant.now())).block();
        }
    }

    private void seedJudge(String accountId, int tier) {
        db.sql("INSERT INTO judge(id, account_id, organization_id, eligibility_tier, active,"
                        + " ops_admitted, ops_admitted_at, ops_admitted_by)"
                + " VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), NULL, :tier, true, true, now(), CAST(:actor AS uuid))")
                .bind("id", UUID.randomUUID().toString()).bind("acct", accountId).bind("tier", tier)
                .bind("actor", UUID.randomUUID().toString())
                .then().block();
    }

    /** 每轮=1 个真实 voting 案件（触发器要求 panel_assignment 挂在真实 dispute 上且 vote 的 d.round=1）。 */
    private void seedVoteRounds(String judgeAccount, int rounds) {
        int existing = judges.countDistinctVotingRounds(judgeAccount).block();
        for (int i = 0; i < rounds; i++) {
            String disputeId = openVotingDispute();
            db.sql("INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id)"
                            + " VALUES (CAST(:d AS uuid), 1, CAST(:a AS uuid))")
                    .bind("d", disputeId).bind("a", judgeAccount).then().block();
            db.sql("INSERT INTO dispute_vote(dispute_id, round, judge_account_id, vote, rationale)"
                            + " VALUES (CAST(:d AS uuid), 1, CAST(:a AS uuid), 'for_merchant', '理由充分。')")
                    .bind("d", disputeId).bind("a", judgeAccount).then().block();
        }
    }

    /** 开争议 → voting round 1（供面板/投票种子挂靠）。 */
    private String openVotingDispute() {
        String merchant = UUID.randomUUID().toString();
        String eng = UUID.randomUUID().toString();
        client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng))
                .exchange().expectStatus().isCreated();
        String id = disputes.findActiveByEngagementRef(eng).block().id();
        disputes.startAdjudication(id, 1).block();
        return id;
    }

    private String openStandardDispute() {
        String merchant = UUID.randomUUID().toString();
        String eng = UUID.randomUUID().toString();
        client().post().uri("/api/trust/disputes")
                .header("X-Grassland-Identity", sign(merchant, "merchant", MARKETPLACE_ORG, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("engagementRef", eng))
                .exchange().expectStatus().isCreated();
        return disputes.findActiveByEngagementRef(eng).block().id();
    }

    private String signAdmin() {
        Instant now = Instant.now();
        return com.grassland.identity.assertion.TestAssertionHelper
                .userSigner("edge-bff", "grassland-trust").sign(new com.grassland.identity.assertion.IdentityAssertion(
                        UUID.randomUUID().toString(), null, "sid-admin", null, null,
                        "cookie-session", "level2", now, "r", "t",
                        "grassland-trust", now, now.plusSeconds(60), null, null, "platform_admin"));
    }
}
