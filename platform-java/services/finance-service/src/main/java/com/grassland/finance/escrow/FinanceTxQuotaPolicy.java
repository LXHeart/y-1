package com.grassland.finance.escrow;

/**
 * 单笔交易额度策略（草场 D-05「硬限额执行」finance 侧）。纯逻辑 + 静态表，无外部依赖。
 *
 * <p>断言 {@code X-Grassland-Identity} 携带的 {@code permissionTier} 是 String dbValue；finance 无法 import
 * identity-service 的 {@code PermissionTier}（build 仅依赖 platform-assertion），故此处内联同义映射，
 * 与 marketplace 的 {@code MerchantTier}/{@code PublishQuotaPolicy} 同构。
 *
 * <p><b>交叉引用（防漂移）</b>：{@link #maxTxAmountCents} 与 {@code identity.permission.PermissionQuotaPolicy}
 * 的 {@code maxTxAmountCents}、marketplace {@code PublishQuotaPolicy.maxTxAmountCents} 同值。
 * 三处变更须同步，单测 {@code FinanceTxQuotaPolicyTest} 锁定。
 *
 * <p><b>适用范围（关键）</b>：仅对<b>终端商家用户断言</b>执行。marketplace Saga 的服务断言
 * （{@code callerKind=service}，{@code permissionTier=null}）<b>豁免</b>——服务断言刻意不带 tier，
 * 若按 null→DRAFT 解析会把上限判成 0，拦死 4F 的 AcceptApplicationReservationWorkflow。
 * 服务断言发起的预留，其金额已在 marketplace 发布任务时经 {@code PublishQuotaPolicy.maxTxAmountCents} 校验。
 */
public final class FinanceTxQuotaPolicy {

    private FinanceTxQuotaPolicy() {}

    private static final long DRAFT_MAX = 0L;
    private static final long BASIC_PUBLISH_MAX = 0L;
    private static final long FINANCE_TRANSACTION_MAX = 10_000_000L;

    /** 某 tier dbValue 的单笔交易上限（分）。0 = 无交易权限；未知/null → 0（保守拒绝）。 */
    public static long maxTxAmountCents(String permissionTier) {
        if (permissionTier == null || permissionTier.isBlank()) {
            return DRAFT_MAX;
        }
        return switch (permissionTier.trim().toLowerCase()) {
            case "finance_transaction" -> FINANCE_TRANSACTION_MAX;
            case "basic_publish" -> BASIC_PUBLISH_MAX;
            default -> DRAFT_MAX;  // draft 及未知值保守按 0
        };
    }

    /** 该 tier 下 {@code amountCents} 是否在单笔上限内。 */
    public static boolean isWithinLimit(String permissionTier, long amountCents) {
        return amountCents <= maxTxAmountCents(permissionTier);
    }
}
