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
                    + " j.ops_admitted, j.version, j.ops_admitted_at, j.ops_admitted_by::text, j.created_at";
    private static final String JUDGE_RETURNING =
            "id::text, account_id::text, organization_id::text, eligibility_tier, active,"
                    + " ops_admitted, version, ops_admitted_at, ops_admitted_by::text, created_at";

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
        if (eligibilityTier != 5) {
            return Mono.error(new IllegalArgumentException("审判官报名仅接受 Lv5 资格"));
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
                  AND (j.organization_id IS NULL OR j.organization_id <> CAST(:org AS uuid))
                  AND NOT EXISTS (
                      SELECT 1 FROM judge_conflict jc
                      WHERE jc.judge_id = j.id AND jc.organization_id = CAST(:org AS uuid))
                ORDER BY random()
                """.formatted(JUDGE_COLS))
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
        if (judgeAccountIds == null || judgeAccountIds.isEmpty()) {
            return Mono.just(0);
        }
        Mono<Integer> total = Mono.just(0);
        List<String> deterministicAccounts = judgeAccountIds.stream().distinct().sorted().toList();
        for (String accountId : deterministicAccounts) {
            total = total.flatMap(sum -> insertPanelMember(disputeId, round, accountId).map(n -> sum + n));
        }
        return total;
    }

    private Mono<Integer> insertPanelMember(String disputeId, int round, String judgeAccountId) {
        return db.sql("""
                INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id)
                SELECT CAST(:d AS uuid), :round, j.account_id
                FROM judge j
                JOIN dispute_case d ON d.id = CAST(:d AS uuid)
                WHERE j.account_id = CAST(:j AS uuid)
                  AND j.active = true
                  AND j.ops_admitted = true
                  AND j.eligibility_tier >= 5
                  AND (j.organization_id IS NULL OR j.organization_id <> d.organization_id)
                  AND NOT EXISTS (
                      SELECT 1
                      FROM judge_conflict conflict
                      WHERE conflict.judge_id = j.id
                        AND conflict.organization_id = d.organization_id)
                ON CONFLICT (dispute_id, round, judge_account_id) DO NOTHING
                """)
                .bind("d", disputeId).bind("round", round).bind("j", judgeAccountId)
                .fetch().rowsUpdated().map(Long::intValue).defaultIfEmpty(0);
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
                toInstant(row.get("created_at", OffsetDateTime.class)));
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
