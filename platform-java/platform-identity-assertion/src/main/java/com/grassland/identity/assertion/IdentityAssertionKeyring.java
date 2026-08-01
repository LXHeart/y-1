package com.grassland.identity.assertion;

import java.util.List;
import java.util.Optional;

/**
 * 身份断言密钥环（GL-P0-ASSERT-001）。签发方持有签名钥，受众持有验签钥。
 *
 * <h3>签名钥查询</h3>
 * 按 {@code (purpose, targetAudience)} 唯一确定一把钥（生产环境应唯一，缺少时 fail-fast）。
 *
 * <h3>验签钥查询</h3>
 * 按 {@code (issuer, kid)} 精确匹配（kid 来自 payload {@code keyId} claim）。
 * kid 为 null 时返回该 issuer 的全部验签钥（用于 kid 缺失的 legacy token，单钥场景）。
 *
 * <h3>安全性</h3>
 * <ul>
 *   <li>签名钥泄露 → 只能伪造发给某受众的断言（audience 绑定）。</li>
 *   <li>验签钥泄露 → 只能解析发给本服务的断言，无法签发给别处。</li>
 *   <li>service 钥的 {@code issuer} 绑定 {@code principal}（{@link IdentityAssertionKey#matches}）。</li>
 * </ul>
 */
public interface IdentityAssertionKeyring {

    /**
     * 查询签名钥（签发时调用）。
     *
     * @param purpose {@link Purpose#USER} 或 {@link Purpose#SERVICE}
     * @param targetAudience 目标受众服务名（如 identity/marketplace/finance）
     * @return 签名钥（非空，缺钥时抛异常或返回 empty 由调用方处理）
     */
    Optional<IdentityAssertionKey> signingKey(Purpose purpose, String targetAudience);

    /**
     * 查询验签钥（验签时调用）。
     *
     * @param issuer 签发方服务名（来自 payload {@code issuer} claim）
     * @param kid 密钥标识符（来自 payload {@code keyId} claim，null 表示查找该 issuer 的全部钥）
     * @return 匹配的验签钥列表（可能为空；kid 为 null 时返回该 issuer 的全部钥）
     */
    List<IdentityAssertionKey> verifyKeys(String issuer, String kid);
}
