package com.grassland.identity.assertion;

import java.util.Locale;

/**
 * 平台后台角色（PRD §11.8「后台角色」）。
 *
 * <p>与 {@link IdentityAssertion#activeIdentityType()}（业务身份：商家/推荐官）正交——
 * 后台角色是账号在平台运营侧的职能。一个账号可同时持有多个后台角色（多值，存 {@code backend_role} 表）。
 *
 * <p>断言 {@code role} claim 以逗号分隔承载多值（如 {@code "platform_admin,content_reviewer"}），
 * 由 {@link BackendRoles#fromClaim(String)} 解析。**审判官不走本枚举**——它走「推荐官 + judge 池」
 * （trust {@code JudgeController}），与后台角色正交。
 *
 * <p>dbValue 是稳定的存储/协议值（snake_case），不在枚举重命名时漂移。{@code PLATFORM_ADMIN} 是超集：
 * 持有它即视为持有所有角色（{@link BackendRoles#hasAny} 对 PLATFORM_ADMIN 恒返回 true）。
 */
public enum BackendRole {
    PLATFORM_ADMIN("platform_admin"),        // 平台管理员（原 admin，超集）
    MERCHANT_REVIEWER("merchant_reviewer"),  // 商家审核员（KYB/门店/认证资料）
    CONTENT_REVIEWER("content_reviewer"),    // 内容审核员（任务/内容/凭证）
    CUSTOMER_SERVICE("customer_service"),    // 客服（争议/申诉/运营处置）
    FINANCE("finance"),                      // 财务人员（支付/托管/对账）
    RISK("risk"),                            // 风控人员（异常调查）
    AI_ADMIN("ai_admin");                    // AI 管理员（模型供应商/能力路由）

    private final String dbValue;

    BackendRole(String dbValue) {
        this.dbValue = dbValue;
    }

    /** 稳定的存储/协议值（snake_case）。 */
    public String dbValue() {
        return dbValue;
    }

    /**
     * 从 dbValue 解析（大小写不敏感）。未知值 → null（前向兼容：新角色在旧代码里被忽略而非抛错）。
     */
    public static BackendRole fromDb(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (BackendRole role : values()) {
            if (role.dbValue.equals(normalized)) {
                return role;
            }
        }
        return null;
    }
}
