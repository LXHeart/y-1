package com.grassland.edge.internalassertion;

/**
 * edge-bff 直读 session 表解析出的当前调用者身份（HLD 7.4）。
 *
 * <p>{@code activeIdentityType} 为 {@code null} 表示该 session 为消费者。{@code role}/{@code status}
 * 仅为断言 {@code authStrength} 判断所用，<b>不下发到断言 claims</b>——account 级角色由下游 identity-service
 * 自查 app_users（它是权威，HLD 7.4「不能只信任 BFF 声明的角色」）。
 */
public record ResolvedIdentity(
        String accountId,
        String role,
        String status,
        String activeIdentityType,
        String sessionToken) {
}
