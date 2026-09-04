package com.grassland.identity.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 *
 * <p>任务书 #72 卡 A：行内聚合身份/组织归属（identity_profile / organization_membership /
 * organization owner 三路标量子查询），并提供 {@code status} / {@code identityType} 可选筛选
 * （缺省=不过滤，与既有调用完全兼容）。筛选谓词与行内聚合共用同一 EXISTS 常量，
 * 保证「按身份筛出的行」与「行内身份标记」永远同口径。
 *
 * <p>任务书 #72 卡 D 连坐：owned_orgs 以 id+name+status 结构返回（治理台详情抽屉的组织冻结/恢复
 * 按钮需要 orgId 定位端点）——additive，owned_org_names 保留（D10 只增不改）。
 */
@Component
public class AdminUserRepository {

    private static final String RECOMMENDER_EXISTS =
            "EXISTS(SELECT 1 FROM identity_profile p WHERE p.account_id = app_users.id AND p.identity_type = 'recommender')";
    private static final String MERCHANT_EXISTS =
            "EXISTS(SELECT 1 FROM identity_profile p WHERE p.account_id = app_users.id AND p.identity_type = 'merchant')";
    // organization_membership 无 active/软删列（V2 建表后仅 #48/#52 DML 改造），裸 EXISTS 即「属于任一组织成员池」
    private static final String MEMBERSHIP_EXISTS =
            "EXISTS(SELECT 1 FROM organization_membership m WHERE m.account_id = app_users.id)";

    /** jsonb 直读的驱动映射形态不稳，统一 ::text 出库后本地解析（SessionRepository 同款 service-local 实例）。 */
    private final ObjectMapper json = new ObjectMapper();

    private final DatabaseClient db;

    public AdminUserRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 过滤谓词：分页行查询与 COUNT 共用同一片段，保证 total 与列表同口径（防漂移）。 */
    private static String filterPredicate(String query, String status, String identityType) {
        StringBuilder where = new StringBuilder();
        if (query != null) {
            where.append(where.isEmpty() ? " WHERE " : " AND ")
                    .append("lower(coalesce(email,'') || ' ' || coalesce(display_name,'') || ' ' || id::text)\n")
                    .append("      LIKE lower(:query) ESCAPE E'\\\\'");
        }
        if (status != null) {
            where.append(where.isEmpty() ? " WHERE " : " AND ").append("status = :status");
        }
        if (identityType != null) {
            where.append(where.isEmpty() ? " WHERE " : " AND ").append(identityExists(identityType));
        }
        return where.toString();
    }

    /** identityType 筛选三值 → EXISTS 谓词（非法值在 controller 校 400，这里兜底同报错）。 */
    private static String identityExists(String identityType) {
        return switch (identityType) {
            case "recommender" -> RECOMMENDER_EXISTS;
            case "merchant" -> MERCHANT_EXISTS;
            case "member" -> MEMBERSHIP_EXISTS;
            default -> throw new IllegalArgumentException("identityType 仅支持 recommender/merchant/member");
        };
    }

    /** 用户分页（注册时间倒序，对齐 legacy ORDER BY created_at DESC，不换序）。 */
    public Mono<List<AdminUserRow>> findAll(String query, String status, String identityType, int limit, int offset) {
        GenericExecuteSpec spec = db.sql("""
                        SELECT id::text, email, display_name, role, status, created_at,
                               %s AS has_recommender,
                               %s AS has_merchant,
                               %s AS has_membership,
                               (SELECT string_agg(o.name, ', ') FROM organization o
                                 WHERE o.owner_account_id = app_users.id) AS owned_org_names,
                               (SELECT coalesce(json_agg(json_build_object(
                                         'id', o.id::text, 'name', o.name, 'status', o.status)
                                         ORDER BY o.created_at), '[]'::json)::text
                                  FROM organization o
                                 WHERE o.owner_account_id = app_users.id) AS owned_orgs
                          FROM app_users
                          %s
                         ORDER BY created_at DESC
                         LIMIT :limit OFFSET :offset
                        """.formatted(RECOMMENDER_EXISTS, MERCHANT_EXISTS, MEMBERSHIP_EXISTS,
                filterPredicate(query, status, identityType)))
                .bind("limit", limit).bind("offset", offset);
        // 动态 SQL 只 bind 实际出现在 SQL 里的命名参数（缺失标识符会抛 NoSuchElementException）。
        if (query != null) spec = spec.bind("query", query);
        if (status != null) spec = spec.bind("status", status);
        // identityType 只内联常量谓词，无绑定参数
        return spec
                .map((row, meta) -> new AdminUserRow(
                        row.get("id", String.class),
                        row.get("email", String.class),
                        row.get("display_name", String.class),
                        row.get("role", String.class),
                        row.get("status", String.class),
                        toInstant(row.get("created_at", OffsetDateTime.class)),
                        row.get("has_recommender", Boolean.class),
                        row.get("has_merchant", Boolean.class),
                        row.get("has_membership", Boolean.class),
                        row.get("owned_org_names", String.class),
                        parseOwnedOrgs(row.get("owned_orgs", String.class))))
                .all()
                .collectList();
    }

    /** 与 {@link #findAll(String, String, String, int, int)} 同 WHERE 口径的总数。 */
    public Mono<Long> countAll(String query, String status, String identityType) {
        GenericExecuteSpec spec = db.sql("""
                        SELECT COUNT(*) AS c
                          FROM app_users
                          %s
                        """.formatted(filterPredicate(query, status, identityType)));
        if (query != null) spec = spec.bind("query", query);
        if (status != null) spec = spec.bind("status", status);
        return spec.map((row, meta) -> row.get("c", Long.class)).one().defaultIfEmpty(0L);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** owned_orgs json 文本 → 结构列表；空/坏值一律回空列表（列表富化不因个别行脏数据 500）。 */
    private List<OwnedOrg> parseOwnedOrgs(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            return json.readValue(raw, json.getTypeFactory().constructCollectionType(List.class, OwnedOrg.class));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** admin 用户列表行（不含 credits，余额在 controller 层合并；后五列为任务书 #72 的身份/组织归属聚合）。 */
    public record AdminUserRow(
            String id, String email, String displayName, String role, String status, Instant createdAt,
            Boolean hasRecommender, Boolean hasMerchant, Boolean hasMembership, String ownedOrgNames,
            List<OwnedOrg> ownedOrgs) {}

    /** 账号名下主体（卡 D 详情抽屉的组织冻结/恢复入口数据源）。 */
    public record OwnedOrg(String id, String name, String status) {}
}
