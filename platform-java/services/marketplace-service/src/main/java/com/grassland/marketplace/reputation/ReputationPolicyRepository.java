package com.grassland.marketplace.reputation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Readable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 等级策略持久化；所有动态值均通过 R2DBC bind 传入。 */
@Component
public class ReputationPolicyRepository {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final String CURRENT_POLICY_SQL = """
            SELECT p.version, p.updated_at,
                   r.level_number::int AS level_number, r.level_code, r.title, r.min_completed,
                   r.min_completion_rate, r.min_average_score, r.invite_only, r.judge_eligible,
                   r.task_priority_weight, r.settlement_delay_days, r.commission_bonus_bps,
                   r.ai_quota_multiplier_bps, r.premium_support, r.benefits::text AS benefits_json
            FROM reputation_policy p CROSS JOIN reputation_level_rule r
            WHERE p.id = 1 ORDER BY r.level_number
            """;

    private final DatabaseClient db;
    private final ObjectMapper objectMapper;

    public ReputationPolicyRepository(DatabaseClient db, ObjectMapper objectMapper) {
        this.db = db;
        this.objectMapper = objectMapper;
    }

    public Mono<ReputationPolicy> findCurrent() {
        return readCurrent(CURRENT_POLICY_SQL);
    }

    /** 锁住单例策略头；所有策略写入都经由该行串行化。 */
    public Mono<ReputationPolicy> findCurrentForUpdate() {
        return readCurrent(CURRENT_POLICY_SQL + " FOR UPDATE OF p");
    }

    private Mono<ReputationPolicy> readCurrent(String sql) {
        return db.sql(sql).map(row -> new PolicyRow(longOf(row, "version"),
                        toInstant(row.get("updated_at", OffsetDateTime.class)), mapRule(row)))
                .all().collectList()
                .filter(rows -> !rows.isEmpty())
                .map(rows -> new ReputationPolicy(rows.getFirst().version(),
                        rows.stream().map(PolicyRow::rule).toList(), rows.getFirst().updatedAt()))
                .switchIfEmpty(Mono.error(new IllegalStateException("reputation policy 未初始化")));
    }

    /** 抢占策略版本；空结果表示 expectedVersion 已过期。 */
    public Mono<Long> advanceVersion(long expectedVersion, String actorAccountId) {
        return db.sql("""
                UPDATE reputation_policy
                SET version = version + 1, updated_by = CAST(:actor AS uuid), updated_at = now()
                WHERE id = 1 AND version = :expected
                RETURNING version
                """).bind("actor", actorAccountId).bind("expected", expectedVersion)
                .map(row -> longOf(row, "version")).one();
    }

    public Mono<Void> updateAll(List<ReputationLevelRule> rules) {
        return Flux.fromIterable(rules).concatMap(this::updateRule).then();
    }

    private Mono<Void> updateRule(ReputationLevelRule rule) {
        GenericExecuteSpec spec = db.sql("""
                UPDATE reputation_level_rule
                SET level_code = :code, title = :title, min_completed = :completed,
                    min_completion_rate = :rate, min_average_score = :score,
                    invite_only = :inviteOnly, judge_eligible = :judgeEligible,
                    task_priority_weight = :priorityWeight,
                    settlement_delay_days = :settlementDays,
                    commission_bonus_bps = :commissionBps,
                    ai_quota_multiplier_bps = :aiQuotaBps,
                    premium_support = :premiumSupport,
                    benefits = CAST(:benefits AS jsonb)
                WHERE level_number = :number
                """).bind("code", rule.level()).bind("title", rule.title())
                .bind("completed", rule.minCompleted()).bind("rate", rule.minCompletionRate())
                .bind("inviteOnly", rule.inviteOnly()).bind("judgeEligible", rule.judgeEligible())
                .bind("priorityWeight", rule.taskPriorityWeight())
                .bind("settlementDays", rule.settlementDelayDays())
                .bind("commissionBps", rule.commissionBonusBps())
                .bind("aiQuotaBps", rule.aiQuotaMultiplierBps())
                .bind("premiumSupport", rule.premiumSupport())
                .bind("benefits", writeBenefits(rule.benefits()))
                .bind("number", rule.levelNumber());
        spec = rule.minAverageScore() == null
                ? spec.bindNull("score", Double.class)
                : spec.bind("score", rule.minAverageScore());
        return spec.fetch().rowsUpdated()
                .filter(count -> count == 1)
                .switchIfEmpty(Mono.error(new IllegalStateException("等级策略行缺失")))
                .then();
    }

    private ReputationLevelRule mapRule(Readable row) {
        return new ReputationLevelRule(
                intOf(row, "level_number"), row.get("level_code", String.class),
                row.get("title", String.class), intOf(row, "min_completed"),
                doubleOf(row, "min_completion_rate"), nullableDouble(row, "min_average_score"),
                boolOf(row, "invite_only"), boolOf(row, "judge_eligible"),
                intOf(row, "task_priority_weight"), intOf(row, "settlement_delay_days"),
                intOf(row, "commission_bonus_bps"), intOf(row, "ai_quota_multiplier_bps"),
                boolOf(row, "premium_support"), readBenefits(row.get("benefits_json", String.class)));
    }

    private List<String> readBenefits(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("等级权益配置损坏", error);
        }
    }

    private String writeBenefits(List<String> benefits) {
        try {
            return objectMapper.writeValueAsString(benefits);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("benefits 无法序列化", error);
        }
    }

    private static int intOf(Readable row, String column) {
        Integer value = row.get(column, Integer.class);
        return value == null ? 0 : value.intValue();
    }

    private static long longOf(Readable row, String column) {
        Long value = row.get(column, Long.class);
        return value == null ? 0 : value.longValue();
    }

    private static boolean boolOf(Readable row, String column) {
        return Boolean.TRUE.equals(row.get(column, Boolean.class));
    }

    private static double doubleOf(Readable row, String column) {
        BigDecimal value = row.get(column, BigDecimal.class);
        return value == null ? 0 : value.doubleValue();
    }

    private static Double nullableDouble(Readable row, String column) {
        BigDecimal value = row.get(column, BigDecimal.class);
        return value == null ? null : value.doubleValue();
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record PolicyRow(long version, Instant updatedAt, ReputationLevelRule rule) {}
}
