package com.grassland.intelligence.ai.byok;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 个人 BYOK 开关仓储（任务书 #47 S5）。形状对齐 {@link AiOrgByokPolicyRepository}：小表 + version 乐观锁。
 *
 * <p>{@link #isOwnKeyEnabled} 是运行时热路径唯一入口：<b>无行即 true</b>（D14），所以从未碰过开关的
 * 账号行为与改造前完全一致。
 */
@Component
public class AiProviderPreferenceRepository {

    private static final String COLS = "account_id, capability, use_own_key, version, updated_at";

    private final DatabaseClient db;

    public AiProviderPreferenceRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 该能力是否启用个人密钥。<b>无行 → true</b>（D14）。
     *
     * <p>运行时只在「个人密钥确实存在」之后才调用——大多数账号没有个人密钥，先查密钥再查偏好
     * 可以让绝大多数请求省掉这次往返。
     */
    public Mono<Boolean> isOwnKeyEnabled(String accountId, String capability) {
        return db.sql("""
                        SELECT use_own_key FROM ai_provider_preference
                        WHERE account_id = :accountId AND capability = :capability
                        """)
                .bind("accountId", accountId)
                .bind("capability", capability)
                .map((row, meta) -> Boolean.TRUE.equals(row.get("use_own_key", Boolean.class)))
                .one()
                .defaultIfEmpty(true);
    }

    /** 某账号已显式配置的全部偏好（未配置的能力不出现，由调用方补默认）。 */
    public Flux<AiProviderPreference> findByAccount(String accountId) {
        return db.sql("SELECT " + COLS + " FROM ai_provider_preference"
                        + " WHERE account_id = :accountId ORDER BY capability")
                .bind("accountId", accountId)
                .map(AiProviderPreferenceRepository::map)
                .all();
    }

    public Mono<AiProviderPreference> find(String accountId, String capability) {
        return db.sql("SELECT " + COLS + " FROM ai_provider_preference"
                        + " WHERE account_id = :accountId AND capability = :capability")
                .bind("accountId", accountId)
                .bind("capability", capability)
                .map(AiProviderPreferenceRepository::map)
                .one();
    }

    /**
     * 乐观锁 upsert。{@code expectedVersion=0} 表示「预期无行」；其余表示预期的当前版本。
     * 版本不符（含并发插入撞 PK）→ 空 Mono，由 controller 转 409。
     */
    public Mono<AiProviderPreference> upsert(
            String accountId, String capability, boolean useOwnKey, long expectedVersion) {
        if (expectedVersion == 0L) {
            return db.sql("""
                            INSERT INTO ai_provider_preference(account_id, capability, use_own_key, version)
                            VALUES (:accountId, :capability, :useOwnKey, 1)
                            ON CONFLICT (account_id, capability) DO NOTHING
                            RETURNING """ + " " + COLS)
                    .bind("accountId", accountId)
                    .bind("capability", capability)
                    .bind("useOwnKey", useOwnKey)
                    .map(AiProviderPreferenceRepository::map)
                    .one();
        }
        return db.sql("""
                        UPDATE ai_provider_preference
                        SET use_own_key = :useOwnKey, version = version + 1, updated_at = now()
                        WHERE account_id = :accountId AND capability = :capability
                          AND version = :expectedVersion
                        RETURNING """ + " " + COLS)
                .bind("accountId", accountId)
                .bind("capability", capability)
                .bind("useOwnKey", useOwnKey)
                .bind("expectedVersion", expectedVersion)
                .map(AiProviderPreferenceRepository::map)
                .one();
    }

    private static AiProviderPreference map(Row row, RowMetadata meta) {
        return new AiProviderPreference(
                row.get("account_id", String.class),
                row.get("capability", String.class),
                Boolean.TRUE.equals(row.get("use_own_key", Boolean.class)),
                row.get("version", Long.class),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
