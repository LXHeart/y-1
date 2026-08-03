package com.grassland.crypto;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 信封加密配置属性（GL-P3-AI-001 Phase 1）。
 *
 * <p>首期 KEK 从环境变量 {@code CRYPTO_KEK_BASE64} 读取（32 字节 Base64）。
 * 后续接入 AWS KMS 或云 Secret Manager 时扩展。
 */
@ConfigurationProperties("crypto")
public record CryptoProperties(
    /** KEK（Key Encryption Key）的 Base64 编码，必须是 32 字节（AES-256）。 */
    Kek kek
) {

    public CryptoProperties {
        if (kek == null) {
            kek = new Kek(null);
        }
    }

    /**
     * KEK 配置。
     *
     * @param encoded Base64 编码的 KEK（32 字节）
     */
    public record Kek(
        String encoded
    ) {

        @PostConstruct
        void validate() {
            if (encoded != null && !encoded.isBlank()) {
                byte[] decoded = java.util.Base64.getDecoder().decode(encoded);
                if (decoded.length != 32) {
                    throw new IllegalStateException(
                        "crypto.kek.encoded must be exactly 32 bytes when decoded, got: " + decoded.length
                    );
                }
            }
        }
    }

    /** 检查 KEK 是否已配置。 */
    public boolean isKekConfigured() {
        return kek != null && kek.encoded() != null && !kek.encoded().isBlank();
    }
}
