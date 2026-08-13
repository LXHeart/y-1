package com.grassland.intelligence.imageanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper();

    public FeishuCredentialsRepository(DatabaseClient db) {
        this.db = db;
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
                .map(FeishuCredentialsRepository::parse);
    }

    private static FeishuCredentials parse(String json) {
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            JsonNode feishu = root.path("integrations").path("feishu");
            if (feishu.isMissingNode() || feishu.isNull()) {
                return new FeishuCredentials(null, null, null);
            }
            return new FeishuCredentials(
                    text(feishu, "appId"),
                    text(feishu, "appSecret"),
                    text(feishu, "folderToken"));
        } catch (Exception e) {
            return new FeishuCredentials(null, null, null);
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
