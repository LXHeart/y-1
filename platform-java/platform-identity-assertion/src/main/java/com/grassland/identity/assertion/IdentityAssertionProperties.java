package com.grassland.identity.assertion;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 内部身份断言配置（GL-P0-ASSERT-001 keyring 模式）。
 *
 * <h3>属性前缀</h3>
 * {@code identity-assertion}
 *
 * <h3>两种模式</h3>
 * <ul>
 *   <li><b>keyring 模式</b>（生产）：{@code signing-keys} + {@code verify-keys} 显式配置 per-pair 密钥。
 *       必须设置 {@code issuer}。</li>
 *   <li><b>legacy 模式</b>（测试兼容）：仅设置 {@code secret} + {@code audience}，不校验 issuer/kid 绑定。
 *       不与 keys 混用（混用则启动失败）。</li>
 * </ul>
 *
 * <h3>keyring 配置示例</h3>
 * <pre>{@code
 * identity-assertion:
 *   enabled: true
 *   issuer: edge-bff
 *   signing-keys:
 *     - kid: "edge-user-identity-v1"
 *       purpose: user
 *       audience: grassland-identity
 *       secret: "${IDENTITY_ASSERTION_KEY_EDGE_USER_IDENTITY}"
 *   verify-keys: []  # edge-bff 不验证断言
 *   replay-protection:
 *     enabled: false  # 单实例部署时关闭，扩副本前须切换共享存储
 * }</pre>
 *
 * <h3>配置校验</h3>
 * <ul>
 *   <li>enabled=true 且非 legacy 模式时，keys 不能为空。</li>
 *   <li>signing-keys 条目的 kid/issuer/purpose/audience/secret 不能为空。</li>
 *   <li>signing-keys 和 verify-keys 的 kid 不能重复（含两者之间）。</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "identity-assertion")
public record IdentityAssertionProperties(
        boolean enabled,
        // legacy 模式字段（仅测试兼容）
        String secret,
        long ttlSeconds,
        String audience,
        String headerName,
        long leewaySeconds,
        List<String> internalHeaderDenylist,
        // keyring 模式字段
        String issuer,
        List<KeyEntry> signingKeys,
        List<KeyEntry> verifyKeys,
        ReplayProtectionConfig replayProtection) {

    public IdentityAssertionProperties {
        // 填充默认值
        if (ttlSeconds <= 0) {
            ttlSeconds = 60;
        }
        if (leewaySeconds < 0) {
            leewaySeconds = 5;
        }
        if (audience == null || audience.isBlank()) {
            audience = "grassland-internal";
        }
        if (headerName == null || headerName.isBlank()) {
            headerName = "X-Grassland-Identity";
        }
        internalHeaderDenylist = (internalHeaderDenylist == null || internalHeaderDenylist.isEmpty())
                ? List.of(
                        "X-Grassland-Identity",
                        "X-Grassland-Account-Id",
                        "X-Grassland-Active-Identity",
                        "X-Grassland-Session-Token")
                : List.copyOf(internalHeaderDenylist);

        // 过滤掉 secret 为空的条目：env 占位符 ${VAR:} 未设置时为空字符串，
        // 视作「该钥未配置」（本地/测试未注入 env 时自动回落 legacy secret，不破坏上下文启动）。
        // 生产环境须确保所有 key env 已注入（.env.example + compose 已列全）；缺钥会在签发时抛
        // IdentityAssertionException（fail-closed），故此过滤只放宽「启动期强校验」，不放宽「运行期缺钥」。
        signingKeys = (signingKeys == null ? List.<KeyEntry>of() : signingKeys).stream()
                .filter(e -> e != null && e.secret() != null && !e.secret().isBlank())
                .toList();
        verifyKeys = (verifyKeys == null ? List.<KeyEntry>of() : verifyKeys).stream()
                .filter(e -> e != null && e.secret() != null && !e.secret().isBlank())
                .toList();
        replayProtection = replayProtection != null ? replayProtection : new ReplayProtectionConfig(false);

        // 校验模式：enabled 时必须二选一（legacy secret 或有效 keyring keys），不可同时设置
        boolean hasLegacySecret = secret != null && !secret.isBlank();
        boolean hasKeyringKeys = !signingKeys.isEmpty() || !verifyKeys.isEmpty();
        if (enabled && hasLegacySecret && hasKeyringKeys) {
            throw new IllegalArgumentException(
                    "identity-assertion: legacy secret and keyring keys cannot both be set");
        }
        if (enabled && !hasLegacySecret && !hasKeyringKeys) {
            // 既无 legacy secret 也无有效 keyring keys → fail-fast（覆盖旧版「secret 必填」语义）
            throw new IllegalArgumentException(
                    "identity-assertion.secret (legacy) or signing-keys/verify-keys (keyring) "
                            + "must be set when identity-assertion.enabled=true");
        }
        if (enabled && hasKeyringKeys && replayProtection.enabled()
                && replayProtection.usesRedis() && replayProtection.redisUrl().isBlank()) {
            throw new IllegalArgumentException(
                    "identity-assertion.replay-protection.redis-url must be set for redis storage");
        }

        // keyring 模式校验（仅对有效条目）
        if (enabled && hasKeyringKeys) {
            if (issuer == null || issuer.isBlank()) {
                throw new IllegalArgumentException(
                        "identity-assertion.issuer must be set in keyring mode");
            }
            // 校验 signing-keys 条目（kid 在 signing-keys 内唯一；与 verify-keys 可重复——对称 HMAC）
            List<String> signingKids = new ArrayList<>();
            for (KeyEntry entry : signingKeys) {
                if (entry.kid() == null || entry.kid().isBlank()) {
                    throw new IllegalArgumentException("signing-key kid must be non-blank");
                }
                if (signingKids.contains(entry.kid())) {
                    throw new IllegalArgumentException("duplicate kid in signing-keys: " + entry.kid());
                }
                signingKids.add(entry.kid());
                if (entry.purpose() == null) {
                    throw new IllegalArgumentException("signing-key purpose must be set (user/service)");
                }
                if (entry.audience() == null || entry.audience().isBlank()) {
                    throw new IllegalArgumentException("signing-key audience must be non-blank");
                }
            }
            // 校验 verify-keys 条目（kid 在 verify-keys 内唯一）
            List<String> verifyKids = new ArrayList<>();
            for (KeyEntry entry : verifyKeys) {
                if (entry.kid() == null || entry.kid().isBlank()) {
                    throw new IllegalArgumentException("verify-key kid must be non-blank");
                }
                if (verifyKids.contains(entry.kid())) {
                    throw new IllegalArgumentException("duplicate kid in verify-keys: " + entry.kid());
                }
                verifyKids.add(entry.kid());
                if (entry.issuer() == null || entry.issuer().isBlank()) {
                    throw new IllegalArgumentException("verify-key issuer must be non-blank");
                }
                if (entry.purpose() == null) {
                    throw new IllegalArgumentException("verify-key purpose must be set");
                }
                if (entry.audience() == null || entry.audience().isBlank()) {
                    throw new IllegalArgumentException("verify-key audience must be non-blank");
                }
            }
        }
    }

    public Duration ttl() {
        return Duration.ofSeconds(ttlSeconds);
    }

    public Duration leeway() {
        return Duration.ofSeconds(leewaySeconds);
    }

    /** 是否为 legacy 模式（单 secret）。 */
    public boolean isLegacyMode() {
        boolean hasLegacySecret = secret != null && !secret.isBlank();
        boolean hasKeyringKeys = !signingKeys.isEmpty() || !verifyKeys.isEmpty();
        return hasLegacySecret && !hasKeyringKeys;
    }

    /**
     * 单把密钥配置条目。
     *
     * @param kid 密钥标识符（唯一）
     * @param issuer 签发方（verify-keys 必填；signing-keys 可缺省，自动用 properties.issuer）
     * @param purpose {@code user} 或 {@code service}
     * @param audience 受众服务名
     * @param secret 密钥原文（环境变量占位符）
     */
    public record KeyEntry(
            String kid,
            String issuer,
            String purpose,
            String audience,
            String secret) {

        public KeyEntry {
            // 暂不在 compact-constructor 校验，由 IdentityAssertionProperties 统一校验
        }

        /** 将 purpose 转为 {@link Purpose} 枚举。 */
        public Purpose purposeEnum() {
            if (purpose == null) {
                return null;
            }
            return switch (purpose.toLowerCase()) {
                case "user" -> Purpose.USER;
                case "service" -> Purpose.SERVICE;
                default -> throw new IllegalArgumentException("Unknown purpose: " + purpose);
            };
        }
    }

    /** replay 防护配置。 */
    public record ReplayProtectionConfig(
            boolean enabled,
            String storage,
            String redisUrl,
            String keyPrefix) {

        /** 旧测试构造器兼容：显式 boolean 使用进程内 guard。生产 YAML 默认 redis。 */
        public ReplayProtectionConfig(boolean enabled) {
            this(enabled, "memory", "", "grassland:identity-assertion:replay:");
        }

        public ReplayProtectionConfig {
            storage = (storage == null || storage.isBlank()) ? "redis" : storage.trim().toLowerCase();
            redisUrl = redisUrl == null ? "" : redisUrl.trim();
            keyPrefix = (keyPrefix == null || keyPrefix.isBlank())
                    ? "grassland:identity-assertion:replay:"
                    : keyPrefix;
            if (!storage.equals("redis") && !storage.equals("memory")) {
                throw new IllegalArgumentException(
                        "identity-assertion.replay-protection.storage must be redis or memory");
            }
        }

        public boolean usesRedis() {
            return enabled && storage.equals("redis");
        }

        public boolean usesMemory() {
            return enabled && storage.equals("memory");
        }
    }
}
