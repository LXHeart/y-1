package com.grassland.intelligence.ai.controlplane;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 平台凭据仓储（任务书 #47 S1）。
 *
 * <p>写入即 {@code version+1}（轮换与改连接信息都算变更），供 {@code ai_run.credential_version} 冻结（D7，S3 接线）。
 * 软删只置 {@code enabled=false}——历史 {@code platform_model_config} 行仍引用它，硬删会破坏 FK 与审计复现。
 */
@Component
public class PlatformProviderCredentialRepository {

    private static final String COLS =
            "id::text, name, provider, base_url, encrypted_key, key_version, masked_hint, "
                    + "enabled, version, updated_by, created_at, updated_at";

    private final DatabaseClient db;

    public PlatformProviderCredentialRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 全部有效凭据（admin 看板）。 */
    public Flux<PlatformProviderCredential> findAllEnabled() {
        return db.sql("SELECT " + COLS + " FROM platform_provider_credential"
                        + " WHERE enabled = true ORDER BY provider, name")
                .map(PlatformProviderCredentialRepository::map)
                .all();
    }

    /** 按 id 读有效凭据；停用或不存在 → 空。 */
    public Mono<PlatformProviderCredential> findEnabledById(UUID id) {
        return db.sql("SELECT " + COLS + " FROM platform_provider_credential"
                        + " WHERE id = CAST(:id AS uuid) AND enabled = true")
                .bind("id", id.toString())
                .map(PlatformProviderCredentialRepository::map)
                .one();
    }

    /** 按目的地读有效凭据（写入侧 find-or-create 的 find 半边）。 */
    public Mono<PlatformProviderCredential> findEnabledByDestination(String provider, String baseUrl) {
        return db.sql("SELECT " + COLS + " FROM platform_provider_credential"
                        + " WHERE provider = :provider AND base_url = :baseUrl AND enabled = true")
                .bind("provider", provider)
                .bind("baseUrl", baseUrl)
                .map(PlatformProviderCredentialRepository::map)
                .one();
    }

    /** 插入 version=1。唯一索引冲突（同目的地/同名）由调用方转 409。 */
    public Mono<UUID> create(
            String name, String provider, String baseUrl,
            String encryptedKey, String keyVersion, String maskedHint, String adminId) {
        return db.sql("""
                        INSERT INTO platform_provider_credential(
                            name, provider, base_url, encrypted_key, key_version, masked_hint,
                            enabled, version, updated_by
                        ) VALUES (
                            :name, :provider, :baseUrl, :encryptedKey, :keyVersion, :maskedHint,
                            true, 1, :adminId
                        )
                        RETURNING id::text
                        """)
                .bind("name", name)
                .bind("provider", provider)
                .bind("baseUrl", baseUrl)
                .bind("encryptedKey", nullable(encryptedKey, String.class))
                .bind("keyVersion", nullable(keyVersion, String.class))
                .bind("maskedHint", nullable(maskedHint, String.class))
                .bind("adminId", nullable(adminId, String.class))
                .map((row, meta) -> row.get("id", String.class))
                .one()
                .map(UUID::fromString);
    }
    /** 改连接信息（不含密钥，D5）→ version+1。行不存在/已停用返回 false。 */
    public Mono<Boolean> updateConnection(
            UUID id, String name, String provider, String baseUrl, String adminId) {
        return db.sql("""
                        UPDATE platform_provider_credential
                        SET name = :name, provider = :provider, base_url = :baseUrl,
                            version = version + 1, updated_by = :adminId, updated_at = now()
                        WHERE id = CAST(:id AS uuid) AND enabled = true
                        RETURNING id::text
                        """)
                .bind("id", id.toString())
                .bind("name", name)
                .bind("provider", provider)
                .bind("baseUrl", baseUrl)
                .bind("adminId", nullable(adminId, String.class))
                .map((row, meta) -> row.get("id", String.class))
                .one()
                .hasElement();
    }

    /** 轮换密钥 → version+1。行不存在/已停用返回 false。 */
    public Mono<Boolean> rotateKey(
            UUID id, String encryptedKey, String keyVersion, String maskedHint, String adminId) {
        return db.sql("""
                        UPDATE platform_provider_credential
                        SET encrypted_key = :encryptedKey, key_version = :keyVersion,
                            masked_hint = :maskedHint,
                            version = version + 1, updated_by = :adminId, updated_at = now()
                        WHERE id = CAST(:id AS uuid) AND enabled = true
                        RETURNING id::text
                        """)
                .bind("id", id.toString())
                .bind("encryptedKey", encryptedKey)
                .bind("keyVersion", keyVersion)
                .bind("maskedHint", maskedHint)
                .bind("adminId", nullable(adminId, String.class))
                .map((row, meta) -> row.get("id", String.class))
                .one()
                .hasElement();
    }

    /** 软删（enabled=false）→ version+1。调用方须先确认无 enabled 引用（D6）。 */
    public Mono<Boolean> disable(UUID id, String adminId) {
        return db.sql("""
                        UPDATE platform_provider_credential
                        SET enabled = false, version = version + 1,
                            updated_by = :adminId, updated_at = now()
                        WHERE id = CAST(:id AS uuid) AND enabled = true
                        RETURNING id::text
                        """)
                .bind("id", id.toString())
                .bind("adminId", nullable(adminId, String.class))
                .map((row, meta) -> row.get("id", String.class))
                .one()
                .hasElement();
    }

    /**
     * 引用该凭据的<b>有效</b>模型配置数（D6 拒删依据）。
     *
     * <p>只数 {@code enabled=true}：历史版本行永久引用其时点凭据，若一并计数则任何用过的凭据都永远删不掉。
     */
    public Mono<Long> countEnabledReferences(UUID id) {
        return db.sql("""
                        SELECT COUNT(*) AS n FROM platform_model_config
                        WHERE credential_id = CAST(:id AS uuid) AND enabled = true
                        """)
                .bind("id", id.toString())
                .map((row, meta) -> row.get("n", Long.class))
                .one();
    }

    private static PlatformProviderCredential map(Row row, RowMetadata meta) {
        return new PlatformProviderCredential(
                uuidFromString(row.get("id", String.class)),
                row.get("name", String.class),
                row.get("provider", String.class),
                row.get("base_url", String.class),
                row.get("encrypted_key", String.class),
                row.get("key_version", String.class),
                row.get("masked_hint", String.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                row.get("version", Long.class),
                row.get("updated_by", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static UUID uuidFromString(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
