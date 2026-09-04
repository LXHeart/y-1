package com.grassland.trust.judge;

import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 审判官池 + 面板分配 + 投票数据访问（草场 Epic 6 Slice 6C / HLD §5.5）。表由 Flyway V3 建。
 *
 * <p>抽面板（{@link #streamEligibleCandidates}）：流式读取 active + ops_admitted 且
 * {@code eligibility_tier >= minTier} 的审判官，排除与争议组织利益冲突者
 * （同 {@code organization_id} 或显式 {@code judge_conflict}），随机排序供上层逐个复验。
 *
 * <p>分配（{@link #assignPanel}）/ 投票（{@link #recordVote}）均 <b>幂等</b>：UNIQUE 约束 + ON CONFLICT DO NOTHING，
 * Phase C workflow activity 重试不会重复写。{@link #tallyVotes} 读 {@code dispute_vote} 按 choice 聚合计票，
 * 多数决阈值见 {@link VoteTally}。
 *
 * <p>本仓库在 workflow activity 线程（非 workflow 线程）执行，{@code ORDER BY random()} 等非确定性安全
 * （Temporal 确定性铁律只约束 workflow 主体，不约束 activity）。
 */
@Component
public class JudgeRepository {

    private static final String JUDGE_COLS =
            "j.id::text, j.account_id::text, j.organization_id::text, j.eligibility_tier, j.active,"
                    + " j.ops_admitted, j.version, j.ops_admitted_at, j.ops_admitted_by::text, j.created_at,"
                    + " j.exam_passed_at, j.admission_level, j.probation_since, j.suspended_until, j.suspension_reason";
    private static final String JUDGE_RETURNING =
            "id::text, account_id::text, organization_id::text, eligibility_tier, active,"
                    + " ops_admitted, version, ops_admitted_at, ops_admitted_by::text, created_at,"
                    + " exam_passed_at, admission_level, probation_since, suspended_until, suspension_reason";

    /**
     * 任务书 #74 卡 E（V14 触发器同口径）：可被抽中的资格谓词——Lv5 直入，或 Lv4+考试及格（见习通道），
     * 且未处于挂起期。
     */
    public static final String DRAWABLE_PREDICATE =
            "(j.eligibility_tier >= 5 OR (j.eligibility_tier >= 4 AND j.exam_passed_at IS NOT NULL)) "
                    + "AND (j.suspended_until IS NULL OR j.suspended_until < now())";

    private final DatabaseClient db;

    public JudgeRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 报名入池（幂等）：{@code UNIQUE(account_id)} 冲突 → 复活并返回既有行（退池后可再报名）。
     * GL-P2-TRUST-001：优先使用 {@link #enrollWithTier} 从 marketplace 获取声誉等级；
     * 本方法保留为兼容入口，安全默认 tier=5；HTTP 边界仍须先验证 marketplace 的资格结果。
     */
    public Mono<Judge> enroll(String accountId, String organizationId) {
        return enrollWithTier(accountId, organizationId, 5);
    }

    /**
     * 报名入池（指定声誉等级）：GL-P2-TRUST-001 reputation-based judge eligibility。
     *
     * <p>调用方已从 marketplace 验证有效 Lv5。active（用户参选意愿）与 ops_admitted（运营准入）正交：
     * 重复报名或退池后重报仅恢复 active，不得静默撤销或授予运营准入。
     *
     * <p>幂等：{@code UNIQUE(account_id)} 冲突 → 复活并更新 tier（允许声誉为动态值）。
     */
    public Mono<Judge> enrollWithTier(String accountId, String organizationId, int eligibilityTier) {
        // 任务书 #74 卡 E（D4）：Lv5 直入 full；Lv4 须先过准入考试（exam_passed_at）才有见习资格——
        // 考试在 JudgeExamService 单独把关，此处只放宽报名到 Lv4（Lv4 未考试仍过不了 V14 触发器抽签门）。
        if (eligibilityTier != 5 && eligibilityTier != 4) {
            return Mono.error(new IllegalArgumentException("审判官报名仅接受 Lv4/Lv5 资格"));
        }
        var spec = db.sql("""
                INSERT INTO judge(id, account_id, organization_id, eligibility_tier, active)
                VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), CAST(:org AS uuid), :tier, true)
                ON CONFLICT (account_id) DO UPDATE
                    SET active = true,
                        organization_id = EXCLUDED.organization_id,
                        eligibility_tier = :tier
                RETURNING %s
                """.formatted(JUDGE_RETURNING))
                .bind("id", UUID.randomUUID().toString()).bind("acct", accountId).bind("tier", eligibilityTier);
        spec = (organizationId == null || organizationId.isBlank())
                ? spec.bindNull("org", String.class)
                : spec.bind("org", organizationId);
        return spec.map(JudgeRepository::mapJudge).one();
    }

    /** 查本人审判官记录（含已退池的 active=false 行）。无 → empty。 */
    public Mono<Judge> findByAccountId(String accountId) {
        return db.sql("SELECT " + JUDGE_COLS + " FROM judge j WHERE j.account_id = CAST(:acct AS uuid)")
                .bind("acct", accountId)
                .map(JudgeRepository::mapJudge).one();
    }

    /** 后台列表：精确账号搜索或按 created_at + id 做稳定的 keyset 分页。 */
    public Flux<Judge> listForAdmin(int limit, Instant beforeCreatedAt, String beforeId,
                                    String accountId) {
        StringBuilder sql = new StringBuilder("SELECT ").append(JUDGE_COLS).append(" FROM judge j");
        if (accountId != null) {
            sql.append(" WHERE j.account_id = CAST(:accountId AS uuid)");
        } else if (beforeCreatedAt != null && beforeId != null) {
            sql.append(" WHERE (j.created_at, j.id) < (:beforeCreatedAt, CAST(:beforeId AS uuid))");
        }
        sql.append(" ORDER BY j.created_at DESC, j.id DESC LIMIT :limit");
        var spec = db.sql(sql.toString()).bind("limit", limit);
        if (accountId != null) {
            spec = spec.bind("accountId", accountId);
        } else if (beforeCreatedAt != null && beforeId != null) {
            spec = spec.bind("beforeCreatedAt", OffsetDateTime.ofInstant(
                    beforeCreatedAt, java.time.ZoneOffset.UTC)).bind("beforeId", beforeId);
        }
        return spec.map(JudgeRepository::mapJudge).all();
    }

    /** 乐观锁更新运营准入；版本不匹配或目标状态未变化均返回 empty。 */
    public Mono<Judge> updateAdmission(String accountId, boolean admitted, long expectedVersion,
                                       String admittedBy) {
        var spec = db.sql("""
                UPDATE judge
                SET ops_admitted = :admitted,
                    ops_admitted_at = CASE WHEN :admitted THEN now() ELSE NULL END,
                    ops_admitted_by = CASE WHEN :admitted THEN CAST(:actor AS uuid) ELSE NULL END,
                    version = version + 1
                WHERE account_id = CAST(:acct AS uuid)
                  AND version = :expected
                  AND ops_admitted <> :admitted
                RETURNING %s
                """.formatted(JUDGE_RETURNING))
                .bind("admitted", admitted)
                .bind("acct", accountId)
                .bind("expected", expectedVersion);
        spec = admitted ? spec.bind("actor", admittedBy) : spec.bindNull("actor", String.class);
        return spec.map(JudgeRepository::mapJudge).one();
    }

    /** 退池（软删：active=false，保留历史面板/投票的外键完整性）。0 行（未入池）→ empty。 */
    public Mono<Judge> deactivate(String accountId) {
        return db.sql("""
                UPDATE judge SET active = false
                WHERE account_id = CAST(:acct AS uuid) AND active = true
                RETURNING %s
                """.formatted(JUDGE_RETURNING))
                .bind("acct", accountId)
                .map(JudgeRepository::mapJudge).one();
    }

    /**
     * 流式读取本地符合资格且与争议组织无冲突的全部候选（随机顺序）。
     * 不在 SQL 层预设候选上限，避免前段远端复验失败时遗漏后续有效候选。
     */
    public Flux<Judge> streamEligibleCandidates(int minTier, String disputeOrgId) {
        return db.sql("""
                SELECT %s
                FROM judge j
                WHERE j.active = true
                  AND j.ops_admitted = true
                  AND j.eligibility_tier >= :minTier
                  AND %s
                  AND (j.organization_id IS NULL OR j.organization_id <> CAST(:org AS uuid))
                  AND NOT EXISTS (
                      SELECT 1 FROM judge_conflict jc
                      WHERE jc.judge_id = j.id AND jc.organization_id = CAST(:org AS uuid))
                ORDER BY random()
                """.formatted(JUDGE_COLS, DRAWABLE_PREDICATE))
                .bind("minTier", minTier).bind("org", disputeOrgId)
                .map(JudgeRepository::mapJudge).all();
    }

    /** 兼容仓储级抽签调用；业务面板组建使用 {@link #streamEligibleCandidates} 完成远端复验。 */
    public Flux<Judge> drawEligiblePool(int minTier, String disputeOrgId, int size) {
        return streamEligibleCandidates(minTier, disputeOrgId).take(size);
    }

    /**
     * 条件分配面板：仅插入提交时仍 active、已运营准入且 tier>=5 的审判官。
     * 调用方必须校验返回行数与目标面板人数完全一致，并在同一事务内回滚状态、分配和 outbox。
     */
    public Mono<Integer> assignPanel(String disputeId, int round, List<String> judgeAccountIds) {
        return assignPanel(disputeId, round, judgeAccountIds, java.util.Set.of());
    }

    /**
     * 条件分配面板（可带卡 D 熟手标记）：仅插入提交时仍 active、已运营准入且满足 V14 资格谓词的审判官。
     * {@code matchedPlatformAccounts} 中的成员写 matched_platform=true（涉案平台完成 ≥3 任务的熟手席）。
     * 调用方必须校验返回行数与目标面板人数完全一致，并在同一事务内回滚状态、分配和 outbox。
     */
    public Mono<Integer> assignPanel(String disputeId, int round, List<String> judgeAccountIds,
                                     java.util.Set<String> matchedPlatformAccounts) {
        if (judgeAccountIds == null || judgeAccountIds.isEmpty()) {
            return Mono.just(0);
        }
        Mono<Integer> total = Mono.just(0);
        List<String> deterministicAccounts = judgeAccountIds.stream().distinct().sorted().toList();
        for (String accountId : deterministicAccounts) {
            boolean matched = matchedPlatformAccounts != null && matchedPlatformAccounts.contains(accountId);
            total = total.flatMap(sum -> insertPanelMember(disputeId, round, accountId, matched).map(n -> sum + n));
        }
        return total;
    }

    private Mono<Integer> insertPanelMember(String disputeId, int round, String judgeAccountId, boolean matched) {
        return db.sql("""
                INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id, matched_platform)
                SELECT CAST(:d AS uuid), :round, j.account_id, :matched
                FROM judge j
                JOIN dispute_case d ON d.id = CAST(:d AS uuid)
                WHERE j.account_id = CAST(:j AS uuid)
                  AND j.active = true
                  AND j.ops_admitted = true
                  AND %s
                  AND (j.organization_id IS NULL OR j.organization_id <> d.organization_id)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM judge_conflict conflict
                      WHERE conflict.judge_id = j.id
                        AND conflict.organization_id = d.organization_id)
                ON CONFLICT (dispute_id, round, judge_account_id) DO NOTHING
                """.formatted(DRAWABLE_PREDICATE))
                .bind("d", disputeId).bind("round", round).bind("j", judgeAccountId).bind("matched", matched)
                .fetch().rowsUpdated().map(Long::intValue).defaultIfEmpty(0);
    }

    /** 卡 D：本轮面板中熟手（matched_platform）席位数——快照 matchedPlatformCount 供硬配额核查。 */
    public Mono<Integer> countMatchedPanel(String disputeId, int round) {
        return db.sql("SELECT COUNT(*)::int AS c FROM dispute_panel_assignment"
                        + " WHERE dispute_id = CAST(:id AS uuid) AND round = :round AND matched_platform = true")
                .bind("id", disputeId).bind("round", round)
                .map(r -> r.get("c", Integer.class)).one().defaultIfEmpty(0);
    }

    /** 卡 E：本轮面板中见习（admission_level='probation'）席位数——快照 probationCount。 */
    public Mono<Integer> countPanelProbation(String disputeId, int round) {
        return db.sql("""
                SELECT COUNT(*)::int AS c
                FROM dispute_panel_assignment p JOIN judge j ON j.account_id = p.judge_account_id
                WHERE p.dispute_id = CAST(:id AS uuid) AND p.round = :round AND j.admission_level = 'probation'
                """)
                .bind("id", disputeId).bind("round", round)
                .map(r -> r.get("c", Integer.class)).one().defaultIfEmpty(0);
    }

    /** 卡 E：该审判官去重（dispute, round）实际投票轮数——见习转正口径（v1 不考方向/低质标记）。 */
    public Mono<Integer> countDistinctVotingRounds(String judgeAccountId) {
        return db.sql("SELECT COUNT(DISTINCT (dispute_id, round))::int AS c FROM dispute_vote"
                        + " WHERE judge_account_id = CAST(:j AS uuid)")
                .bind("j", judgeAccountId)
                .map(r -> r.get("c", Integer.class)).one().defaultIfEmpty(0);
    }

    /** 卡 F：历轮全部面板成员（重抽面板排除集）。 */
    public Flux<String> listPanelAccountsAllRounds(String disputeId) {
        return db.sql("SELECT DISTINCT judge_account_id::text AS account_id FROM dispute_panel_assignment"
                        + " WHERE dispute_id = CAST(:id AS uuid)")
                .bind("id", disputeId)
                .map(row -> row.get("account_id", String.class)).all();
    }

    /** 卡 E：见习转正（admission_level probation→full）+ probation_since 清空；0 行（非见习）→ empty。 */
    public Mono<Judge> promote(String judgeAccountId) {
        return db.sql("""
                UPDATE judge SET admission_level = 'full', probation_since = NULL, version = version + 1
                WHERE account_id = CAST(:j AS uuid) AND admission_level = 'probation'
                RETURNING %s
                """.formatted(JUDGE_RETURNING))
                .bind("j", judgeAccountId)
                .map(JudgeRepository::mapJudge).one();
    }

    /** 卡 E：挂起/恢复（运营确认制）：suspend=true 落 suspended_until/reason，false 清空。 */
    public Mono<Judge> updateSuspension(String judgeAccountId, boolean suspend, java.time.Instant until,
                                        String reason) {
        var spec = db.sql("""
                UPDATE judge SET suspended_until = :until, suspension_reason = :reason, version = version + 1
                WHERE account_id = CAST(:j AS uuid)
                  AND ((:suspend AND (suspended_until IS NULL OR suspended_until < now()))
                       OR (NOT :suspend AND suspended_until IS NOT NULL))
                RETURNING %s
                """.formatted(JUDGE_RETURNING))
                .bind("j", judgeAccountId).bind("suspend", suspend);
        spec = suspend
                ? spec.bind("until", until == null ? null : OffsetDateTime.ofInstant(until, java.time.ZoneOffset.UTC))
                        .bind("reason", reason)
                : spec.bindNull("until", OffsetDateTime.class).bindNull("reason", String.class);
        return spec.map(JudgeRepository::mapJudge).one();
    }

    /** 卡 E：考试及格落值（幂等：已及格不改）；admission_level=probation + probation_since。 */
    public Mono<Judge> markExamPassed(String judgeAccountId) {
        return db.sql("""
                UPDATE judge SET exam_passed_at = COALESCE(exam_passed_at, now()),
                       admission_level = CASE WHEN admission_level = 'full' AND eligibility_tier < 5
                                              THEN 'probation' ELSE admission_level END,
                       probation_since = CASE WHEN admission_level = 'full' AND eligibility_tier < 5
                                              THEN now() ELSE probation_since END,
                       version = version + 1
                WHERE account_id = CAST(:j AS uuid) AND exam_passed_at IS NULL
                RETURNING %s
                """.formatted(JUDGE_RETURNING))
                .bind("j", judgeAccountId)
                .map(JudgeRepository::mapJudge).one();
    }

    /** 某争议某轮的面板（join judge，按分配时间序）。 */
    public Flux<Judge> findPanel(String disputeId, int round) {
        return db.sql("""
                SELECT %s
                FROM dispute_panel_assignment p JOIN judge j ON j.account_id = p.judge_account_id
                WHERE p.dispute_id = CAST(:id AS uuid) AND p.round = :round
                ORDER BY p.assigned_at
                """.formatted(JUDGE_COLS))
                .bind("id", disputeId).bind("round", round)
                .map(JudgeRepository::mapJudge).all();
    }

    public Mono<Integer> countPanel(String disputeId, int round) {
        return db.sql("SELECT COUNT(*)::int AS c FROM dispute_panel_assignment"
                + " WHERE dispute_id = CAST(:id AS uuid) AND round = :round")
                .bind("id", disputeId).bind("round", round)
                .map(r -> r.get("c", Integer.class)).one().defaultIfEmpty(0);
    }

    /**
     * 事务级面板互斥锁。同一 dispute+round 的计数、补位和最终校验必须持有该锁，防止并发组出超员面板。
     */
    public Mono<Void> lockPanel(String disputeId, int round) {
        String key = disputeId + ":" + round;
        return db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0::bigint)) AS locked")
                .bind("key", key)
                .map(row -> Boolean.TRUE).one().then();
    }

    /** 读取某轮已固化的面板账号；不依赖当前 judge 资格，供残缺面板安全补位。 */
    public Flux<String> findPanelAccountIds(String disputeId, int round) {
        return db.sql("SELECT judge_account_id::text AS account_id FROM dispute_panel_assignment"
                        + " WHERE dispute_id = CAST(:id AS uuid) AND round = :round"
                        + " ORDER BY judge_account_id")
                .bind("id", disputeId).bind("round", round)
                .map(row -> row.get("account_id", String.class)).all();
    }

    /** 该审判官是否在该争议该轮面板上（投票前资格自查）。 */
    public Mono<Boolean> isPanelMember(String disputeId, int round, String judgeAccountId) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM dispute_panel_assignment"
                + " WHERE dispute_id = CAST(:id AS uuid) AND round = :round"
                + " AND judge_account_id = CAST(:j AS uuid)) AS present")
                .bind("id", disputeId).bind("round", round).bind("j", judgeAccountId)
                .map(r -> r.get("present", Boolean.class)).one().defaultIfEmpty(false);
    }

    /** 记票（幂等）：UNIQUE(dispute_id, round, judge_account_id) 冲突 → empty（调用方据 findVote 返回既有票）。 */
    public Mono<JudgeVote> recordVote(String disputeId, int round, String judgeAccountId, String vote, String rationale) {
        var spec = db.sql("""
                INSERT INTO dispute_vote(dispute_id, round, judge_account_id, vote, rationale)
                SELECT CAST(:d AS uuid), :round, j.account_id, :vote, :rationale
                FROM judge j
                JOIN dispute_case d ON d.id = CAST(:d AS uuid)
                WHERE j.account_id = CAST(:j AS uuid)
                  AND j.active = true
                  AND j.ops_admitted = true
                  AND (j.suspended_until IS NULL OR j.suspended_until < now())
                  AND d.status = 'voting'
                  AND d.round = :round
                  AND d.appeal_state <> 'escalated'
                ON CONFLICT (dispute_id, round, judge_account_id) DO NOTHING
                RETURNING dispute_id::text, round, judge_account_id::text, vote, rationale, voted_at
                """)
                .bind("d", disputeId).bind("round", round).bind("j", judgeAccountId).bind("vote", vote);
        spec = (rationale == null || rationale.isBlank()) ? spec.bindNull("rationale", String.class) : spec.bind("rationale", rationale);
        return spec.map(JudgeRepository::mapVote).one();
    }

    public Mono<JudgeVote> findVote(String disputeId, int round, String judgeAccountId) {
        return db.sql("SELECT dispute_id::text, round, judge_account_id::text, vote, rationale, voted_at"
                + " FROM dispute_vote"
                + " WHERE dispute_id = CAST(:d AS uuid) AND round = :round AND judge_account_id = CAST(:j AS uuid)")
                .bind("d", disputeId).bind("round", round).bind("j", judgeAccountId)
                .map(JudgeRepository::mapVote).one();
    }

    /**
     * 任务书 #31 / ADR-D15 D2：该轮**实际投出**（含弃权）的审判官账号——奖励对象。
     * 早结论未投票者不在 dispute_vote 中，天然无奖励；客服终审轮无投票行，同样天然不发。
     */
    public Flux<String> findVoterAccountIds(String disputeId, int round) {
        return db.sql("""
                        SELECT DISTINCT judge_account_id::text FROM dispute_vote
                        WHERE dispute_id = CAST(:d AS uuid) AND round = :round
                        ORDER BY judge_account_id::text
                        """)
                .bind("d", disputeId).bind("round", round)
                .map(r -> r.get(0, String.class)).all();
    }

    /**
     * 任务书 #74 卡 G：某轮每票理由（脱敏摘要原料；<b>不含</b> judge 账号——判例聚合只取 rationale）。
     */
    public Flux<String> listVoteRationales(String disputeId, int round) {
        return db.sql("""
                SELECT COALESCE(rationale, '') AS rationale FROM dispute_vote
                WHERE dispute_id = CAST(:d AS uuid) AND round = :round
                ORDER BY voted_at, judge_account_id
                """)
                .bind("d", disputeId).bind("round", round)
                .map(r -> r.get("rationale", String.class)).all();
    }

    /** 计票：按 choice 聚合 + 实际面板人数（多数决阈值见 {@link VoteTally}）。无投票 → 全 0。 */
    public Mono<VoteTally> tallyVotes(String disputeId, int round) {
        return db.sql("""
                SELECT
                  COUNT(*) FILTER (WHERE vote = 'for_merchant')::int AS fm,
                  COUNT(*) FILTER (WHERE vote = 'for_recommender')::int AS fr,
                  COUNT(*) FILTER (WHERE vote = 'abstain')::int AS ab,
                  (SELECT COUNT(*)::int FROM dispute_panel_assignment
                    WHERE dispute_id = CAST(:id AS uuid) AND round = :round) AS panel_size
                FROM dispute_vote WHERE dispute_id = CAST(:id AS uuid) AND round = :round
                """)
                .bind("id", disputeId).bind("round", round)
                .map(r -> new VoteTally(
                        nvl(r.get("fm", Integer.class)),
                        nvl(r.get("fr", Integer.class)),
                        nvl(r.get("ab", Integer.class)),
                        nvl(r.get("panel_size", Integer.class))))
                .one()
                .defaultIfEmpty(new VoteTally(0, 0, 0, 0));
    }

    private static int nvl(Integer v) {
        return v == null ? 0 : v;
    }

    private static Judge mapJudge(Readable row) {
        return new Judge(
                row.get("id", String.class),
                row.get("account_id", String.class),
                row.get("organization_id", String.class),
                row.get("eligibility_tier", Integer.class),
                row.get("active", Boolean.class),
                row.get("ops_admitted", Boolean.class),
                row.get("version", Long.class),
                toInstant(row.get("ops_admitted_at", OffsetDateTime.class)),
                row.get("ops_admitted_by", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("exam_passed_at", OffsetDateTime.class)),
                row.get("admission_level", String.class),
                toInstant(row.get("probation_since", OffsetDateTime.class)),
                toInstant(row.get("suspended_until", OffsetDateTime.class)),
                row.get("suspension_reason", String.class));
    }

    private static JudgeVote mapVote(Readable row) {
        return new JudgeVote(
                row.get("dispute_id", String.class),
                row.get("round", Integer.class),
                row.get("judge_account_id", String.class),
                row.get("vote", String.class),
                row.get("rationale", String.class),
                toInstant(row.get("voted_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
