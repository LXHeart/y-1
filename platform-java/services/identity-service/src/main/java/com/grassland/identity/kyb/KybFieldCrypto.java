package com.grassland.identity.kyb;

import com.grassland.crypto.EnvelopeEncryption;
import com.grassland.identity.auth.IdentityException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * KYB 敏感字段加密与掩码。GL-P3-MERCHANT-001。
 *
 * <p>覆盖两个字段：{@code merchant_profile.legal_person_id_number}（法人身份证号）与
 * {@code withdrawal_account.account_number_encrypted}（收款账号）。二者都是 D-10 归类的强敏感 PII，
 * 列名早已承诺加密，此前却存明文——本类是唯一写入通道。
 *
 * <p><b>fail-closed</b>：{@link EnvelopeEncryption} bean 仅在 {@code crypto.kek.encoded} 非空时装配
 * （见 {@code CryptoAutoConfiguration}）。未配置 KEK 时 {@link #encrypt} 抛 503 而**不退化为存明文**——
 * 宁可让能力不可用，也不把身份证号/银行账号以明文落库。与 intelligence BYOK 端点同一 fail-closed 精神。
 *
 * <p>读取侧一律经 {@link #maskTail4}：完整明文永不出响应体，调用方只能看到末 4 位。
 */
@Component
public class KybFieldCrypto {

    private final ObjectProvider<EnvelopeEncryption> envelope;

    public KybFieldCrypto(ObjectProvider<EnvelopeEncryption> envelope) {
        this.envelope = envelope;
    }

    /** KEK 是否已配置（用于端点级能力门禁）。 */
    public boolean isAvailable() {
        return envelope.getIfAvailable() != null;
    }

    /**
     * 加密敏感明文。空白输入返回 {@code null}（该字段留空，合法）；
     * 有内容但 KEK 未配置 → 503，绝不落明文。
     */
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        EnvelopeEncryption crypto = envelope.getIfAvailable();
        if (crypto == null) {
            throw new IdentityException(503, "敏感字段加密未配置（CRYPTO_KEK_BASE64），暂不接受该字段");
        }
        return crypto.encrypt(plaintext.trim());
    }

    /**
     * 解密后取末 4 位掩码，如 {@code ****1234}。密文为空返回 {@code null}；
     * 解密失败（KEK 轮换/密文损坏）返回 {@code "****"} 而非抛错——读取路径不应因单字段不可解而整体 500。
     */
    public String maskTail4(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return null;
        }
        EnvelopeEncryption crypto = envelope.getIfAvailable();
        if (crypto == null) {
            return "****";
        }
        try {
            String plaintext = crypto.decrypt(ciphertext);
            if (plaintext == null || plaintext.isBlank()) {
                return "****";
            }
            String trimmed = plaintext.trim();
            return trimmed.length() <= 4 ? "****" : "****" + trimmed.substring(trimmed.length() - 4);
        } catch (RuntimeException e) {
            return "****";
        }
    }

    /** 解密后做常量时间规范化比对；完整证件号不会离开本方法或进入 OCR 落库结果。 */
    public boolean matches(String ciphertext, String candidate) {
        if (ciphertext == null || ciphertext.isBlank() || candidate == null || candidate.isBlank()) {
            return false;
        }
        EnvelopeEncryption crypto = envelope.getIfAvailable();
        if (crypto == null) {
            return false;
        }
        try {
            byte[] expected = normalize(crypto.decrypt(ciphertext)).getBytes(StandardCharsets.UTF_8);
            byte[] actual = normalize(candidate).getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException error) {
            return false;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("[^0-9A-Za-z]", "").toUpperCase(Locale.ROOT);
    }
}
