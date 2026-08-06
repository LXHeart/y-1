package com.grassland.intelligence.settings;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * user_settings 表数据访问（GL: settings/homepage 迁移）。
 *
 * <p>表由 legacy {@code server/sql/001_init_auth.sql} 创建，公共 schema，所有服务共用。
 * intelligence 直接读写（database-per-service 约定下，这张 legacy 表是跨服务共享的例外——
 * 与 backend_role/credits_account 同口径）。
 */
@Component
public class UserSettingsRepository {

    private final DatabaseClient db;

    public UserSettingsRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 读某账号某类型设置（jsonb → text）。无记录 → empty。 */
    public Mono<String> findByAccountAndType(String accountId, String settingsType) {
        return db.sql("SELECT settings_json::text AS json FROM user_settings"
                        + " WHERE user_id = CAST(:acct AS uuid) AND settings_type = :type LIMIT 1")
                .bind("acct", accountId)
                .bind("type", settingsType)
                .map(row -> row.get("json", String.class))
                .one();
    }

    /** upsert（ON CONFLICT DO UPDATE，version+1）。 */
    public Mono<Void> upsert(String accountId, String settingsType, String settingsJson) {
        return db.sql("""
                INSERT INTO user_settings(id, user_id, settings_type, settings_json)
                VALUES (gen_random_uuid(), CAST(:acct AS uuid), :type, CAST(:json AS jsonb))
                ON CONFLICT (user_id, settings_type) DO UPDATE
                    SET settings_json = CAST(:json AS jsonb),
                        version = user_settings.version + 1,
                        updated_at = now()
                """)
                .bind("acct", accountId)
                .bind("type", settingsType)
                .bind("json", settingsJson)
                .then();
    }
}
