package com.grassland.identity.kyb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.crypto.BouncyCastleEnvelopeEncryption;
import com.grassland.crypto.CryptoProperties;
import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.identity.auth.IdentityException;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * KYB 敏感字段信封加密。GL-P3-MERCHANT-001。
 *
 * <p>锁住 fail-closed 语义：**未配 KEK 时抛 503，绝不退化为明文落库**（对齐 intelligence BYOK）。
 */
class KybFieldCryptoTest {

    private static final String KEK = Base64.getEncoder().encodeToString(new byte[32]);

    private static KybFieldCrypto withKek() {
        EnvelopeEncryption envelope = new BouncyCastleEnvelopeEncryption(
                new CryptoProperties(new CryptoProperties.Kek(KEK)));
        return new KybFieldCrypto(provider(envelope));
    }

    private static KybFieldCrypto withoutKek() {
        return new KybFieldCrypto(provider(null));
    }

    /** 最小 ObjectProvider：只有 getIfAvailable 被 KybFieldCrypto 用到。 */
    private static ObjectProvider<EnvelopeEncryption> provider(EnvelopeEncryption value) {
        return new ObjectProvider<>() {
            @Override
            public EnvelopeEncryption getObject(Object... args) {
                return value;
            }

            @Override
            public EnvelopeEncryption getObject() {
                return value;
            }

            @Override
            public EnvelopeEncryption getIfAvailable() {
                return value;
            }

            @Override
            public EnvelopeEncryption getIfUnique() {
                return value;
            }
        };
    }

    @Test
    @DisplayName("加解密往返：密文不含明文，解密还原")
    void roundTrip() {
        EnvelopeEncryption envelope = new BouncyCastleEnvelopeEncryption(
                new CryptoProperties(new CryptoProperties.Kek(KEK)));
        KybFieldCrypto crypto = new KybFieldCrypto(provider(envelope));
        String plaintext = "310101199001011234";

        String ciphertext = crypto.encrypt(plaintext);

        // KybFieldCrypto 刻意不暴露 decrypt（读取侧只给掩码），往返用底层 envelope 验。
        assertThat(ciphertext).isNotNull().doesNotContain(plaintext);
        assertThat(envelope.decrypt(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("未配 KEK：抛 503，不返回明文")
    void failsClosedWithoutKek() {
        KybFieldCrypto crypto = withoutKek();

        assertThat(crypto.isAvailable()).isFalse();
        assertThatThrownBy(() -> crypto.encrypt("310101199001011234"))
                .isInstanceOf(IdentityException.class)
                .satisfies(e -> assertThat(((IdentityException) e).status()).isEqualTo(503))
                .hasMessageNotContaining("310101199001011234");
    }

    @Test
    @DisplayName("空白输入不加密，返回 null（可选字段留空是合法草稿）")
    void blankStaysNull() {
        assertThat(withKek().encrypt(null)).isNull();
        assertThat(withKek().encrypt("   ")).isNull();
        // 未配 KEK 也不该因为空白字段就 503——草稿里没填就不涉及加密。
        assertThat(withoutKek().encrypt(null)).isNull();
        assertThat(withoutKek().encrypt("")).isNull();
    }

    @Test
    @DisplayName("掩码只保留末 4 位；解密失败返回 **** 而不抛")
    void maskKeepsOnlyTail4() {
        KybFieldCrypto crypto = withKek();
        String ciphertext = crypto.encrypt("6222021234567890123");

        assertThat(crypto.maskTail4(ciphertext)).isEqualTo("****0123");
        assertThat(crypto.maskTail4(null)).isNull();
        // 垃圾密文（换 KEK/数据损坏）不能把整个响应打成 500。
        assertThat(crypto.maskTail4("not-a-ciphertext")).isEqualTo("****");
        assertThat(withoutKek().maskTail4(ciphertext)).isEqualTo("****");
    }

    @Test
    @DisplayName("同一明文两次加密密文不同（随机 DEK/IV），不可用于等值探测")
    void ciphertextIsNotDeterministic() {
        KybFieldCrypto crypto = withKek();
        assertThat(crypto.encrypt("310101199001011234"))
                .isNotEqualTo(crypto.encrypt("310101199001011234"));
    }
}
