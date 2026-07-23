package com.grassland.identity.assertion;

import java.time.Instant;

/**
 * BFF 签发的短时内部身份断言 claims（HLD 7.4「内部身份断言」）。
 *
 * <p>edge-bff 直读 session 表解析出当前账号后，构造本断言并 HMAC 签名，作为内部 Header
 * （默认 {@code X-Grassland-Identity}）转发给下游领域服务。下游验签后据此识人，
 * <b>但仍须自做资源级授权</b>（HLD 7.4 末句：「领域服务不能只信任 BFF 声明的角色」）。
 *
 * <p>claims 语义：
 * <ul>
 *   <li>{@code accountId} / {@code sessionToken}（sid）— 身份事实，验签后可信。</li>
 *   <li>{@code activeIdentityType} — 当前 session 活动身份（merchant/recommender/{@code null}=消费者）。
 *       来源 identity_session（identity-service 自管表），故信任断言值。</li>
 *   <li>{@code authMethod} / {@code authStrength} / {@code reauthenticatedAt} — 认证方式/强度/最近重认证时间；
 *       当前 level1=密码 session，level2 留待 MFA（D-08）。</li>
 *   <li>{@code requestId} / {@code traceId} — 透传请求/链路追踪。</li>
 *   <li>{@code audience} / {@code issuedAt} / {@code expiresAt} — 限缩受众与短时窗口。</li>
 * </ul>
 *
 * <p>record + Jackson（JavaTimeModule）序列化为 JSON，base64url 编码后作 token payload。
 */
public record IdentityAssertion(
        String accountId,
        String activeIdentityType,
        String sessionToken,
        String authMethod,
        String authStrength,
        Instant reauthenticatedAt,
        String requestId,
        String traceId,
        String audience,
        Instant issuedAt,
        Instant expiresAt) {
}
