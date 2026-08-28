package com.grassland.identity.admin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 平台 admin 用户列表（迁自 legacy {@code server/src/services/admin.service.ts:29-49}）。
 *
 * <p>只读 {@code app_users}；credits 余额由 {@link FinanceCreditsAdminClient} 经 finance 批量端点取
 * （database-per-service：不跨服务 JOIN credits_account，虽然同库同 schema）。
 * 管理列表需要 {@code created_at}，{@link com.grassland.identity.user.UserLookup} 不取该列，故独立查询。
 */
@Component
public class AdminUserRepository {

    private final DatabaseClient db;

    public AdminUserRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 搜索谓词：分页行查询与 COUNT 共用同一片段，保证 total 与列表同口径（防漂移）。 */
    private static String searchPredicate(String query) {
        return query == null ? "" : """
                         WHERE lower(coalesce(email,'') || ' ' || coalesce(display_name,'') || ' ' || id::text)
                               LIKE lower(:query) ESCAPE E'\\\\'
                """;
    }

    /** 用户分页（注册时间倒序，对齐 legacy ORDER BY created_at DESC，不换序）。 */
    public Mono<List<AdminUserRow>> findAll(String query, int limit, int offset) {
        GenericExecuteSpec spec = db.sql("""
                        SELECT id::text, email, display_name, role, status, created_at
                          FROM app_users
                        %s
                         ORDER BY created_at DESC
                         LIMIT :limit OFFSET :offset
                        """.formatted(searchPredicate(query)))
                .bind("limit", limit).bind("offset", offset);
        // 动态 SQL 只 bind 实际出现在 SQL 里的命名参数（缺失标识符会抛 NoSuchElementException）。
        if (query != null) spec = spec.bind("query", query);
        return spec
                .map((row, meta) -> new AdminUserRow(
                        row.get("id", String.class),
                        row.get("email", String.class),
                        row.get("display_name", String.class),
                        row.get("role", String.class),
                        row.get("status", String.class),
                        toInstant(row.get("created_at", OffsetDateTime.class))))
                .all()
                .collectList();
    }

    /** 与 {@link #findAll(String, int, int)} 同 WHERE 口径的总数。 */
    public Mono<Long> countAll(String query) {
        GenericExecuteSpec spec = db.sql("""
                        SELECT COUNT(*) AS c
                          FROM app_users
                        %s
                        """.formatted(searchPredicate(query)));
        if (query != null) spec = spec.bind("query", query);
        return spec.map((row, meta) -> row.get("c", Long.class)).one().defaultIfEmpty(0L);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** admin 用户列表行（不含 credits，余额在 controller 层合并）。 */
    public record AdminUserRow(
            String id, String email, String displayName, String role, String status, Instant createdAt) {}
}
