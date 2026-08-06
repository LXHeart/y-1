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

    @BeforeEach
    void setup() {
        // 正确地提供 32 字节的 Base64 编码 KEK
        String kekBase64 = Base64.getEncoder().encodeToString(
            TEST_KEK.getBytes(StandardCharsets.UTF_8)
        );
        CryptoProperties properties = new CryptoProperties(
            new CryptoProperties.Kek(kekBase64)
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

    @Test
    @DisplayName("rotateKey 增加版本号")
    void rotateKey_incrementsVersion() {
        String v1 = encryption.rotateKey();
        assertThat(v1).isEqualTo("v2");

        String v2 = encryption.rotateKey();
        assertThat(v2).isEqualTo("v3");
    }

    @Test
    @DisplayName("可从密文头读取密钥版本且轮换后保持一致")
    void ciphertextVersionMatchesActiveVersion() {
        String legacyCiphertext = encryption.encrypt("legacy-secret");
        assertThat(encryption.keyVersion(legacyCiphertext)).isEqualTo("v1");

        assertThat(encryption.rotateKey()).isEqualTo("v2");
        String rotatedCiphertext = encryption.encrypt("rotated-secret");

        assertThat(encryption.keyVersion(rotatedCiphertext)).isEqualTo("v2");
        assertThat(encryption.decrypt(legacyCiphertext)).isEqualTo("legacy-secret");
        assertThat(encryption.decrypt(rotatedCiphertext)).isEqualTo("rotated-secret");
    }

    @Test
    @DisplayName("encrypt 长明文")
    void encrypt_longPlaintext() {
        String longKey = "sk-" + "a".repeat(100) + "-xyz";
        String ciphertext = encryption.encrypt(longKey);
        assertThat(encryption.decrypt(ciphertext)).isEqualTo(longKey);
    }
}
