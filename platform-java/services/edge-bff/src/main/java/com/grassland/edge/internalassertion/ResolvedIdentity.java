package com.grassland.edge.internalassertion;

/**
 * edge-bff 直读 session 表解析出的当前调用者身份（HLD 7.4）。
 *
 * <p>{@code activeIdentityType} 为 {@code null} 表示该 session 为消费者。{@code organizationId}/{@code permissionTier}
 * 来自该账号商家身份关联的 org（identity_profile↔organization；非商家或未关联为 null），下发断言供下游 org 级授权/限额。
 * {@code role}/{@code status} 仅为断言 {@code authStrength} 判断所用，<b>不下发</b>——account 级角色由下游自查。
 *
 * @param mustChangePassword 首登强制改密标记（任务书 #48，account_flag 表）：true 时
 *                           {@link InternalAssertionFilter} 对非豁免路径统一 428，改密完成前锁死业务面。
 *                           与 {@code status} 同为边界自消费字段，不签入断言。
 */
public record ResolvedIdentity(
        String accountId,
        String role,
        String status,
        String activeIdentityType,
        String sessionToken,
        String organizationId,
        String permissionTier,
        /** MFA 重认证时刻（V7）；null=从未重认证。下游按近期性判定敏感操作（如 trust 客服终审）。 */
        java.time.Instant reauthenticatedAt,
        /** level1=普通登录 / level2=已重认证；null 视作 level1。 */
        String authStrength,
        boolean mustChangePassword) {
}
