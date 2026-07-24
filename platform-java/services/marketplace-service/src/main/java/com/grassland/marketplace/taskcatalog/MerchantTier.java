package com.grassland.marketplace.taskcatalog;

/**
 * 商家准入 tier 的 marketplace 本地镜像（草场 Epic 4 Slice 4B）。
 *
 * <p>断言 {@code X-Grassland-Identity} 携带的 {@code permissionTier} 是 String dbValue，marketplace
 * 无法 import identity-service 的 {@code com.grassland.identity.organization.PermissionTier}（build 仅依赖
 * platform-storage + platform-assertion）。此枚举是其同义副本，仅服务 marketplace 的发布限额执行。
 *
 * <p><b>交叉引用（防漂移）</b>：与 {@code identity.permission.PermissionQuotaPolicy} 配对——
 * identity 暴露策略声明（含 maxMonthly/maxTx），marketplace 仅消费 maxActiveTasks（见 {@link PublishQuotaPolicy}）。
 * tier 值受监管、极少变动；单元测试 {@code MerchantTierTest} 锁定 dbValue 映射。长期去重靠 identity
 * {@code GET /api/organizations/{orgId}/quota} RPC（marketplace 具备熔断器后评估）。
 */
public enum MerchantTier {
    DRAFT("draft"),
    BASIC_PUBLISH("basic_publish"),
    FINANCE_TRANSACTION("finance_transaction");

    private final String dbValue;

    MerchantTier(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    /** 从断言 String 解析，大小写不敏感；null/空 → {@link #DRAFT}（实际由 TaskController 的 tier 闸门拦为 403）。 */
    public static MerchantTier fromDb(String value) {
        if (value == null || value.isBlank()) {
            return DRAFT;
        }
        String normalized = value.trim().toLowerCase();
        for (MerchantTier tier : values()) {
            if (tier.dbValue.equals(normalized)) {
                return tier;
            }
        }
        return DRAFT;
    }
}
