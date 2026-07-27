package com.grassland.identity.recommenderprofile;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 推荐官画像数据访问。
 *
 * <p>两处类型细节：{@code text[]} 用 {@code String[]} 绑定/读取；{@code jsonb} 只能绑字符串，
 * 故 SQL 里显式 {@code CAST(:social AS jsonb)}，读出来再用 Jackson 解回对象列表——
 * <b>JSON 字符串只存在于 DB 与本类之间</b>，不外泄到 API（本项目被「响应回字符串」坑过）。
 */
@Component
public class RecommenderProfileRepository {

    private static final String SELECT_COLS =
            "account_id::text, display_name, bio, content_tags, domain_tags,"
                    + " social_accounts::text AS social_accounts, updated_at";

    private static final TypeReference<List<SocialAccount>> SOCIAL_LIST = new TypeReference<>() {};

    private final DatabaseClient db;
    private final ObjectMapper mapper = new ObjectMapper();

    public RecommenderProfileRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<RecommenderProfile> findByAccount(String accountId) {
        return db.sql("SELECT " + SELECT_COLS + " FROM recommender_profile WHERE account_id = CAST(:acct AS uuid)")
                .bind("acct", accountId)
                .map(this::map).one();
    }

    /** 整份覆盖（PUT 语义），首次维护时懒创建。 */
    public Mono<RecommenderProfile> upsert(String accountId, UpdateRecommenderProfileRequest body) {
        String socialJson = writeSocial(body.socialAccounts());
        return db.sql("""
                INSERT INTO recommender_profile(account_id, display_name, bio, content_tags, domain_tags, social_accounts)
                VALUES (CAST(:acct AS uuid), :name, :bio, :content, :domain, CAST(:social AS jsonb))
                ON CONFLICT (account_id) DO UPDATE
                  SET display_name = :name, bio = :bio, content_tags = :content,
                      domain_tags = :domain, social_accounts = CAST(:social AS jsonb), updated_at = now()
                RETURNING %s
                """.formatted(SELECT_COLS))
                .bind("acct", accountId)
                .bind("name", body.displayName() == null ? "" : body.displayName())
                .bind("bio", body.bio() == null ? "" : body.bio())
                .bind("content", body.contentTags().toArray(String[]::new))
                .bind("domain", body.domainTags().toArray(String[]::new))
                .bind("social", socialJson)
                .map(this::map).one();
    }

    private RecommenderProfile map(Readable row) {
        return new RecommenderProfile(
                row.get("account_id", String.class),
                emptyToNull(row.get("display_name", String.class)),
                emptyToNull(row.get("bio", String.class)),
                toList(row.get("content_tags", String[].class)),
                toList(row.get("domain_tags", String[].class)),
                readSocial(row.get("social_accounts", String.class)),
                toInstant(row.get("updated_at", OffsetDateTime.class))
        );
    }

    private String writeSocial(List<SocialAccount> accounts) {
        try {
            return mapper.writeValueAsString(accounts == null ? List.of() : accounts);
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid social accounts", error);
        }
    }

    /** 坏 JSON 返回空列表：画像是展示数据，不该因为一行脏数据让整个报名列表打不开。 */
    private List<SocialAccount> readSocial(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, SOCIAL_LIST);
        } catch (Exception error) {
            return List.of();
        }
    }

    private static List<String> toList(String[] values) {
        return values == null ? List.of() : Arrays.stream(values).filter(v -> v != null && !v.isBlank()).toList();
    }

    /** DB 里用空串表示「没填」（绑 null 到 varchar 要额外处理），出到领域层统一成 null。 */
    private static String emptyToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
