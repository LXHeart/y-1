package com.grassland.intelligence.ai.byok;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 组织 BYOK 回退策略仓储（ADR-D17）。乐观锁与 {@code AiModelBudgetRepository} 同款。 */
@Component
public class AiOrgByokPolicyRepository {

    private final DatabaseClient db;

    public AiOrgByokPolicyRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 无行 = 未配置（默认不允许回退）。 */
    public Mono<AiOrgByokPolicy> find(String organizationId) {
        return db.sql("""
                SELECT organization_id, allow_platform_fallback, version, updated_by_account_id, updated_at
                FROM ai_org_byok_policy
                WHERE organization_id = :orgId
                """)
                .bind("orgId", organizationId)
                .map(AiOrgByokPolicyRepository::map)
                .one();
    }

    /** 首次配置（expectedVersion=0 语义由调用方处理）；并发创建由 PK + DO NOTHING 转成 empty。 */
    public Mono<AiOrgByokPolicy> create(String organizationId, boolean allowPlatformFallback, String updatedBy) {
        return db.sql("""
                INSERT INTO ai_org_byok_policy(
                    organization_id, allow_platform_fallback, version, updated_by_account_id, updated_at)
                VALUES (:orgId, :allowFallback, 1, :updatedBy, now())
                ON CONFLICT (organization_id) DO NOTHING
                RETURNING organization_id, allow_platform_fallback, version, updated_by_account_id, updated_at
                """)
                .bind("orgId", organizationId)
                .bind("allowFallback", allowPlatformFallback)
                .bind("updatedBy", updatedBy)
                .map(AiOrgByokPolicyRepository::map)
                .one();
    }

    /** 乐观锁更新；version 不匹配返回 empty（调用方 409）。 */
    public Mono<AiOrgByokPolicy> update(
            String organizationId, boolean allowPlatformFallback, long expectedVersion, String updatedBy) {
        return db.sql("""
                UPDATE ai_org_byok_policy
                SET allow_platform_fallback = :allowFallback,
                    version = version + 1,
                    updated_by_account_id = :updatedBy,
                    updated_at = now()
                WHERE organization_id = :orgId AND version = :expectedVersion
                RETURNING organization_id, allow_platform_fallback, version, updated_by_account_id, updated_at
                """)
                .bind("orgId", organizationId)
                .bind("allowFallback", allowPlatformFallback)
                .bind("expectedVersion", expectedVersion)
                .bind("updatedBy", updatedBy)
                .map(AiOrgByokPolicyRepository::map)
                .one();
    }

    /** 关闭策略（删除行回到默认不允许）；version 不匹配返回 false。 */
    public Mono<Boolean> delete(String organizationId, long expectedVersion) {
        return db.sql("DELETE FROM ai_org_byok_policy"
                + " WHERE organization_id = :orgId AND version = :expectedVersion")
                .bind("orgId", organizationId)
                .bind("expectedVersion", expectedVersion)
                .fetch().rowsUpdated().map(count -> count > 0).defaultIfEmpty(false);
    }

    private static AiOrgByokPolicy map(Row row, RowMetadata meta) {
        return new AiOrgByokPolicy(
                row.get("organization_id", String.class),
                row.get("allow_platform_fallback", Boolean.class),
                row.get("version", Long.class),
                row.get("updated_by_account_id", String.class),
                row.get("updated_at", OffsetDateTime.class) == null
                        ? null
                        : row.get("updated_at", OffsetDateTime.class).toInstant()
        );
    }
}
