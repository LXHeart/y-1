package com.grassland.intelligence.homepage;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 首页热点平台配置仓储（任务书 #47 S7b）。单行表，固定 {@code id=1}。
 *
 * <p>{@link #findOrDefault} 无行时返回 {@link HomepageHotConfig#platformDefault()}——沿用改造前的
 * 硬编码默认 {@code 60s}，所以升级后未配置的环境行为逐字节不变。
 */
@Component
public class HomepageHotConfigRepository {

    private static final String COLS =
            "provider, alapi_token_encrypted, alapi_token_key_version, alapi_token_masked, "
                    + "version, updated_by, updated_at";

    private final DatabaseClient db;

    public HomepageHotConfigRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 读平台配置；无行 → 平台默认（60s，version=0）。 */
    public Mono<HomepageHotConfig> findOrDefault() {
        return db.sql("SELECT " + COLS + " FROM homepage_hot_config WHERE id = 1")
                .map(HomepageHotConfigRepository::map)
                .one()
                .defaultIfEmpty(HomepageHotConfig.platformDefault());
    }

    /**
     * 乐观锁 upsert。{@code expectedVersion=0} 表示「预期无行」；其余为预期当前版本。
     * 版本不符 → 空 Mono，由 controller 转 409。
     *
     * <p>{@code alapiTokenEncrypted} 传 null 表示<b>保持现有 token 不变</b>（同 BYOK 的
     * 「改连接信息不含密钥」口径）；要清空 token 走 {@link #clearAlapiToken}。
     */
    public Mono<HomepageHotConfig> upsert(
            String provider, String alapiTokenEncrypted, String alapiTokenKeyVersion,
            String alapiTokenMasked, long expectedVersion, String adminId) {
        if (expectedVersion == 0L) {
            return db.sql("""
                            INSERT INTO homepage_hot_config(
                                id, provider, alapi_token_encrypted, alapi_token_key_version,
                                alapi_token_masked, version, updated_by)
                            VALUES (1, :provider, :encrypted, :keyVersion, :masked, 1, :adminId)
                            ON CONFLICT (id) DO NOTHING
                            RETURNING """ + " " + COLS)
                    .bind("provider", provider)
                    .bind("encrypted", nullable(alapiTokenEncrypted, String.class))
                    .bind("keyVersion", nullable(alapiTokenKeyVersion, String.class))
                    .bind("masked", nullable(alapiTokenMasked, String.class))
                    .bind("adminId", nullable(adminId, String.class))
                    .map(HomepageHotConfigRepository::map)
                    .one();
        }
        // token 三列用 COALESCE：传 null 即保持不变，避免「只想换数据源」时把 token 抹掉
        return db.sql("""
                        UPDATE homepage_hot_config
                        SET provider = :provider,
                            alapi_token_encrypted = COALESCE(:encrypted, alapi_token_encrypted),
                            alapi_token_key_version = COALESCE(:keyVersion, alapi_token_key_version),
                            alapi_token_masked = COALESCE(:masked, alapi_token_masked),
                            version = version + 1,
                            updated_by = :adminId,
                            updated_at = now()
                        WHERE id = 1 AND version = :expectedVersion
                        RETURNING """ + " " + COLS)
                .bind("provider", provider)
                .bind("encrypted", nullable(alapiTokenEncrypted, String.class))
                .bind("keyVersion", nullable(alapiTokenKeyVersion, String.class))
                .bind("masked", nullable(alapiTokenMasked, String.class))
                .bind("expectedVersion", expectedVersion)
                .bind("adminId", nullable(adminId, String.class))
                .map(HomepageHotConfigRepository::map)
                .one();
    }

    /** 清空 ALAPI token（三列一起置 null）。 */
    public Mono<HomepageHotConfig> clearAlapiToken(long expectedVersion, String adminId) {
        return db.sql("""
                        UPDATE homepage_hot_config
                        SET alapi_token_encrypted = NULL,
                            alapi_token_key_version = NULL,
                            alapi_token_masked = NULL,
                            version = version + 1,
                            updated_by = :adminId,
                            updated_at = now()
                        WHERE id = 1 AND version = :expectedVersion
                        RETURNING """ + " " + COLS)
                .bind("expectedVersion", expectedVersion)
                .bind("adminId", nullable(adminId, String.class))
                .map(HomepageHotConfigRepository::map)
                .one();
    }

    private static HomepageHotConfig map(Row row, RowMetadata meta) {
        return new HomepageHotConfig(
                row.get("provider", String.class),
                row.get("alapi_token_encrypted", String.class),
                row.get("alapi_token_key_version", String.class),
                row.get("alapi_token_masked", String.class),
                row.get("version", Long.class),
                row.get("updated_by", String.class),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
