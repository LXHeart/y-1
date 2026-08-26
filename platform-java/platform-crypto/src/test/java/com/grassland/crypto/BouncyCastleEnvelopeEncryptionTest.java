package com.grassland.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BouncyCastleEnvelopeEncryption")
class BouncyCastleEnvelopeEncryptionTest {

    // 32 字节测试 KEK（将被 Base64 编码后传入）
    private static final String TEST_KEK = "32-byte-test-key-1234567890abcde";

    private BouncyCastleEnvelopeEncryption encryption;

    /** 32 字节 KEK 的 Base64（TEST_KEK 本身是原始字节串，不是 Base64）。 */
    private static String kekBase64() {
        return Base64.getEncoder().encodeToString(TEST_KEK.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setup() {
        CryptoProperties properties = new CryptoProperties(
            CryptoProperties.Kek.of(kekBase64())
        );
        encryption = new BouncyCastleEnvelopeEncryption(properties);
    }

    @Test
    @DisplayName("encrypt/decrypt 往返验证")
    void encryptDecrypt_roundTrip() {
        String plaintext = "sk-test-api-key-1234567890abcdef";
        String ciphertext = encryption.encrypt(plaintext);
        String decrypted = encryption.decrypt(ciphertext);
        assertThat(decrypted).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("encrypt 返回 Base64 密文")
    void encrypt_returnsBase64() {
        String plaintext = "test-key";
        String ciphertext = encryption.encrypt(plaintext);
        // Base64 解码不应抛异常
        assertThat(Base64.getDecoder().decode(ciphertext)).isNotEmpty();
    }

    @Test
    @DisplayName("encrypt 空或空白抛异常")
    void encrypt_blank_throws() {
        assertThatThrownBy(() -> encryption.encrypt(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encryption.encrypt(""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encryption.encrypt("  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("decrypt 空或空白抛异常")
    void decrypt_blank_throws() {
        assertThatThrownBy(() -> encryption.decrypt(null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> encryption.decrypt(""))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("decrypt 损坏密文抛异常")
    void decrypt_corrupted_throws() {
        assertThatThrownBy(() -> encryption.decrypt("not-base64!@#"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("decrypt 过短密文抛异常")
    void decrypt_tooShort_throws() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[10]);
        assertThatThrownBy(() -> encryption.decrypt(tooShort))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("多次加密产生不同密文（DEK 随机）")
    void multipleEncryptions_differentCiphertexts() {
        String plaintext = "sk-test-key";
        String c1 = encryption.encrypt(plaintext);
        String c2 = encryption.encrypt(plaintext);
        assertThat(c1).isNotEqualTo(c2);
        // 但都能解密回原明文
        assertThat(encryption.decrypt(c1)).isEqualTo(plaintext);
        assertThat(encryption.decrypt(c2)).isEqualTo(plaintext);
    }

    /**
     * 原用例断言 rotateKey() 递增版本号。那个行为已废除：它每次调用都把密文首字节 +1 却<b>不换</b>
     * KEK 材料，使版本字节与「用哪把 KEK 加密」失去对应；且计数器在内存里，重启归 1，同一把 KEK
     * 的密文会带上互相冲突的版本号。两个 BYOK controller 曾在用户轮换自己的 API key 时调它，
     * 把平台级版本号越推越高——纯语义错误。
     */
    @Test
    @DisplayName("rotateKey 是 no-op：版本号由 crypto.kek.version 决定，不被调用推高")
    void rotateKey_isNoOp() {
        assertThat(encryption.rotateKey()).isEqualTo("v1");
        assertThat(encryption.rotateKey()).isEqualTo("v1");

        // 调用后新密文的版本字节仍是配置值，不会漂移
        assertThat(encryption.keyVersion(encryption.encrypt("after-no-op"))).isEqualTo("v1");
    }

    @Test
    @DisplayName("密文头的版本字节来自配置的 crypto.kek.version")
    void ciphertextVersionComesFromConfiguration() {
        assertThat(encryption.keyVersion(encryption.encrypt("secret"))).isEqualTo("v1");

        EnvelopeEncryption v7 = new BouncyCastleEnvelopeEncryption(new CryptoProperties(
                new CryptoProperties.Kek(kekBase64(), 7, null)));
        assertThat(v7.keyVersion(v7.encrypt("secret"))).isEqualTo("v7");
    }

    /** 双 KEK 并存：轮换期新 KEK 写、旧 KEK 仍能读存量密文——这是 KEK 轮换不丢数据的前提。 */
    @Test
    @DisplayName("双 KEK 并存：新 KEK 写入，旧版本密文仍可解")
    void previousKekDecryptsLegacyCiphertext() {
        EnvelopeEncryption v1 = new BouncyCastleEnvelopeEncryption(new CryptoProperties(
                new CryptoProperties.Kek(kekBase64(), 1, null)));
        String legacy = v1.encrypt("legacy-secret");
        assertThat(v1.keyVersion(legacy)).isEqualTo("v1");

        // 轮换到 v2：新材料写入，旧材料挂在 previous 供解密
        String newKek = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";   // 32 字节全 0x02
        EnvelopeEncryption v2 = new BouncyCastleEnvelopeEncryption(new CryptoProperties(
                new CryptoProperties.Kek(newKek, 2, "1=" + kekBase64())));

        String fresh = v2.encrypt("fresh-secret");
        assertThat(v2.keyVersion(fresh)).isEqualTo("v2");
        assertThat(v2.decrypt(fresh)).isEqualTo("fresh-secret");
        assertThat(v2.decrypt(legacy)).isEqualTo("legacy-secret");   // 旧密文照样读得出
    }

    /**
     * 旧 KEK 未配置时<b>显式失败并点名缺失版本</b>，不回落当前 KEK——回落会把「配置缺了旧 KEK」
     * 伪装成「密文损坏」，让运维在轮换期查错方向。
     */
    @Test
    @DisplayName("缺对应版本的 KEK → 报错点名版本号，不静默回落当前 KEK")
    void missingPreviousKekFailsLoudly() {
        EnvelopeEncryption v1 = new BouncyCastleEnvelopeEncryption(new CryptoProperties(
                new CryptoProperties.Kek(kekBase64(), 1, null)));
        String legacy = v1.encrypt("legacy-secret");

        String newKek = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=";
        EnvelopeEncryption v2WithoutPrevious = new BouncyCastleEnvelopeEncryption(new CryptoProperties(
                new CryptoProperties.Kek(newKek, 2, null)));

        assertThatThrownBy(() -> v2WithoutPrevious.decrypt(legacy))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("v1")
                .hasMessageContaining("crypto.kek.previous");
    }

    @Test
    @DisplayName("encrypt 长明文")
    void encrypt_longPlaintext() {
        String longKey = "sk-" + "a".repeat(100) + "-xyz";
        String ciphertext = encryption.encrypt(longKey);
        assertThat(encryption.decrypt(ciphertext)).isEqualTo(longKey);
    }
}
