package com.grassland.trust.judge;

import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 审判官池 + 面板分配 + 投票数据访问（草场 Epic 6 Slice 6C / HLD §5.5）。表由 Flyway V3 建。
 *
 * <p>抽面板（{@link #drawEligiblePool}）：active 审判官 × {@code eligibility_tier >= minTier}，排除与争议组织利益冲突者
 * （同 {@code organization_id} 或显式 {@code judge_conflict}），随机排序取 {@code size} 名（公平抽签）。
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
            "j.id::text, j.account_id::text, j.organization_id::text, j.eligibility_tier, j.active, j.created_at";

    private final DatabaseClient db;

    public JudgeRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 抽符合资格且与争议组织无冲突的审判官池（随机），取 {@code size} 名。返回顺序即抽签顺序。 */
    public Flux<Judge> drawEligiblePool(int minTier, String disputeOrgId, int size) {
        return db.sql("""
                SELECT %s
                FROM judge j
                WHERE j.active = true
                  AND j.eligibility_tier >= :minTier
                  AND (j.organization_id IS NULL OR j.organization_id <> CAST(:org AS uuid))
                  AND NOT EXISTS (
                      SELECT 1 FROM judge_conflict jc
                      WHERE jc.judge_id = j.id AND jc.organization_id = CAST(:org AS uuid))
                ORDER BY random()
                LIMIT :size
                """.formatted(JUDGE_COLS))
                .bind("minTier", minTier).bind("org", disputeOrgId).bind("size", size)
                .map(JudgeRepository::mapJudge).all();
    }

    /** 分配面板（幂等）：逐官插入，ON CONFLICT 跳过已分配。返回新分配行数（0 = 全部已存在）。 */
    public Mono<Integer> assignPanel(String disputeId, int round, List<String> judgeAccountIds) {
        if (judgeAccountIds == null || judgeAccountIds.isEmpty()) {
            return Mono.just(0);
        }
        Mono<Integer> total = Mono.just(0);
        for (String accountId : judgeAccountIds) {
            total = total.flatMap(sum -> insertPanelMember(disputeId, round, accountId).map(n -> sum + n));
        }
        return total;
    }

    private Mono<Integer> insertPanelMember(String disputeId, int round, String judgeAccountId) {
        return db.sql("""
                INSERT INTO dispute_panel_assignment(dispute_id, round, judge_account_id)
                VALUES (CAST(:d AS uuid), :round, CAST(:j AS uuid))
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
                VALUES (CAST(:d AS uuid), :round, CAST(:j AS uuid), :vote, :rationale)
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
        Mono<Integer> panelSize = countPanel(disputeId, round);
        Mono<int[]> counts = db.sql("""
                SELECT
                  COUNT(*) FILTER (WHERE vote = 'for_merchant')::int AS fm,
                  COUNT(*) FILTER (WHERE vote = 'for_recommender')::int AS fr,
                  COUNT(*) FILTER (WHERE vote = 'abstain')::int AS ab
                FROM dispute_vote WHERE dispute_id = CAST(:id AS uuid) AND round = :round
                """)
                .bind("id", disputeId).bind("round", round)
                .map(r -> new int[]{
                        nvl(r.get("fm", Integer.class)), nvl(r.get("fr", Integer.class)), nvl(r.get("ab", Integer.class))})
                .one()
                .defaultIfEmpty(new int[]{0, 0, 0});
        return Mono.zip(panelSize, counts)
                .map(t -> new VoteTally(t.getT2()[0], t.getT2()[1], t.getT2()[2], t.getT1()));
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
