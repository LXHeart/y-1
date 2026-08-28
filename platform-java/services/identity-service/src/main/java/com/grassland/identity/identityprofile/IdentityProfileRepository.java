package com.grassland.identity.identityprofile;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * identity_profile 数据访问（R2DBC {@link DatabaseClient} 手写 SQL，与 organization/ 风格一致）。
 * 一个账号每种 identity_type 至多一条（UNIQUE(account_id, identity_type)）。
 */
@Component
public class IdentityProfileRepository {

    private static final String SELECT_COLS =
            "id::text, account_id::text, identity_type, organization_id::text, status, created_at, updated_at";

    private final DatabaseClient db;

    public IdentityProfileRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 开通身份。organizationId 可空（商家身份可关联 org，推荐官为空）。 */
    public Mono<IdentityProfile> create(String accountId, String identityType, String organizationId) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO identity_profile(id, account_id, identity_type, organization_id, status)
                VALUES (CAST(:id AS uuid), CAST(:acct AS uuid), :type, CAST(:org AS uuid), 'active')
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("id", id).bind("acct", accountId).bind("type", identityType);
        if (organizationId == null) {
            spec = spec.bindNull("org", UUID.class);
        } else {
            spec = spec.bind("org", organizationId);
        }
        return spec.map(IdentityProfileRepository::map).one();
    }

    public Flux<IdentityProfile> findByAccount(String accountId) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM identity_profile WHERE account_id = CAST(:acct AS uuid) ORDER BY created_at")
                .bind("acct", accountId)
                .map(IdentityProfileRepository::map).all();
    }

    public Mono<IdentityProfile> findByAccountAndType(String accountId, String identityType) {
        return db.sql("SELECT " + SELECT_COLS
                + " FROM identity_profile WHERE account_id = CAST(:acct AS uuid) AND identity_type = :type")
                .bind("acct", accountId).bind("type", identityType)
                .map(IdentityProfileRepository::map).one();
    }

    public Mono<Boolean> existsByAccountAndType(String accountId, String identityType) {
        return db.sql("SELECT EXISTS (SELECT 1 FROM identity_profile"
                + " WHERE account_id = CAST(:acct AS uuid) AND identity_type = :type)::boolean AS e")
                .bind("acct", accountId).bind("type", identityType)
                .map(row -> row.get("e", Boolean.class)).one();
    }

    /**
     * 把商家身份档案绑到 org（仅当 organization_id 仍为 NULL 时改写；已绑定的行不动）。
     *
     * <p>堵「登录先开通（不带 org）→ 之后才建主体」序列的漏点：建主体时回调此方法回填，
     * 让断言（edge 每请求实时查库）带上 org。无档案时影响 0 行（之后开通走带 org 路径），
     * 谓词 IS NULL 保证可重复调用。
     */
    public Mono<Void> bindOrganizationIfAbsent(String accountId, String organizationId) {
        return db.sql("UPDATE identity_profile SET organization_id = CAST(:org AS uuid), updated_at = now()"
                + " WHERE account_id = CAST(:acct AS uuid) AND identity_type = 'merchant'"
                + " AND organization_id IS NULL")
                .bind("acct", accountId).bind("org", organizationId)
                .then();
    }

    private static IdentityProfile map(Readable row) {
        return new IdentityProfile(
                row.get("id", String.class),
                row.get("account_id", String.class),
                row.get("identity_type", String.class),
                row.get("organization_id", String.class),
                row.get("status", String.class),
                toInstant(row.get("created_at", OffsetDateTime.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
