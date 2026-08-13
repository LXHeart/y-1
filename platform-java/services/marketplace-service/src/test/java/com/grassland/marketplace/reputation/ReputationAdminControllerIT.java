package com.grassland.marketplace.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/** V20 推荐官等级配置、Lv5 邀请与 trust 资格契约。 */
class ReputationAdminControllerIT extends MarketplaceItSupport {

    private static final String ADMIN = "10000000-0000-0000-0000-000000000001";

    @MockitoBean
    private ReputationRepository reputations;

    private long auditStartId;

    @BeforeEach
    void resetReputationAdministration() {
        when(reputations.statsOf(anyString())).thenReturn(Mono.just(ReputationStats.empty()));
        auditStartId = latestAuditId();
        db.sql("DELETE FROM reputation_lv5_admission").fetch().rowsUpdated().block();
        db.sql("""
                UPDATE reputation_policy SET version = 1, updated_by = NULL, updated_at = now()
                WHERE id = 1
                """).fetch().rowsUpdated().block();
        resetRule(1, "Lv1", "新手草友", 0, 0.0, null, false, false, List.of("基础任务"));
        resetRule(2, "Lv2", "活跃草友", 6, 0.80, null, false, false, List.of("更多任务"));
        resetRule(3, "Lv3", "优质草友", 21, 0.85, 4.0, false, false, List.of("优先推荐"));
        resetRule(4, "Lv4", "金牌草友", 51, 0.90, 4.5, false, false, List.of("专属任务"));
        resetRule(5, "Lv5", "草场达人", 100, 0.95, 4.8, true, true,
                List.of("审判官资格", "T+1 优先结算"));
    }

    @Test
    @DisplayName("等级配置只允许 PLATFORM_ADMIN 读取，匿名、普通用户和服务身份均拒绝")
    void configurationRequiresPlatformAdmin() {
        client().get().uri("/api/admin/reputation-config")
                .exchange().expectStatus().isUnauthorized();

        client().get().uri("/api/admin/reputation-config")
                .header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "customer_service"))
                .exchange().expectStatus().isForbidden();

        client().get().uri("/api/admin/reputation-config")
                .header("X-Grassland-Identity", signService("trust"))
                .exchange().expectStatus().isForbidden();

        client().get().uri("/api/admin/reputation-config")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.version").isEqualTo(1)
                .jsonPath("$.data.levels.length()").isEqualTo(5)
                .jsonPath("$.data.levels[4].level").isEqualTo("Lv5")
                .jsonPath("$.data.levels[4].inviteOnly").isEqualTo(true)
                .jsonPath("$.data.levels[4].judgeEligible").isEqualTo(true)
                .jsonPath("$.data.levels[4].settlementDelayDays").isEqualTo(1)
                .jsonPath("$.data.levels[4].commissionBonusBps").isEqualTo(1000)
                .jsonPath("$.data.levels[4].premiumSupport").isEqualTo(true)
                .jsonPath("$.data.levels[2].aiQuotaMultiplierBps").isEqualTo(15000)
                .jsonPath("$.data.levels[4].benefits[0]").isEqualTo("审判官资格");
    }

    @Test
    @DisplayName("管理员可用 expectedVersion 原子更新阈值与权益，过期版本返回 409 并写一条审计")
    void updateConfigurationUsesOptimisticLockAndAudit() {
        Map<String, Object> body = Map.of("expectedVersion", 1, "levels", validLevels(8));

        client().put().uri("/api/admin/reputation-config")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.version").isEqualTo(2)
                .jsonPath("$.data.levels[1].minCompleted").isEqualTo(8)
                .jsonPath("$.data.levels[3].benefits[0]").isEqualTo("专属活动");

        client().put().uri("/api/admin/reputation-config")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isEqualTo(409);

        Map<String, Object> audit = db.sql("""
                SELECT action,
                       (before_snapshot->>'version')::bigint AS before_version,
                       (after_snapshot->>'version')::bigint AS after_version,
                       jsonb_array_length(before_snapshot->'levels') AS before_levels,
                       jsonb_array_length(after_snapshot->'levels') AS after_levels,
                       (before_snapshot #>> '{levels,1,minCompleted}')::int AS before_lv2_completed,
                       (after_snapshot #>> '{levels,1,minCompleted}')::int AS after_lv2_completed
                FROM reputation_admin_audit
                WHERE id > :start AND action = 'policy_updated'
                """).bind("start", auditStartId)
                .map(row -> Map.<String, Object>of(
                        "action", row.get("action", String.class),
                        "beforeVersion", row.get("before_version", Long.class),
                        "afterVersion", row.get("after_version", Long.class),
                        "beforeLevels", row.get("before_levels", Integer.class),
                        "afterLevels", row.get("after_levels", Integer.class),
                        "beforeLv2", row.get("before_lv2_completed", Integer.class),
                        "afterLv2", row.get("after_lv2_completed", Integer.class)))
                .one().block();
        assertThat(audit).containsEntry("action", "policy_updated")
                .containsEntry("beforeVersion", 1L)
                .containsEntry("afterVersion", 2L)
                .containsEntry("beforeLevels", 5)
                .containsEntry("afterLevels", 5)
                .containsEntry("beforeLv2", 6)
                .containsEntry("afterLv2", 8);
    }

    @Test
    @DisplayName("配置拒绝重复等级、非单调阈值、越界数值及过长权益")
    void updateConfigurationValidatesWholePolicy() {
        client().put().uri("/api/admin/reputation-config")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("levels", validLevels(8)))
                .exchange().expectStatus().isBadRequest();

        List<Map<String, Object>> nonMonotonic = validLevels(8);
        nonMonotonic.set(2, rule(3, "Lv3", "优质草友", 7, 0.85, 4.0,
                false, false, List.of("优先推荐")));
        assertBadConfiguration(nonMonotonic);

        List<Map<String, Object>> duplicate = validLevels(8);
        duplicate.set(4, rule(4, "Lv4", "重复", 100, 0.95, 4.8,
                true, true, List.of("审判官资格")));
        assertBadConfiguration(duplicate);

        List<Map<String, Object>> invalidRate = validLevels(8);
        invalidRate.set(1, rule(2, "Lv2", "活跃草友", 8, 1.01, null,
                false, false, List.of("更多任务")));
        assertBadConfiguration(invalidRate);

        List<Map<String, Object>> longBenefit = validLevels(8);
        longBenefit.set(1, rule(2, "Lv2", "活跃草友", 8, 0.80, null,
                false, false, List.of("x".repeat(129))));
        assertBadConfiguration(longBenefit);

        List<Map<String, Object>> invalidMachineBenefit = validLevels(8);
        invalidMachineBenefit.get(3).put("commissionBonusBps", 10_001);
        assertBadConfiguration(invalidMachineBenefit);
    }

    @Test
    @DisplayName("配置中缺失任一 primitive 阈值或结构化权益字段均返回 400")
    void updateConfigurationRejectsMissingRequiredPrimitiveFields() {
        for (String field : List.of(
                "levelNumber", "minCompleted", "minCompletionRate", "inviteOnly",
                "judgeEligible", "taskPriorityWeight", "settlementDelayDays",
                "commissionBonusBps", "aiQuotaMultiplierBps", "premiumSupport")) {
            List<Map<String, Object>> levels = validLevels(8);
            levels.get(1).remove(field);
            assertBadConfiguration(levels);
        }
    }

    @Test
    @DisplayName("Lv5 邀请用版本锁更新；grant 后成为 Lv5，revoke 后立即回落")
    void lv5AdmissionRequiresMetricsAndControlsEffectiveLevel() {
        String accountId = UUID.randomUUID().toString();
        when(reputations.statsOf(accountId)).thenReturn(Mono.just(
                new ReputationStats(100, 100, 0, 0, 4, 10, 4.8, null)));

        client().put().uri("/api/admin/reputation/" + accountId + "/lv5-admission")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("admitted", true, "expectedVersion", 0, "note", "签约邀请"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.accountId").isEqualTo(accountId)
                .jsonPath("$.data.admitted").isEqualTo(true)
                .jsonPath("$.data.version").isEqualTo(1);

        client().get().uri("/api/admin/reputation/" + accountId)
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.effectiveLevel").isEqualTo("Lv5")
                .jsonPath("$.data.lv5Admitted").isEqualTo(true)
                .jsonPath("$.data.judgeEligible").isEqualTo(true)
                .jsonPath("$.data.admissionVersion").isEqualTo(1);

        client().get().uri("/internal/marketplace/reputation/" + accountId + "/level")
                .header("X-Grassland-Identity", signService("trust"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.effectiveLevel").isEqualTo("Lv5")
                .jsonPath("$.data.levelNumber").isEqualTo(5)
                .jsonPath("$.data.judgeEligible").isEqualTo(true)
                .jsonPath("$.data.policyVersion").isEqualTo(1);

        client().put().uri("/api/admin/reputation/" + accountId + "/lv5-admission")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("admitted", false, "expectedVersion", 0, "note", "过期写"))
                .exchange().expectStatus().isEqualTo(409);

        client().put().uri("/api/admin/reputation/" + accountId + "/lv5-admission")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("admitted", false, "expectedVersion", 1, "note", "解除签约"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.admitted").isEqualTo(false)
                .jsonPath("$.data.version").isEqualTo(2);

        client().get().uri("/api/admin/reputation/" + accountId)
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.calculatedLevel").isEqualTo("Lv5")
                .jsonPath("$.data.effectiveLevel").isEqualTo("Lv4")
                .jsonPath("$.data.judgeEligible").isEqualTo(false);

        client().get().uri("/internal/marketplace/reputation/" + accountId + "/level")
                .header("X-Grassland-Identity", signService("trust"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.effectiveLevel").isEqualTo("Lv4")
                .jsonPath("$.data.levelNumber").isEqualTo(4)
                .jsonPath("$.data.judgeEligible").isEqualTo(false);

        List<AdmissionAudit> audits = db.sql("""
                SELECT action,
                       (before_snapshot->>'admitted')::boolean AS before_admitted,
                       (before_snapshot->>'version')::bigint AS before_version,
                       (after_snapshot->>'admitted')::boolean AS after_admitted,
                       (after_snapshot->>'version')::bigint AS after_version
                FROM reputation_admin_audit
                WHERE id > :start AND target_account_id = CAST(:accountId AS uuid)
                ORDER BY id
                """).bind("start", auditStartId).bind("accountId", accountId)
                .map(row -> new AdmissionAudit(
                        row.get("action", String.class),
                        Boolean.TRUE.equals(row.get("before_admitted", Boolean.class)),
                        row.get("before_version", Long.class),
                        Boolean.TRUE.equals(row.get("after_admitted", Boolean.class)),
                        row.get("after_version", Long.class)))
                .all().collectList().block();
        assertThat(audits).containsExactly(
                new AdmissionAudit("lv5_granted", false, 0, true, 1),
                new AdmissionAudit("lv5_revoked", true, 1, false, 2));
    }

    @Test
    @DisplayName("零业绩账号不能被授予 Lv5，失败操作不写当前态和审计")
    void lv5AdmissionRejectsAccountBelowRequiredMetrics() {
        String accountId = UUID.randomUUID().toString();

        client().put().uri("/api/admin/reputation/" + accountId + "/lv5-admission")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("admitted", true, "expectedVersion", 0, "note", "错误邀请"))
                .exchange().expectStatus().isEqualTo(409);

        Integer admissions = db.sql("SELECT COUNT(*)::int AS c FROM reputation_lv5_admission")
                .map(row -> row.get("c", Integer.class)).one().block();
        Integer audits = auditCountAfterStart();
        assertThat(admissions).isZero();
        assertThat(audits).isZero();
    }

    @Test
    @DisplayName("reputation 管理审计在数据库层拒绝 UPDATE 和 DELETE")
    void reputationAdminAuditIsDatabaseImmutable() {
        client().put().uri("/api/admin/reputation-config")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1, "levels", validLevels(8)))
                .exchange().expectStatus().isOk();
        long auditId = latestAuditId();

        assertThatThrownBy(() -> db.sql("UPDATE reputation_admin_audit SET note = 'tampered' WHERE id = :id")
                .bind("id", auditId).fetch().rowsUpdated().block())
                .hasMessageContaining("reputation admin audit is immutable");
        assertThatThrownBy(() -> db.sql("DELETE FROM reputation_admin_audit WHERE id = :id")
                .bind("id", auditId).fetch().rowsUpdated().block())
                .hasMessageContaining("reputation admin audit is immutable");

        String action = db.sql("SELECT action FROM reputation_admin_audit WHERE id = :id")
                .bind("id", auditId).map(row -> row.get("action", String.class)).one().block();
        assertThat(action).isEqualTo("policy_updated");
    }

    @Test
    @DisplayName("Lv5 邀请校验 UUID、备注长度和管理员角色")
    void lv5AdmissionValidatesAuthorizationAndInput() {
        String accountId = UUID.randomUUID().toString();
        Map<String, Object> valid = Map.of("admitted", true, "expectedVersion", 0, "note", "邀请");

        client().put().uri("/api/admin/reputation/" + accountId + "/lv5-admission")
                .header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "risk"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(valid)
                .exchange().expectStatus().isForbidden();

        client().put().uri("/api/admin/reputation/" + accountId + "/lv5-admission")
                .header("X-Grassland-Identity", signService("trust"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(valid)
                .exchange().expectStatus().isForbidden();

        client().put().uri("/api/admin/reputation/not-a-uuid/lv5-admission")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(valid)
                .exchange().expectStatus().isBadRequest();

        client().put().uri("/api/admin/reputation/" + accountId + "/lv5-admission")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("admitted", true, "expectedVersion", 0, "note", "x".repeat(501)))
                .exchange().expectStatus().isBadRequest();

        client().put().uri("/api/admin/reputation/" + accountId + "/lv5-admission")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("admitted", true, "note", "缺版本"))
                .exchange().expectStatus().isBadRequest();

        client().put().uri("/api/admin/reputation/" + accountId + "/lv5-admission")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("admitted", true, "expectedVersion", 0, "note", "   "))
                .exchange().expectStatus().isBadRequest();
    }

    @Test
    @DisplayName("trust 内部资格端点返回严格字段并拒绝用户和错误 service principal")
    void internalEligibilityContractIsStrict() {
        String accountId = UUID.randomUUID().toString();

        client().get().uri("/internal/marketplace/reputation/" + accountId + "/level")
                .header("X-Grassland-Identity", signService("trust"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.data.accountId").isEqualTo(accountId)
                .jsonPath("$.data.effectiveLevel").isEqualTo("Lv1")
                .jsonPath("$.data.levelNumber").isEqualTo(1)
                .jsonPath("$.data.judgeEligible").isEqualTo(false)
                .jsonPath("$.data.policyVersion").isEqualTo(1);

        client().get().uri("/internal/marketplace/reputation/" + accountId + "/level")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isForbidden();

        client().get().uri("/internal/marketplace/reputation/" + accountId + "/level")
                .header("X-Grassland-Identity", signService("intelligence"))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("intelligence AI 权益端点返回策略快照并严格校验服务身份")
    void internalAiEntitlementRequiresIntelligenceServiceIdentity() {
        String accountId = UUID.randomUUID().toString();

        client().get().uri("/internal/marketplace/reputation/" + accountId + "/ai-entitlement")
                .header("X-Grassland-Identity", signService("intelligence"))
                .exchange().expectStatus().isOk().expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.accountId").isEqualTo(accountId)
                .jsonPath("$.data.aiQuotaMultiplierBps").isEqualTo(10_000)
                .jsonPath("$.data.policyVersion").isEqualTo(1);

        client().get().uri("/internal/marketplace/reputation/" + accountId + "/ai-entitlement")
                .header("X-Grassland-Identity", signService("trust"))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/internal/marketplace/reputation/" + accountId + "/ai-entitlement")
                .header("X-Grassland-Identity", sign(UUID.randomUUID().toString(), "recommender"))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/internal/marketplace/reputation/not-a-uuid/ai-entitlement")
                .header("X-Grassland-Identity", signService("intelligence"))
                .exchange().expectStatus().isBadRequest();
    }

    private void assertBadConfiguration(List<Map<String, Object>> levels) {
        client().put().uri("/api/admin/reputation-config")
                .header("X-Grassland-Identity", signWithRole(ADMIN, "platform_admin"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("expectedVersion", 1, "levels", levels))
                .exchange().expectStatus().isBadRequest();
    }

    private static List<Map<String, Object>> validLevels(int lv2Completed) {
        return new java.util.ArrayList<>(List.of(
                rule(1, "Lv1", "新手草友", 0, 0.0, null, false, false, List.of("基础任务")),
                rule(2, "Lv2", "活跃草友", lv2Completed, 0.80, null, false, false, List.of("更多任务")),
                rule(3, "Lv3", "优质草友", 21, 0.85, 4.0, false, false, List.of("优先推荐")),
                rule(4, "Lv4", "金牌草友", 51, 0.90, 4.5, false, false, List.of("专属活动")),
                rule(5, "Lv5", "草场达人", 100, 0.95, 4.8, true, true,
                        List.of("审判官资格", "T+1 优先结算"))));
    }

    private static Map<String, Object> rule(int levelNumber, String level, String title,
                                            int minCompleted, double minCompletionRate,
                                            Double minAverageScore, boolean inviteOnly,
                                            boolean judgeEligible, List<String> benefits) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("levelNumber", levelNumber);
        result.put("level", level);
        result.put("title", title);
        result.put("minCompleted", minCompleted);
        result.put("minCompletionRate", minCompletionRate);
        result.put("minAverageScore", minAverageScore);
        result.put("inviteOnly", inviteOnly);
        result.put("judgeEligible", judgeEligible);
        result.put("taskPriorityWeight", switch (levelNumber) {
            case 1 -> 100;
            case 2 -> 110;
            case 3 -> 120;
            case 4 -> 140;
            default -> 160;
        });
        result.put("settlementDelayDays", levelNumber == 5 ? 1 : 2);
        result.put("commissionBonusBps", switch (levelNumber) {
            case 3 -> 300;
            case 4 -> 500;
            case 5 -> 1000;
            default -> 0;
        });
        result.put("aiQuotaMultiplierBps", switch (levelNumber) {
            case 3, 4, 5 -> 15_000;
            default -> 10_000;
        });
        result.put("premiumSupport", levelNumber >= 4);
        result.put("benefits", benefits);
        return result;
    }

    private void resetRule(int levelNumber, String level, String title, int minCompleted,
                           double minRate, Double minScore, boolean inviteOnly,
                           boolean judgeEligible, List<String> benefits) {
        String benefitsJson = benefits.stream()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        var spec = db.sql("""
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
                """)
                .bind("code", level).bind("title", title).bind("completed", minCompleted)
                .bind("rate", minRate).bind("inviteOnly", inviteOnly)
                .bind("judgeEligible", judgeEligible).bind("benefits", benefitsJson)
                .bind("priorityWeight", switch (levelNumber) {
                    case 1 -> 100; case 2 -> 110; case 3 -> 120; case 4 -> 140; default -> 160;
                })
                .bind("settlementDays", levelNumber == 5 ? 1 : 2)
                .bind("commissionBps", switch (levelNumber) {
                    case 3 -> 300; case 4 -> 500; case 5 -> 1000; default -> 0;
                })
                .bind("aiQuotaBps", switch (levelNumber) {
                    case 3, 4, 5 -> 15_000; default -> 10_000;
                })
                .bind("premiumSupport", levelNumber >= 4)
                .bind("number", levelNumber);
        spec = minScore == null ? spec.bindNull("score", Double.class) : spec.bind("score", minScore);
        spec.fetch().rowsUpdated().block();
    }

    private long latestAuditId() {
        Long value = db.sql("SELECT COALESCE(MAX(id), 0) AS id FROM reputation_admin_audit")
                .map(row -> row.get("id", Long.class)).one().block();
        return value == null ? 0 : value;
    }

    private int auditCountAfterStart() {
        Integer value = db.sql("SELECT COUNT(*)::int AS c FROM reputation_admin_audit WHERE id > :start")
                .bind("start", auditStartId).map(row -> row.get("c", Integer.class)).one().block();
        return value == null ? 0 : value;
    }

    private record AdmissionAudit(String action, boolean beforeAdmitted, long beforeVersion,
                                  boolean afterAdmitted, long afterVersion) {}
}
