package com.grassland.crypto;

/**
 * 信封加密端口（GL-P3-AI-001 Phase 1）。
 *
 * <p>Envelope Encryption 模式：
 * <ul>
 *   <li>KEK（Key Encryption Key）：存储在 KMS/Secret Manager 或环境变量（首期）</li>
 *   <li>DEK（Data Encryption Key）：每次加密随机生成，用 KEK 加密后与密文一起存储</li>
 *   <li>密文格式：Base64(DEK_IV || DEK_Encrypted_With_KEK || Ciphertext || AuthTag)</li>
 * </ul>
 *
 * <p>首期实现使用 BouncyCastle AES-256-GCM；KEK 从环境变量 {@code CRYPTO_KEK_BASE64} 读取。
 */
public interface EnvelopeEncryption {

    /**
     * 加密明文（API Key）。
     *
     * @param plaintext 原始明文（如 API Key）
     * @return Base64 编码的密文
     * @throws IllegalArgumentException 如果 plaintext 为空
     */
    String encrypt(String plaintext);

    /**
     * 解密密文。
     *
     * @param ciphertext Base64 编码的密文
     * @return 原始明文
     * @throws IllegalArgumentException 如果 ciphertext 格式错误或解密失败
     */
    String decrypt(String ciphertext);

    /**
     * 读取密文中携带的密钥版本，不解密明文。
     *
     * @param ciphertext Base64 编码的密文
     * @return 版本标识（如 "v1"）
     */
    String keyVersion(String ciphertext);

    /**
     * 生成新的 DEK 并加密 KEK（密钥轮换时使用）。
     *
     * @return 新的 Key Version 标识符（如 "v2"）
     */
    String rotateKey();
}
