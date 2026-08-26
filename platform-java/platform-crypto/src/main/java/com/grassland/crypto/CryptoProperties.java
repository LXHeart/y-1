package com.grassland.crypto;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 信封加密配置属性（GL-P3-AI-001 Phase 1；双 KEK 并存见下）。
 *
 * <p>KEK 从环境变量 {@code CRYPTO_KEK_BASE64} 读取（32 字节 Base64）。
 *
 * <p><b>KEK 轮换支持（双 KEK 并存）</b>：密文首字节记录 key version，解密据此选择密钥材料。
 * 轮换期同时配置新旧两把：
 * <ul>
 *   <li>{@code crypto.kek.encoded} + {@code crypto.kek.version} — 当前 KEK，<b>写入路径只用它</b>；</li>
 *   <li>{@code crypto.kek.previous} — 形如 {@code 1=<base64>,2=<base64>} 的旧 KEK 台账，
 *       <b>只供解密</b>。重加密全部存量密文后，才可从配置移除对应版本。</li>
 * </ul>
 *
 * <p>版本号<b>必须由配置显式给出</b>，不能是进程内计数器——计数器重启即归 1，会让同一把 KEK 的
 * 密文带上互相冲突的版本字节，解密时无法定位密钥材料。
 */
@ConfigurationProperties("crypto")
public record CryptoProperties(
    /** KEK（Key Encryption Key）的 Base64 编码，必须是 32 字节（AES-256）。 */
    Kek kek
) {

    public CryptoProperties {
        if (kek == null) {
            kek = Kek.of(null);
        }
    }

    /**
     * KEK 配置。
     *
     * @param encoded Base64 编码的当前 KEK（32 字节），写入路径使用
     * @param version 当前 KEK 的版本号（1–255，写入密文首字节）；不配置默认 1
     * @param previous 旧 KEK 台账 {@code version=base64} 逗号分隔，仅供解密；不配置表示无历史版本
     */
    public record Kek(
        String encoded,
        Integer version,
        String previous
    ) {

        /**
         * 便捷工厂：无历史版本、版本号取默认 1（测试与本地开发用）。
         *
         * <p>刻意用静态工厂而非重载构造器——record 有第二个构造器时 Spring 的
         * {@code @ConfigurationProperties} 构造器绑定无法判断用哪个，会静默绑不上 {@code encoded}，
         * 表现为启动期「crypto.kek.encoded is required」。实测踩过。
         */
        public static Kek of(String encoded) {
            return new Kek(encoded, null, null);
        }

        /** 当前版本号；未配置时为 1。 */
        public int currentVersion() {
            return version == null ? 1 : version;
        }

        @PostConstruct
        void validate() {
            if (encoded != null && !encoded.isBlank()) {
                requireThirtyTwoBytes("crypto.kek.encoded", encoded);
            }
            int current = currentVersion();
            // 版本号写进密文单字节，故上限 255；0 不用，便于把「未初始化」和 v1 区分开
            if (current < 1 || current > 255) {
                throw new IllegalStateException(
                    "crypto.kek.version must be between 1 and 255, got: " + current);
            }
            parsePrevious().forEach((legacyVersion, material) -> {
                if (legacyVersion < 1 || legacyVersion > 255) {
                    throw new IllegalStateException(
                        "crypto.kek.previous contains out-of-range version: " + legacyVersion);
                }
                if (legacyVersion == current) {
                    // 否则解密时同一版本有两份候选材料，行为不确定
                    throw new IllegalStateException(
                        "crypto.kek.previous must not contain the current version " + current);
                }
                requireThirtyTwoBytes("crypto.kek.previous[" + legacyVersion + "]", material);
            });
        }

        /**
         * 解析旧 KEK 台账。格式 {@code 1=<base64>,2=<base64>}；空/缺失返回空 map。
         *
         * <p>用 {@code version=material} 而非有序列表：轮换后旧版本号可能不连续（例如跳过一次
         * 未上线的轮换），位置隐含版本号会在那时错位。
         */
        public java.util.Map<Integer, String> parsePrevious() {
            if (previous == null || previous.isBlank()) {
                return java.util.Map.of();
            }
            java.util.Map<Integer, String> parsed = new java.util.LinkedHashMap<>();
            for (String entry : previous.split(",")) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0 || separator == trimmed.length() - 1) {
                    throw new IllegalStateException(
                        "crypto.kek.previous entries must look like <version>=<base64>, got: " + trimmed);
                }
                String versionPart = trimmed.substring(0, separator).trim();
                String materialPart = trimmed.substring(separator + 1).trim();
                int legacyVersion;
                try {
                    legacyVersion = Integer.parseInt(versionPart);
                } catch (NumberFormatException e) {
                    throw new IllegalStateException(
                        "crypto.kek.previous version must be an integer, got: " + versionPart, e);
                }
                if (parsed.put(legacyVersion, materialPart) != null) {
                    throw new IllegalStateException(
                        "crypto.kek.previous contains duplicate version: " + legacyVersion);
                }
            }
            return parsed;
        }

        private static void requireThirtyTwoBytes(String name, String base64) {
            byte[] decoded;
            try {
                decoded = java.util.Base64.getDecoder().decode(base64);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(name + " must be valid Base64", e);
            }
            if (decoded.length != 32) {
                throw new IllegalStateException(
                    name + " must be exactly 32 bytes when decoded, got: " + decoded.length);
            }
        }
    }

    /** 检查 KEK 是否已配置。 */
    public boolean isKekConfigured() {
        return kek != null && kek.encoded() != null && !kek.encoded().isBlank();
    }
}
