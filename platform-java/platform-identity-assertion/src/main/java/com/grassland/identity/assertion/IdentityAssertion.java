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
 *   <li>{@code organizationId} / {@code permissionTier} — 商家身份关联的 org 及其准入 tier
 *       （edge-bff 经 identity_profile↔organization 解析；非商家或未关联为 null）。
 *       marketplace 等下游据此做 org 级资源授权与限额，<b>不替代资源级自查</b>（HLD 7.4）。</li>
 *   <li>{@code authMethod} / {@code authStrength} / {@code reauthenticatedAt} — 认证方式/强度/最近重认证时间；
 *       当前 level1=密码 session，level2 留待 MFA（D-08）。</li>
 *   <li>{@code requestId} / {@code traceId} — 透传请求/链路追踪。</li>
 *   <li>{@code audience} / {@code issuedAt} / {@code expiresAt} — 限缩受众与短时窗口。</li>
 *   <li>{@code callerKind} / {@code principal} — 调用方种类（HLD 11.1「服务身份」）：
 *       {@code null}/{@code "user"} = BFF 签发的终端用户断言（识人靠 {@code accountId}）；
 *       {@code "service"} = 领域服务现签的服务间断言（{@code principal} 为服务名，如 {@code "marketplace"}，
 *       带 {@code organizationId} 上下文供下游做 org 级授权）。下游须据 {@link #isService()} 区分两类，
 *       不允许服务断言冒充 merchant 用户（HLD 7.4 末句）。字段末尾追加，旧 token 反序列化为 {@code null}（前向兼容）。</li>
 *   <li>{@code role} — 账号平台角色（{@code app_users.role}：user/admin/customer_service）。
 *       与 {@code activeIdentityType}（业务身份：商家/推荐官）<b>正交</b>：前者是账号在平台侧的职能，
 *       后者是当前 session 选择的业务视角。客服终审等平台侧动作据此判定（trust {@code requireCustomerService}）。
 *       同样字段末尾追加，旧 token 解出 {@code null}（前向兼容）；服务断言无此值。</li>
 * </ul>
 *
 * <p>record + Jackson（JavaTimeModule）序列化为 JSON，base64url 编码后作 token payload。
 */
public record IdentityAssertion(
        String accountId,
        String activeIdentityType,
        String sessionToken,
        String organizationId,
        String permissionTier,
        String authMethod,
        String authStrength,
        Instant reauthenticatedAt,
        String requestId,
        String traceId,
        String audience,
        Instant issuedAt,
        Instant expiresAt,
        String callerKind,
        String principal,
        String role,
        // GL-P0-ASSERT-001 新增 envelope claims
        String issuer,
        String keyId,
        String jti) {

    private static final String SERVICE = "service";

    /**
     * 构造一个带 envelope（issuer/keyId/jti/audience）的断言副本（签发时填充）。
     *
     * <p>用于 {@link IdentityAssertionSigner#sign(IdentityAssertion, String)}：signer 填充 envelope claims
     * 并用目标 audience 重写 payload audience。
     */
    public IdentityAssertion withEnvelope(String issuer, String keyId, String jti, String audience) {
        return new IdentityAssertion(
                accountId, activeIdentityType, sessionToken, organizationId, permissionTier,
                authMethod, authStrength, reauthenticatedAt, requestId, traceId,
                audience, issuedAt, expiresAt, callerKind, principal, role,
                issuer, keyId, jti);
    }

    /**
     * 15 参便捷构造器（{@code role=null}）——服务间断言与既有测试夹具用。
     *
     * <p>服务断言天然没有用户 role（{@code callerKind=service} 的调用方不是人），
     * 故此重载语义即「无 role」，不是权宜之计。保留它使 role 的加入只波及真正需要 role 的构造点，
     * 而非全部 19 处（HLD 7.4 契约演进：新 claim 末尾追加 + 旧调用方零改动）。
     */
    public IdentityAssertion(String accountId, String activeIdentityType, String sessionToken,
                             String organizationId, String permissionTier, String authMethod,
                             String authStrength, Instant reauthenticatedAt, String requestId,
                             String traceId, String audience, Instant issuedAt, Instant expiresAt,
                             String callerKind, String principal) {
        this(accountId, activeIdentityType, sessionToken, organizationId, permissionTier, authMethod,
                authStrength, reauthenticatedAt, requestId, traceId, audience, issuedAt, expiresAt,
                callerKind, principal, null, null, null, null);
    }

    /**
     * 16 参便捷构造器（{@code role} 显式，envelope 为 null）——保留旧 canonical 调用点零改动。
     *
     * <p>{@code issuer}/{@code keyId}/{@code jti} 缺省 null：由 {@link IdentityAssertionSigner}
     * 在 keyring 模式签发时填充（{@link #withEnvelope}）。直接用本构造器签发的 token 无法通过
     * keyring 验签（缺 issuer/kid），仅 legacy 模式或测试夹具用。
     */
    public IdentityAssertion(String accountId, String activeIdentityType, String sessionToken,
                             String organizationId, String permissionTier, String authMethod,
                             String authStrength, Instant reauthenticatedAt, String requestId,
                             String traceId, String audience, Instant issuedAt, Instant expiresAt,
                             String callerKind, String principal, String role) {
        this(accountId, activeIdentityType, sessionToken, organizationId, permissionTier, authMethod,
                authStrength, reauthenticatedAt, requestId, traceId, audience, issuedAt, expiresAt,
                callerKind, principal, role, null, null, null);
    }

    /** 是否为领域服务现签的服务间断言（HLD 11.1）。 */
    public boolean isService() {
        return SERVICE.equalsIgnoreCase(callerKind);
    }

    /**
     * 是否具备指定平台角色（大小写不敏感）。服务断言恒 false——
     * 服务不是人，不该凭 role 执行平台侧动作（同 {@code isMerchant} 的防冒充原则）。
     */
    public boolean hasRole(String expectedRole) {
        return !isService() && expectedRole != null && expectedRole.equalsIgnoreCase(role);
    }

    /** 是否为 BFF 签发的终端用户断言（{@code callerKind} 缺省视为 user）。 */
    public boolean isUser() {
        return !isService();
    }
}
