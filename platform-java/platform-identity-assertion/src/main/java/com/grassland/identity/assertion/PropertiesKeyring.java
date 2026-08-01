package com.grassland.identity.assertion;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 从 {@link IdentityAssertionProperties} 构建的 keyring 实现。
 *
 * <p>索引：
 * <ul>
 *   <li>签名钥：{@code Map<Purpose, Map<audience, Key>>}（按 purpose+audience 唯一）。</li>
 *   <li>验签钥：{@code Map<issuer, Map<kid, Key>>}（按 issuer+kid 精确匹配）。</li>
 * </ul>
 */
public final class PropertiesKeyring implements IdentityAssertionKeyring {

    private final Map<Purpose, Map<String, IdentityAssertionKey>> signingKeys;
    private final Map<String, Map<String, IdentityAssertionKey>> verifyKeys; // issuer → (kid → Key)

    private PropertiesKeyring(
            Map<Purpose, Map<String, IdentityAssertionKey>> signingKeys,
            Map<String, Map<String, IdentityAssertionKey>> verifyKeys) {
        this.signingKeys = Collections.unmodifiableMap(signingKeys);
        this.verifyKeys = Collections.unmodifiableMap(verifyKeys);
    }

    /** 从 properties 构建 keyring。 */
    public static PropertiesKeyring from(IdentityAssertionProperties props) {
        Map<Purpose, Map<String, IdentityAssertionKey>> signingMap = new HashMap<>();
        Map<String, Map<String, IdentityAssertionKey>> verifyMap = new HashMap<>();

        // 构建 signing-keys
        for (IdentityAssertionProperties.KeyEntry entry : props.signingKeys()) {
            Purpose purpose = entry.purposeEnum();
            String audience = entry.audience();
            String issuer = props.issuer(); // 签发方统一用 properties.issuer
            String kid = entry.kid();
            byte[] secret = entry.secret().getBytes(StandardCharsets.UTF_8);

            IdentityAssertionKey key = new IdentityAssertionKey(kid, issuer, purpose, audience, secret);

            signingMap.computeIfAbsent(purpose, k -> new HashMap<>()).put(audience, key);
        }

        // 构建 verify-keys
        for (IdentityAssertionProperties.KeyEntry entry : props.verifyKeys()) {
            Purpose purpose = entry.purposeEnum();
            String audience = entry.audience();
            String issuer = entry.issuer(); // 验签钥的 issuer 来自配置
            String kid = entry.kid();
            byte[] secret = entry.secret().getBytes(StandardCharsets.UTF_8);

            IdentityAssertionKey key = new IdentityAssertionKey(kid, issuer, purpose, audience, secret);

            verifyMap.computeIfAbsent(issuer, k -> new HashMap<>()).put(kid, key);
        }

        return new PropertiesKeyring(signingMap, verifyMap);
    }

    @Override
    public Optional<IdentityAssertionKey> signingKey(Purpose purpose, String targetAudience) {
        Map<String, IdentityAssertionKey> byAudience = signingKeys.get(purpose);
        if (byAudience == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byAudience.get(targetAudience));
    }

    @Override
    public List<IdentityAssertionKey> verifyKeys(String issuer, String kid) {
        if (issuer == null || issuer.isBlank()) {
            return List.of();
        }
        Map<String, IdentityAssertionKey> byKid = verifyKeys.get(issuer);
        if (byKid == null) {
            return List.of();
        }
        if (kid == null || kid.isBlank()) {
            // kid 缺失：返回该 issuer 的全部验签钥（兼容 legacy token）
            return new ArrayList<>(byKid.values());
        }
        // kid 精确匹配
        IdentityAssertionKey key = byKid.get(kid);
        return key != null ? List.of(key) : List.of();
    }

    /**
     * 获取全部签名钥（监控/调试用）。
     */
    public List<IdentityAssertionKey> allSigningKeys() {
        return signingKeys.values().stream()
                .flatMap(m -> m.values().stream())
                .collect(Collectors.toList());
    }

    /**
     * 获取全部验签钥（监控/调试用）。
     */
    public List<IdentityAssertionKey> allVerifyKeys() {
        return verifyKeys.values().stream()
                .flatMap(m -> m.values().stream())
                .collect(Collectors.toList());
    }
}
