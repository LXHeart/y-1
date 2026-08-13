package com.grassland.crypto;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.crypto.engines.AESEngine;
import org.bouncycastle.crypto.modes.GCMBlockCipher;
import org.bouncycastle.crypto.modes.GCMModeCipher;
import org.bouncycastle.crypto.params.AEADParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BouncyCastle 信封加密实现（GL-P3-AI-001 Phase 1）。
 *
 * <p>使用 AES-256-GCM 加密，密文格式：
 * <pre>
 * Base64(
 *   keyVersion(1 byte) ||
 *   dekIv(12 bytes) ||
 *   encryptedDek(32 bytes) ||
 *   ciphertextIv(12 bytes) ||
 *   ciphertext(n bytes) ||
 *   authTag(16 bytes)
 * )
 * </pre>
 *
 * <p>首期 KEK 从 {@code crypto.kek.encoded} 读取，由 {@link CryptoAutoConfiguration} 在该属性存在时装配。
 */
public final class BouncyCastleEnvelopeEncryption implements EnvelopeEncryption {

    private static final Logger log = LoggerFactory.getLogger(BouncyCastleEnvelopeEncryption.class);

    private static final int KEY_SIZE_BYTES = 32;        // AES-256
    private static final int GCM_IV_SIZE = 12;            // GCM 推荐 IV 长度
    private static final int GCM_TAG_SIZE = 16;           // GCM 认证标签长度
    private static final int DEK_SIZE_BYTES = 32;          // DEK 也是 32 字节

    private final CryptoProperties properties;
    private final byte[] kek;
    private final SecureRandom random;
    private final AtomicInteger keyVersion;

    public BouncyCastleEnvelopeEncryption(CryptoProperties properties) {
        this.properties = properties;
        this.random = new SecureRandom();
        this.keyVersion = new AtomicInteger(1);
        this.kek = decodeKek(properties.kek().encoded());
    }

    @PostConstruct
    void init() {
        log.info("Envelope encryption initialized with KEK from configuration (version={})", keyVersion.get());
    }

    @PreDestroy
    void destroy() {
        // 清零 KEK 内存
        if (kek != null) {
            java.util.Arrays.fill(kek, (byte) 0);
        }
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new IllegalArgumentException("plaintext cannot be blank");
        }

        byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);

        // 1. 生成随机 DEK
        byte[] dek = generateRandomDek();

        // 2. 生成 DEK 加密 IV
        byte[] dekIv = new byte[GCM_IV_SIZE];
        random.nextBytes(dekIv);

        // 3. 用 KEK 加密 DEK
        byte[] encryptedDek = encryptWithGcm(kek, dekIv, dek);

        // 4. 生成密文 IV
        byte[] ciphertextIv = new byte[GCM_IV_SIZE];
        random.nextBytes(ciphertextIv);

        // 5. 用 DEK 加密明文
        byte[] ciphertext = encryptWithGcm(dek, ciphertextIv, plaintextBytes);

        // 6. 组装：version(1) + dekIv(12) + encryptedDek(48) + ciphertextIv(12) + ciphertext
        // encryptedDek 长度 = DEK(32) + tag(16) = 48
        // ciphertext 长度 = plaintext.length + tag(16)
        ByteBuffer buffer = ByteBuffer.allocate(
            1 + GCM_IV_SIZE + encryptedDek.length + GCM_IV_SIZE + ciphertext.length
        );

        byte version = (byte) keyVersion.get();
        buffer.put(version);
        buffer.put(dekIv);
        buffer.put(encryptedDek);
        buffer.put(ciphertextIv);
        buffer.put(ciphertext);

        byte[] result = buffer.array();
        return Base64.getEncoder().encodeToString(result);
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("ciphertext cannot be blank");
        }

        byte[] decoded = Base64.getDecoder().decode(ciphertext);

        // 最小长度检查：version(1) + dekIv(12) + encryptedDek(48) + ciphertextIv(12) + tag(16) = 89
        int minLen = 1 + GCM_IV_SIZE + (DEK_SIZE_BYTES + GCM_TAG_SIZE) + GCM_IV_SIZE + GCM_TAG_SIZE;
        if (decoded.length < minLen) {
            throw new IllegalArgumentException("ciphertext too short, need at least " + minLen + " bytes");
        }

        ByteBuffer buffer = ByteBuffer.wrap(decoded);
        byte version = buffer.get();

        // 读取 DEK 部分：dekIv(12) + encryptedDek(48)
        byte[] dekIv = new byte[GCM_IV_SIZE];
        buffer.get(dekIv);
        byte[] encryptedDek = new byte[DEK_SIZE_BYTES + GCM_TAG_SIZE];  // 32 + 16 = 48
        buffer.get(encryptedDek);

        // 读取密文 IV
        byte[] ciphertextIv = new byte[GCM_IV_SIZE];
        buffer.get(ciphertextIv);

        // 剩余部分是密文（包含 tag）
        byte[] ciphertextBytes = new byte[decoded.length - buffer.position()];
        buffer.get(ciphertextBytes);

        // 解密 DEK
        byte[] dek = decryptWithGcm(kek, dekIv, encryptedDek);

        // 解密密文
        byte[] plaintextBytes = decryptWithGcm(dek, ciphertextIv, ciphertextBytes);

        return new String(plaintextBytes, StandardCharsets.UTF_8);
    }

    @Override
    public String keyVersion(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new IllegalArgumentException("ciphertext cannot be blank");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(ciphertext);
            if (decoded.length == 0) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            return "v" + Byte.toUnsignedInt(decoded[0]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ciphertext must be valid Base64 envelope data", e);
        }
    }

    @Override
    public String rotateKey() {
        int newVersion = keyVersion.incrementAndGet();
        log.info("Key version rotated to: v{}", newVersion);
        return "v" + newVersion;
    }

    /**
     * GCM 加密。
     *
     * @param key 加密密钥
     * @param iv IV（12 字节）
     * @param plaintext 明文
     * @return 密文（包含 16 字节 tag 在末尾）
     */
    private byte[] encryptWithGcm(byte[] key, byte[] iv, byte[] plaintext) {
        try {
            GCMModeCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
            cipher.init(true, new AEADParameters(new KeyParameter(key), GCM_TAG_SIZE * 8, iv));

            byte[] output = new byte[cipher.getOutputSize(plaintext.length)];
            int len = cipher.processBytes(plaintext, 0, plaintext.length, output, 0);
            len += cipher.doFinal(output, len);

            // 返回的 output 末尾包含 16 字节 tag
            byte[] result = new byte[len];
            System.arraycopy(output, 0, result, 0, len);
            return result;
        } catch (InvalidCipherTextException e) {
            throw new IllegalStateException("GCM encryption failed", e);
        }
    }

    /**
     * GCM 解密。
     *
     * @param key 解密密钥
     * @param iv IV（12 字节）
     * @param ciphertext 密文（末尾 16 字节是 tag）
     * @return 明文
     */
    private byte[] decryptWithGcm(byte[] key, byte[] iv, byte[] ciphertext) {
        try {
            GCMModeCipher cipher = GCMBlockCipher.newInstance(AESEngine.newInstance());
            cipher.init(false, new AEADParameters(new KeyParameter(key), GCM_TAG_SIZE * 8, iv));

            int outputSize = cipher.getOutputSize(ciphertext.length);
            byte[] output = new byte[outputSize];
            int len = cipher.processBytes(ciphertext, 0, ciphertext.length, output, 0);
            len += cipher.doFinal(output, len);

            byte[] result = new byte[len];
            System.arraycopy(output, 0, result, 0, len);
            return result;
        } catch (InvalidCipherTextException e) {
            throw new IllegalArgumentException("GCM decryption failed (invalid ciphertext or tampered)", e);
        }
    }

    /** 生成随机 DEK。 */
    private byte[] generateRandomDek() {
        byte[] dek = new byte[DEK_SIZE_BYTES];
        random.nextBytes(dek);
        return dek;
    }

    /** 解码 KEK。 */
    private static byte[] decodeKek(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalStateException("crypto.kek.encoded is required for envelope encryption");
        }
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("crypto.kek.encoded must be valid Base64", e);
        }
    }
}
