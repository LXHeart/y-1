package com.grassland.identity.admin;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 平台 admin 用户列表（迁自 legacy {@code server/src/services/admin.service.ts:29-49}）。
 *
 * <p>只读 {@code app_users}；credits 余额由 {@link FinanceCreditsAdminClient} 经 finance 批量端点取
 * （database-per-service：不跨服务 JOIN credits_account，虽然同库同 schema）。
 * {@code created_at} legacy admin 列表需要，{@link com.grassland.identity.user.LegacyUserLookup} 不取该列故独立查询。
 */
@Component
public class AdminUserRepository {

    private final DatabaseClient db;

    public AdminUserRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 全部用户按注册时间倒序（对齐 legacy ORDER BY created_at DESC）。 */
    public Mono<List<AdminUserRow>> findAll() {
        return db.sql("""
                        SELECT id::text, email, display_name, role, status, created_at
                          FROM app_users
                         ORDER BY created_at DESC
                        """)
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

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** admin 用户列表行（不含 credits，余额在 controller 层合并）。 */
    public record AdminUserRow(
            String id, String email, String displayName, String role, String status, Instant createdAt) {}
}
