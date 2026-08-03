package com.grassland.intelligence.ai.byok;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * AI Provider BYOK 密钥仓储（GL-P3-AI-001 Phase 1）。
 */
@Component
public class AiProviderKeyRepository {

    private static final String SELECT_COLS =
            "id::text, organization_id::text, owner_account_id::text, capability, provider, base_url, model, "
            + "encrypted_key, key_version, masked_hint, enabled, created_at, updated_at";

    private final DatabaseClient db;

    public AiProviderKeyRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 创建 BYOK 密钥。返回生成的 ID。 */
    public Mono<UUID> create(AiProviderKey key) {
        return db.sql("""
                INSERT INTO ai_provider_key(
                    organization_id, owner_account_id, capability, provider, base_url, model,
                    encrypted_key, key_version, masked_hint, enabled
                ) VALUES (
                    :orgId, :owner, :capability, :provider, :baseUrl, :model,
                    :encryptedKey, :keyVersion, :maskedHint, :enabled
                )
                RETURNING id::text
                """)
                .bind("orgId", Parameter.fromOrEmpty(key.organizationId(), String.class))  // 个人密钥可空
                .bind("owner", key.ownerAccountId())
                .bind("capability", key.capability())
                .bind("provider", key.provider())
                .bind("baseUrl", key.baseUrl())
                .bind("model", Parameter.fromOrEmpty(key.model(), String.class))  // 可空
                .bind("encryptedKey", key.encryptedKey())
                .bind("keyVersion", key.keyVersion())
                .bind("maskedHint", key.maskedHint())
                .bind("enabled", key.enabled())
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .map(UUID::fromString);
    }

    /** 按 ID 查询。 */
    public Mono<AiProviderKey> findById(UUID id) {
        return db.sql("SELECT " + SELECT_COLS + " FROM ai_provider_key WHERE id = CAST(:id AS uuid)")
                .bind("id", id.toString())
                .map(AiProviderKeyRepository::map)
                .one();
    }

    /** 按组织 + 能力查找有效密钥（用于运行时路由）。 */
    public Mono<AiProviderKey> findByOrganizationAndCapability(String organizationId, String capability) {
        // 普通字符串拼接：text block 会吃掉行尾空格，SELECT/列/FROM 会粘连成坏 SQL。
        return db.sql("SELECT " + SELECT_COLS
                + " FROM ai_provider_key"
                + " WHERE organization_id = :orgId"
                + " AND capability = :capability"
                + " AND enabled = true"
                + " ORDER BY created_at DESC"
                + " LIMIT 1")
                .bind("orgId", organizationId)
                .bind("capability", capability)
                .map(AiProviderKeyRepository::map)
                .one();
    }

    /** 按个人账号 + 能力查找有效密钥。 */
    public Mono<AiProviderKey> findByPersonalAndCapability(String ownerAccountId, String capability) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM ai_provider_key"
                + " WHERE organization_id IS NULL"
                + " AND owner_account_id = :owner"
                + " AND capability = :capability"
                + " AND enabled = true"
                + " ORDER BY created_at DESC"
                + " LIMIT 1")
                .bind("owner", ownerAccountId)
                .bind("capability", capability)
                .map(AiProviderKeyRepository::map)
                .one();
    }

    /** 列出用户的所有密钥（个人 + 组织）。 */
    public Flux<AiProviderKey> findByOwner(String ownerAccountId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM ai_provider_key"
                + " WHERE owner_account_id = :owner"
                + " ORDER BY created_at DESC")
                .bind("owner", ownerAccountId)
                .map(AiProviderKeyRepository::map)
                .all();
    }

    /** 更新配置（不含 apiKey）。返回是否更新成功。 */
    public Mono<Boolean> updateConfig(UUID id, String baseUrl, String model) {
        return db.sql("""
                UPDATE ai_provider_key
                SET base_url = :baseUrl,
                    model = :model,
                    updated_at = now()
                WHERE id = CAST(:id AS uuid)
                RETURNING id::text
                """)
                .bind("id", id.toString())
                .bind("baseUrl", baseUrl)
                .bind("model", Parameter.fromOrEmpty(model, String.class))
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    /** 更换密钥（密钥轮换）。返回是否更新成功。 */
    public Mono<Boolean> updateKey(UUID id, String newEncryptedKey, String newKeyVersion, String newMaskedHint) {
        return db.sql("""
                UPDATE ai_provider_key
                SET encrypted_key = :encryptedKey,
                    key_version = :keyVersion,
                    masked_hint = :maskedHint,
                    updated_at = now()
                WHERE id = CAST(:id AS uuid)
                RETURNING id::text
                """)
                .bind("id", id.toString())
                .bind("encryptedKey", newEncryptedKey)
                .bind("keyVersion", newKeyVersion)
                .bind("maskedHint", newMaskedHint)
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    /** 软删除（enabled=false）。返回是否删除成功。 */
    public Mono<Boolean> delete(UUID id) {
        return db.sql("""
                UPDATE ai_provider_key
                SET enabled = false,
                    updated_at = now()
                WHERE id = CAST(:id AS uuid) AND enabled = true
                RETURNING id::text
                """)
                .bind("id", id.toString())
                .map((r, meta) -> r.get("id", String.class))
                .one()
                .hasElement();
    }

    /** 检查用户是否拥有指定密钥。 */
    public Mono<Boolean> isOwner(UUID id, String ownerAccountId) {
        return db.sql("""
                SELECT owner_account_id::text
                FROM ai_provider_key
                WHERE id = CAST(:id AS uuid)
                """)
                .bind("id", id.toString())
                .map((r, meta) -> r.get("owner_account_id", String.class))
                .one()
                .map(ownerAccountId::equals)
                .defaultIfEmpty(false);
    }

    /** 检查用户是否可以管理指定密钥（个人密钥或组织成员）。 */
    public Mono<Boolean> canManage(UUID id, String ownerAccountId, String organizationId) {
        return db.sql("""
                SELECT organization_id::text, owner_account_id::text
                FROM ai_provider_key
                WHERE id = CAST(:id AS uuid)
                """)
                .bind("id", id.toString())
                .fetch().one()
                .map(r -> {
                    // fetch().one() 返回 Map<String,Object> 行投影（非 Row）。
                    String owner = (String) r.get("owner_account_id");
                    String org = (String) r.get("organization_id");
                    // 个人密钥：只有创建者可管理
                    if (org == null) {
                        return ownerAccountId.equals(owner);
                    }
                    // 组织密钥：需要组织成员关系（由 Controller 层验证）
                    return true;
                })
                .defaultIfEmpty(false);
    }

    private static AiProviderKey map(Row row, RowMetadata meta) {
        return new AiProviderKey(
                uuidFromString(row.get("id", String.class)),
                row.get("organization_id", String.class),
                row.get("owner_account_id", String.class),
                row.get("capability", String.class),
                row.get("provider", String.class),
                row.get("base_url", String.class),
                row.get("model", String.class),
                row.get("encrypted_key", String.class),
                row.get("key_version", String.class),
                row.get("masked_hint", String.class),
                row.get("enabled", Boolean.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static UUID uuidFromString(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
