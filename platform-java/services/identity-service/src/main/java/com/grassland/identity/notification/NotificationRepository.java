package com.grassland.identity.notification;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 通知数据访问（R2DBC {@link DatabaseClient} 手写 SQL，与 membership/ 风格一致）。草场 Slice 12。
 *
 * <p><b>越权在 SQL 层封死</b>：所有读写方法都把 {@code account_id = :me} 写进 WHERE，
 * 不依赖 controller 先查后判——「先 findById 再比 accountId」的写法一旦漏一处就是 IDOR，
 * 而这里连「读到别人的行」都做不到。{@link #markRead} 因此返回受影响行数，传入他人 id 只会得到 0。
 *
 * <p><b>jsonb 细节</b>：R2DBC 只能绑字符串，故写入 {@code CAST(:payload AS jsonb)}、
 * 读取 {@code payload::text} 再用 Jackson 解回 Map（镜像 {@code RecommenderProfileRepository.social_accounts}）。
 */
@Component
public class NotificationRepository {

    private static final String SELECT_COLS = """
            id::text, account_id::text, category, event_type, title, body, link_path,
            source_event_id, payload::text AS payload, read_at, created_at""";

    /** 单页上限：防止「limit=100000」把整个收件箱拉进内存。 */
    private static final int MAX_LIMIT = 50;
    private static final int DEFAULT_LIMIT = 20;

    private final DatabaseClient db;
    // 与 RecommenderProfileRepository 同款：Boot 4/Jackson 3 上下文里没有 com.fasterxml ObjectMapper bean
    // （自动配置产出的是 tools.jackson.databind.JsonMapper），JSON 串只存在于 DB↔本类之间，故本地 new。
    private final ObjectMapper mapper = new ObjectMapper();

    public NotificationRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 幂等插入：同一 {@code (sourceEventId, accountId)} 已存在时返回<b>空 Mono</b>（无异常）。
     *
     * <p>{@code ON CONFLICT DO NOTHING} 而不是捕获异常——被捕获的 INSERT 失败会把 R2DBC 事务置 rollback-only，
     * 而 Stage 2 的消费链要求「inbox 行 + 通知」同事务提交（见 {@code MembershipRepository.createIfAbsent} 同款理由）。
     *
     * <p>{@code sourceEventId} 为 null 时唯一索引（partial）不生效，每次都插入新行——系统通知按此语义。
     */
    public Mono<Notification> insertIfAbsent(
            String accountId,
            NotificationCategory category,
            String eventType,
            String title,
            String body,
            String linkPath,
            String sourceEventId,
            Map<String, Object> payload) {
        DatabaseClient.GenericExecuteSpec spec = db.sql("""
                        INSERT INTO notification(
                            account_id, category, event_type, title, body, link_path, source_event_id, payload)
                        VALUES (CAST(:acct AS uuid), :category, :eventType, :title, :body, :linkPath,
                                :sourceEventId, CAST(:payload AS jsonb))
                        ON CONFLICT (source_event_id, account_id) DO NOTHING
                        RETURNING %s
                        """.formatted(SELECT_COLS))
                .bind("acct", accountId)
                .bind("category", category.dbValue())
                .bind("eventType", eventType)
                .bind("title", title)
                .bind("payload", writePayload(payload));
        spec = bindNullable(spec, "body", body);
        spec = bindNullable(spec, "linkPath", linkPath);
        spec = bindNullable(spec, "sourceEventId", sourceEventId);
        return spec.map(this::map).one();
    }

    /**
     * 我的收件箱，按 {@code created_at DESC} keyset 分页。
     *
     * <p>用 {@code created_at} 游标而不是 OFFSET：新通知持续插入表头，OFFSET 会让下一页重复/漏行。
     * 同一微秒内多条时游标以 {@code id} 破平（复合游标），避免边界行被跳过。
     */
    public Flux<Notification> findByAccount(
            String accountId, boolean unreadOnly, Integer limit, Instant before, String beforeId) {
        boolean hasCursor = before != null;
        String cursorClause = hasCursor
                ? " AND (created_at < :before OR (created_at = :before AND id < CAST(:beforeId AS uuid)))"
                : "";
        DatabaseClient.GenericExecuteSpec spec = db.sql("SELECT " + SELECT_COLS
                        + " FROM notification WHERE account_id = CAST(:acct AS uuid)"
                        + (unreadOnly ? " AND read_at IS NULL" : "")
                        + cursorClause
                        + " ORDER BY created_at DESC, id DESC LIMIT :limit")
                .bind("acct", accountId)
                .bind("limit", clampLimit(limit));
        if (hasCursor) {
            spec = spec.bind("before", OffsetDateTime.ofInstant(before, java.time.ZoneOffset.UTC))
                    .bind("beforeId", beforeId == null ? new java.util.UUID(0L, 0L).toString() : beforeId);
        }
        return spec.map(this::map).all();
    }

    public Mono<Long> countUnread(String accountId) {
        return db.sql("SELECT COUNT(*)::bigint AS c FROM notification"
                        + " WHERE account_id = CAST(:acct AS uuid) AND read_at IS NULL")
                .bind("acct", accountId)
                .map(row -> row.get("c", Long.class))
                .one();
    }

    /**
     * 标记我的若干条为已读，返回实际更新行数。
     *
     * <p>{@code account_id = :me} 在 SQL 内 ⇒ 传入他人的通知 id 不会有任何效果（返回 0，不报错）。
     * {@code read_at IS NULL} ⇒ 重复调用第二次返回 0，且不覆盖首次已读时间。
     */
    public Mono<Long> markRead(String accountId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Mono.just(0L);
        }
        return db.sql("""
                        UPDATE notification SET read_at = now()
                        WHERE account_id = CAST(:acct AS uuid)
                          AND read_at IS NULL
                          AND id = ANY(CAST(:ids AS uuid[]))
                        """)
                .bind("acct", accountId)
                .bind("ids", ids.toArray(String[]::new))
                .fetch()
                .rowsUpdated();
    }

    public Mono<Long> markAllRead(String accountId) {
        return db.sql("UPDATE notification SET read_at = now()"
                        + " WHERE account_id = CAST(:acct AS uuid) AND read_at IS NULL")
                .bind("acct", accountId)
                .fetch()
                .rowsUpdated();
    }

    static int clampLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, limit));
    }

    private static DatabaseClient.GenericExecuteSpec bindNullable(
            DatabaseClient.GenericExecuteSpec spec, String name, String value) {
        return (value == null) ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }

    private Notification map(Readable row) {
        return new Notification(
                row.get("id", String.class),
                row.get("account_id", String.class),
                NotificationCategory.fromDb(row.get("category", String.class)),
                row.get("event_type", String.class),
                row.get("title", String.class),
                row.get("body", String.class),
                row.get("link_path", String.class),
                row.get("source_event_id", String.class),
                readPayload(row.get("payload", String.class)),
                toInstant(row.get("read_at", OffsetDateTime.class)),
                toInstant(row.get("created_at", OffsetDateTime.class)));
    }

    private String writePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid notification payload", error);
        }
    }

    private Map<String, Object> readPayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception error) {
            throw new IllegalArgumentException("invalid notification payload", error);
        }
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
