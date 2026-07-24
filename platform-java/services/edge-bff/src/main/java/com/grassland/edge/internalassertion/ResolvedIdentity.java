package com.grassland.edge.internalassertion;

/**
 * edge-bff 直读 session 表解析出的当前调用者身份（HLD 7.4）。
 *
 * <p>{@code activeIdentityType} 为 {@code null} 表示该 session 为消费者。{@code organizationId}/{@code permissionTier}
 * 来自该账号商家身份关联的 org（identity_profile↔organization；非商家或未关联为 null），下发断言供下游 org 级授权/限额。
 * {@code role}/{@code status} 仅为断言 {@code authStrength} 判断所用，<b>不下发</b>——account 级角色由下游自查。
 */
public record ResolvedIdentity(
        String accountId,
        String role,
        String status,
        String activeIdentityType,
        String sessionToken,
        String organizationId,
        String permissionTier) {
}
