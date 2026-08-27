package com.grassland.identity.organization;

import io.r2dbc.spi.Readable;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 主体成员账号前缀改名的连带重写（任务书 #51）。
 *
 * <p><b>为什么不是改一列</b>：{@code account_username.username} 存的是<b>完整账号名</b>
 * （{@code 前缀-登录名}），未绑真实邮箱的成员在 {@code app_users.email} 还有一份派生的占位值
 * {@code {账号名}@sub.grassland.invalid}（V44 注释：email 是共享表 NOT NULL UNIQUE 列，
 * identity 不可 ALTER，故以占位值满足约束）。只改 {@code organization.account_prefix}
 * 会让存量成员的登录名与新前缀脱节——他们的账号名永远停在旧前缀上。
 *
 * <p>三处必须同事务重写：前缀列（{@link OrganizationRepository#updateAccountPrefix}）、
 * 登录名旁表、占位邮箱。<b>已绑真实邮箱的成员邮箱不动</b>（他们的邮箱是自己的资产，
 * 与前缀无关），由 {@code LIKE '%@sub.grassland.invalid'} 过滤保证。
 *
 * <p>成员范围 = 该 org 的 {@code organization_membership} 行 ∪ 门店属于该 org 的
 * {@code store_membership} 行（纯门店成员不占组织席位，#49 模型）。
 */
@Component
public class OrganizationPrefixRewriteRepository {

    /** 占位邮箱域（与 {@code OrgSubAccountService.PLACEHOLDER_EMAIL_SUFFIX} 同值，此处只做 SQL 过滤）。 */
    private static final String PLACEHOLDER_EMAIL_SUFFIX = "@sub.grassland.invalid";

    /**
     * 本主体成员账号的子查询。UNION 天然去重（一个账号既是组织成员又挂门店时只算一次）。
     * 刻意不含 {@code organization.owner_account_id}——owner 是注册用户，账号名走邮箱通路，
     * 没有 account_username 行，重写 SQL 的过滤会自然跳过它。
     */
    private static final String MEMBER_ACCOUNTS = """
            SELECT m.account_id FROM organization_membership m
             WHERE m.organization_id = CAST(:org AS uuid)
            UNION
            SELECT sm.account_id FROM store_membership sm
              JOIN store s ON s.id = sm.store_id
             WHERE s.organization_id = CAST(:org AS uuid)
            """;

    private final DatabaseClient db;

    public OrganizationPrefixRewriteRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 重写登录名：{@code 旧前缀-x} → {@code 新前缀-x}。
     *
     * <p>用 {@code substring(... from length(:old) + 1)} 只替换开头的前缀段，保留 {@code -登录名}
     * 原样；<b>不用 {@code replace()}</b>——登录名里若含前缀字面（如前缀 {@code milk}、
     * 登录名 {@code milkman}）会把中间那段也换掉。{@code LIKE '旧前缀-%'} 是第二道闸：
     * 只碰确实带该前缀的行（存量异形行原样留着，交由运营单独处理，不在改名里悄悄改坏）。
     *
     * @return 实际重写的行数
     */
    public Mono<Long> rewriteMemberUsernames(String organizationId, String oldPrefix, String newPrefix) {
        return db.sql("""
                UPDATE account_username
                   SET username = :newPrefix || substring(username from length(:oldPrefix) + 1)
                 WHERE account_id IN (%s)
                   AND username LIKE :oldPrefixLike
                """.formatted(MEMBER_ACCOUNTS))
                .bind("org", organizationId)
                .bind("newPrefix", newPrefix)
                .bind("oldPrefix", oldPrefix)
                .bind("oldPrefixLike", oldPrefix + "-%")
                .fetch().rowsUpdated();
    }

    /**
     * 重写占位邮箱（同上的前缀段替换）。只碰 {@code .invalid} 占位行——已绑真实邮箱的成员
     * 邮箱是其本人资产，与前缀无关，绝不能动。
     *
     * @return 实际重写的行数
     */
    public Mono<Long> rewritePlaceholderEmails(String organizationId, String oldPrefix, String newPrefix) {
        return db.sql("""
                UPDATE app_users
                   SET email = :newPrefix || substring(email from length(:oldPrefix) + 1),
                       updated_at = NOW()
                 WHERE id IN (%s)
                   AND email LIKE :oldPrefixLike
                   AND email LIKE :placeholderLike
                """.formatted(MEMBER_ACCOUNTS))
                .bind("org", organizationId)
                .bind("newPrefix", newPrefix)
                .bind("oldPrefix", oldPrefix)
                .bind("oldPrefixLike", oldPrefix + "-%")
                .bind("placeholderLike", "%" + PLACEHOLDER_EMAIL_SUFFIX)
                .fetch().rowsUpdated();
    }

    /**
     * 运营台主体搜索（任务书 #51 拍板 B）：按主体名 / 前缀 / id 模糊命中。
     * 仿 {@code AdminUserRepository} 的 search 拼法（含 ESCAPE，调用方负责转义通配符）。
     * {@code query} 为 null 时列最近创建的若干主体（运营首次进面板即有内容）。
     */
    public Mono<List<AdminOrganizationRow>> searchForAdmin(String query, int limit) {
        String filter = query == null ? "" : """
                 WHERE lower(o.name || ' ' || o.account_prefix || ' ' || o.id::text)
                       LIKE lower(:query) ESCAPE E'\\\\'
                """;
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                SELECT o.id::text AS id, o.name, o.account_prefix, o.status, o.created_at,
                       (SELECT COUNT(*)::int FROM (
                            SELECT m.account_id FROM organization_membership m
                             WHERE m.organization_id = o.id
                            UNION
                            SELECT sm.account_id FROM store_membership sm
                              JOIN store s ON s.id = sm.store_id
                             WHERE s.organization_id = o.id
                        ) members) AS member_count
                  FROM organization o
                %s
                 ORDER BY o.created_at DESC
                 LIMIT :limit
                """.formatted(filter))
                .bind("limit", limit);
        if (query != null) {
            spec = spec.bind("query", query);
        }
        return spec.map(OrganizationPrefixRewriteRepository::mapAdminRow).all().collectList();
    }

    private static AdminOrganizationRow mapAdminRow(Readable row) {
        Integer memberCount = row.get("member_count", Integer.class);
        return new AdminOrganizationRow(
                row.get("id", String.class),
                row.get("name", String.class),
                row.get("account_prefix", String.class),
                row.get("status", String.class),
                memberCount == null ? 0 : memberCount);
    }

    /** 运营台主体行；{@code memberCount} 是「改前缀会影响多少人」的直接依据。 */
    public record AdminOrganizationRow(String id, String name, String accountPrefix, String status,
            int memberCount) {
    }
}
