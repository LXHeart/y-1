package com.grassland.intelligence.imageanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 飞书应用凭据只读访问（草场 intelligence Slice 6）。凭据存于 legacy {@code user_settings(settings_type='analysis')}
 * 的 {@code settings_json->integrations->feishu}，由 legacy {@code /api/settings/*} 管理（本 slice 不迁）。
 *
 * <p>与 identity 读 legacy {@code app_users}/{@code session} 同构：跨服务只读 legacy 表。表由 V2 {@code IF NOT EXISTS} 兜底创建，
 * 缺行/缺表→{@link Mono#empty}（controller 转 400「飞书应用凭证未配置」）。后续 settings 迁移后此仓储可移除。
 */
@Component
public class FeishuCredentialsRepository {

    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper();

    public FeishuCredentialsRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<FeishuCredentials> find(String accountId) {
        // user_settings 是 legacy-owned：空库若 legacy migration 尚未跑，表不存在。先查 regclass，避免导出端点 500；
        // user_id 是 uuid 列，按 text 比较避免非 uuid 形态 accountId 强转错误。
        return db.sql("SELECT to_regclass('public.user_settings') IS NOT NULL AS present")
                .map(r -> Boolean.TRUE.equals(r.get("present", Boolean.class)))
                .one()
                .filter(Boolean::booleanValue)
                .flatMap(ignored -> db.sql("""
                        SELECT settings_json::text FROM user_settings
                        WHERE user_id::text = :accountId AND settings_type = 'analysis'
                        ORDER BY updated_at DESC LIMIT 1
                        """)
                        .bind("accountId", accountId)
                        .map(r -> r.get("settings_json", String.class))
                        .one())
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
