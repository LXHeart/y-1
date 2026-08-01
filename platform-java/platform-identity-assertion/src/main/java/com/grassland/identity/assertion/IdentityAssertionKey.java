package com.grassland.identity.assertion;

/**
 * 单把身份断言密钥（signing 或 verify）。GL-P0-ASSERT-001 per-pair 分钥：每个「签发方→受众+用途」独立密钥。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code kid} — 密钥标识符，用于轮换。非空，签入 payload {@code keyId} claim，验签时匹配对应密钥。</li>
 *   <li>{@code issuer} — 签发方服务名（edge-bff/marketplace/trust）。签入 payload {@code issuer} claim，验签时校验绑定。</li>
 *   <li>{@code purpose} — {@link Purpose#USER} 或 {@link Purpose#SERVICE}。user 钥只能签用户断言，service 钥只能签服务断言。</li>
 *   <li>{@code audience} — 受众服务名（identity/marketplace/finance/trust/intelligence），签入 payload {@code audience} claim，验签时校验绑定。</li>
 *   <li>{@code secret} — HMAC-SHA256 密钥原始字节。验签钥无需持有原值，但当前实现为对称密钥，sign/verify 共用同一 secret。</li>
 * </ul>
 *
 * <p>安全性：泄露某服务的验签钥只能「读」发给它的断言，无法伪造发给别处的断言（audience 绑定）；
 * 泄露某服务的签名钥只能冒充该 issuer，且 audience/purpose 受限。
 */
public record IdentityAssertionKey(
        String kid,
        String issuer,
        Purpose purpose,
        String audience,
        byte[] secret) {

    public IdentityAssertionKey {
        if (kid == null || kid.isBlank()) {
            throw new IllegalArgumentException("kid must be non-blank");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must be non-blank");
        }
        if (purpose == null) {
            throw new IllegalArgumentException("purpose must be non-null");
        }
        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException("audience must be non-blank");
        }
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException("secret must be non-empty");
        }
        // 防御性拷贝，避免外部修改
        secret = secret.clone();
    }

    /** 构造一个仅验签的密钥（secret 用于 HMAC，对称密钥下验签仍需原值）。 */
    public static IdentityAssertionKey verifyOnly(
            String kid, String issuer, Purpose purpose, String audience, byte[] secret) {
        return new IdentityAssertionKey(kid, issuer, purpose, audience, secret);
    }

    /** 是否为 user 钥。 */
    public boolean isUserKey() {
        return purpose == Purpose.USER;
    }

    /** 是否为 service 钥。 */
    public boolean isServiceKey() {
        return purpose == Purpose.SERVICE;
    }

    /**
     * 检查断言是否符合本钥的绑定约束（验签后校验）。
     *
     * <p>约束：
     * <ul>
     *   <li>audience 必须 {@link #audience}。</li>
     *   <li>purpose 必须匹配：user 钥只认用户断言（callerKind!=service），service 钥只认服务断言。</li>
     *   <li>对于 service 钥，{@code principal} 必须等于 {@link #issuer}（防冒充别的服务）。</li>
     * </ul>
     *
     * @param assertion 已验签的断言
     * @param isService 断言是否为服务断言（assertion.isService()，避免重复解析）
     * @return 是否通过绑定校验
     */
    public boolean matches(IdentityAssertion assertion, boolean isService) {
        // audience 绑定
        if (!audience.equals(assertion.audience())) {
            return false;
        }
        // purpose 绑定
        if (purpose.requiresServiceCaller() != isService) {
            return false;
        }
        // service 钥的 principal 绑定
        if (isService && purpose == Purpose.SERVICE) {
            String principal = assertion.principal();
            if (principal == null || !principal.equals(issuer)) {
                return false;
            }
        }
        return true;
    }
}
