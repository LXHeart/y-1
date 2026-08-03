package com.grassland.intelligence.ai.byok;

import java.time.Instant;
import java.util.UUID;

/**
 * AI Provider BYOK 密钥实体（GL-P3-AI-001 Phase 1）。
 *
 * <p>用户/组织自带模型密钥，使用 Envelope Encryption 加密存储。
 */
public record AiProviderKey(
    UUID id,
    String organizationId,       // 可空；个人用户 BYOK
    String ownerAccountId,      // 创建者（逻辑引用 app_users）
    String capability,          // text/image/image_generation/video
    String provider,            // openai-compatible/qwen 等
    String baseUrl,
    String model,               // 可空；某些 provider 不需要指定模型
    String encryptedKey,        // Base64 密文（Envelope Encryption）
    String keyVersion,          // KEK 版本（如 "v1"）
    String maskedHint,          // 掩码提示（sk-***xyz）
    boolean enabled,
    Instant createdAt,
    Instant updatedAt
) {
    /** 创建时使用（id 由数据库生成，created/updatedAt 由数据库默认）。 */
    public static AiProviderKey forCreate(
        String organizationId,
        String ownerAccountId,
        String capability,
        String provider,
        String baseUrl,
        String model,
        String encryptedKey,
        String keyVersion,
        String maskedHint
    ) {
        return new AiProviderKey(
            null,  // id 由数据库生成
            organizationId,
            ownerAccountId,
            capability,
            provider,
            baseUrl,
            model,
            encryptedKey,
            keyVersion,
            maskedHint,
            true,
            null,
            null
        );
    }

    /** 转换为 API 响应体（不含敏感信息）。 */
    public AiProviderKeyResponse toResponse() {
        return new AiProviderKeyResponse(
            id,
            organizationId,
            capability,
            provider,
            baseUrl,
            model,
            maskedHint,
            enabled,
            createdAt,
            updatedAt
        );
    }
}
