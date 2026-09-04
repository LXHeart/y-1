package com.grassland.trust.judge;

import java.time.Instant;

/**
 * 审判官（草场 Epic 6 Slice 6C / HLD 3.1「符合条件的推荐官」+ §5.5 + 任务书 #74 卡 E）。
 *
 * <p>{@code accountId} 与 identity 账号同空间（database-per-service 无 FK）；{@code organizationId} 可空=平台级审判官；
 * {@code eligibilityTier} 来自 marketplace 有效等级；只有 active、{@code opsAdmitted} 且满足 V14 资格谓词
 * （Lv5 直入 / Lv4+考试及格见习）且未挂起才可被抽签。同组织或显式 {@code judge_conflict} 记录构成利益冲突，
 * 抽面板时排除。
 *
 * <p>卡 E 新列：{@code examPassedAt}=准入考试及格时刻；{@code admissionLevel}=full/probation（见习每面板
 * ≤2 席、累计 10 轮投票无异常自动转正）；{@code suspendedUntil}/{@code suspensionReason}=运营确认挂起
 * （90 天窗口弃权率 >40% 的一键建议+人工确认，v1 无自动挂起定时器）。
 */
public record Judge(
        String id,
        String accountId,
        String organizationId,
        int eligibilityTier,
        boolean active,
        boolean opsAdmitted,
        long version,
        Instant opsAdmittedAt,
        String opsAdmittedBy,
        Instant createdAt,
        Instant examPassedAt,
        String admissionLevel,
        Instant probationSince,
        Instant suspendedUntil,
        String suspensionReason) {

    /** 既有 10 参调用方兼容（#74 之前）：无考试/见习/挂起信息。 */
    public Judge(String id, String accountId, String organizationId, int eligibilityTier, boolean active,
                 boolean opsAdmitted, long version, Instant opsAdmittedAt, String opsAdmittedBy,
                 Instant createdAt) {
        this(id, accountId, organizationId, eligibilityTier, active, opsAdmitted, version, opsAdmittedAt,
                opsAdmittedBy, createdAt, null, "full", null, null, null);
    }

    /** 是否见习审判官（admission_level=probation）。 */
    public boolean isProbation() {
        return "probation".equals(admissionLevel);
    }

    /** 当前是否处于挂起期（suspended_until 未到）。 */
    public boolean suspendedNow() {
        return suspendedUntil != null && Instant.now().isBefore(suspendedUntil);
    }
}
