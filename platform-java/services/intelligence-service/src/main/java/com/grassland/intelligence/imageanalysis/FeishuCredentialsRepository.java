package com.grassland.intelligence.imageanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.crypto.EnvelopeEncryption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 飞书应用凭据只读访问（草场 intelligence Slice 6）。凭据存于 Java bootstrap 管理的
 * {@code user_settings(settings_type='analysis')} 的 {@code settings_json->integrations->feishu}，
 * 由 intelligence {@code /api/settings/*} 管理。
 *
 * <p>缺行返回 {@link Mono#empty}（controller 转 400「飞书应用凭证未配置」）。
 */
@Component
public class FeishuCredentialsRepository {

    private static final Logger logger = LoggerFactory.getLogger(FeishuCredentialsRepository.class);

    private final DatabaseClient db;
    private final ObjectProvider<EnvelopeEncryption> encryptionProvider;

    public FeishuCredentialsRepository(
            DatabaseClient db, ObjectProvider<EnvelopeEncryption> encryptionProvider) {
        this.db = db;
        this.encryptionProvider = encryptionProvider;
    }

    public Mono<FeishuCredentials> find(String accountId) {
        // user_id 是 uuid 列，按 text 比较避免非 uuid 形态 accountId 强转错误。
        return db.sql("""
                SELECT settings_json::text FROM user_settings
                WHERE user_id::text = :accountId AND settings_type = 'analysis'
                ORDER BY updated_at DESC LIMIT 1
                """)
                .bind("accountId", accountId)
                .map(r -> r.get("settings_json", String.class))
                .one()
                .map(this::parse);
    }

    /**
     * 解析飞书凭据。任务书 #47 S7：{@code appSecret} <b>密文优先、明文回落</b>。
     *
     * <p>{@code LegacySecretMigrationRunner} 把密文写进独立键 {@code appSecretEncrypted} 而不原地替换
     * ——原地加密后本方法无法区分「已加密」与「还是明文」，试解密再回落是启发式的、不可靠。
     * 独立键让优先级明确：有密文就解密用，没有才回落明文（V51 清空明文后回落分支自然失效）。
     */
    private FeishuCredentials parse(String json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            JsonNode feishu = root.path("integrations").path("feishu");
            if (feishu.isMissingNode() || feishu.isNull()) {
                return new FeishuCredentials(null, null, null);
            }
            return new FeishuCredentials(
                    text(feishu, "appId"),
                    resolveAppSecret(feishu),
                    text(feishu, "folderToken"));
        } catch (Exception e) {
            return new FeishuCredentials(null, null, null);
        }
    }

    private String resolveAppSecret(JsonNode feishu) {
        String encrypted = text(feishu, "appSecretEncrypted");
        String plaintext = text(feishu, "appSecret");
        if (encrypted == null) {
            return plaintext;
        }
        EnvelopeEncryption crypto = encryptionProvider.getIfAvailable();
        if (crypto == null) {
            // KEK 不可用：回落明文（若还在）而不是让导出功能整体不可用
            logger.warn("Feishu appSecret ciphertext present but KEK unavailable; falling back to plaintext");
            return plaintext;
        }
        try {
            return crypto.decrypt(encrypted);
        } catch (RuntimeException e) {
            // 解密失败不吞：回落明文并告警，便于运维在 V51 之前发现问题（不记密钥值）
            logger.warn("Feishu appSecret decryption failed, falling back to plaintext: {}", e.getMessage());
            return plaintext;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** 飞书应用凭据（来自用户设置，非 env）。appId/appSecret 必填，folderToken 可选。 */
    public record FeishuCredentials(String appId, String appSecret, String folderToken) {
        public boolean configured() {
            return appId != null && !appId.isBlank() && appSecret != null && !appSecret.isBlank();
        }
    }
}
