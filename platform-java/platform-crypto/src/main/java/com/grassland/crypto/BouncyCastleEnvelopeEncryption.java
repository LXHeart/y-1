package com.grassland.crypto;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
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
    /** 当前 KEK 版本，来自配置（<b>不是</b>进程内计数器——见 CryptoProperties 的说明）。 */
    private final int currentKeyVersion;
    /** 旧 KEK 台账 version → 材料，仅供解密；轮换期用于读旧密文。 */
    private final Map<Integer, byte[]> previousKeks;

    public BouncyCastleEnvelopeEncryption(CryptoProperties properties) {
        this.properties = properties;
        this.random = new SecureRandom();
        this.currentKeyVersion = properties.kek().currentVersion();
        this.kek = decodeKek(properties.kek().encoded());
        Map<Integer, byte[]> legacy = new LinkedHashMap<>();
        properties.kek().parsePrevious()
                .forEach((version, material) -> legacy.put(version, Base64.getDecoder().decode(material)));
        this.previousKeks = Map.copyOf(legacy);
    }

    @PostConstruct
    void init() {
        log.info("Envelope encryption initialized (currentKeyVersion=v{}, previousKeyVersions={})",
                currentKeyVersion, previousKeks.keySet());
    }

    @PreDestroy
    void destroy() {
        // 清零 KEK 内存
        if (kek != null) {
            java.util.Arrays.fill(kek, (byte) 0);
        }
        previousKeks.values().forEach(material -> java.util.Arrays.fill(material, (byte) 0));
    }

    /**
     * 按密文携带的版本号选择 KEK 材料。
     *
     * <p>找不到就<b>显式失败并点名缺失的版本</b>——不回落当前 KEK。回落只会把「配置缺了旧 KEK」
     * 伪装成「密文损坏」，让运维在轮换期查错方向。
     */
    private byte[] kekForVersion(int version) {
        if (version == currentKeyVersion) {
            return kek;
        }
        byte[] legacy = previousKeks.get(version);
        if (legacy == null) {
            throw new IllegalStateException(
                    "no KEK configured for ciphertext key version v" + version
                            + " (current=v" + currentKeyVersion + ", available previous="
                            + previousKeks.keySet() + "); configure crypto.kek.previous before decrypting");
        }
        return legacy;
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

        // 版本号取配置值：它必须与 this.kek 的材料严格对应，否则解密端选不出正确密钥
        byte version = (byte) currentKeyVersion;
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

        // 解密 DEK：按密文携带的 version 选择密钥材料（双 KEK 并存，轮换期旧密文仍可读）。
        // 改造前这里恒用当前 kek、完全忽略已读出的 version 字节——那让 version 沦为装饰。
        byte[] dek = decryptWithGcm(kekForVersion(Byte.toUnsignedInt(version)), dekIv, encryptedDek);

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

    /**
     * {@inheritDoc}
     *
     * <p><b>不再递增进程内计数器。</b>KEK 版本由 {@code crypto.kek.version} 配置决定——原实现每次调用
     * 都把版本号 +1 却不换密钥材料，导致密文首字节与「用哪把 KEK 加密」失去对应关系；且计数器在内存里，
     * 重启归 1，同一把 KEK 的密文会带上互相冲突的版本号。
     *
     * <p>真正的 KEK 轮换是运维流程（配 {@code crypto.kek.previous} → 重加密存量 → 移除旧版本），
     * 不是一次方法调用。本方法保留只为不破坏接口，返回当前版本。
     */
    @Override
    public String rotateKey() {
        log.warn("rotateKey() is a no-op: KEK version comes from crypto.kek.version."
                + " Rotate the platform KEK through the operations runbook, not this call.");
        return "v" + currentKeyVersion;
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
