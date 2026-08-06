package com.grassland.intelligence.homepage;

import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 60s 热点缓存（V17 intelligence_cached_hot_topics）。2h TTL + 过期降级。
 * 复刻 legacy readCachedHotTopics / persistCachedHotTopics。
 */
@Component
public class HotTopicsCacheRepository {

    private static final String PROVIDER = "60s";

    private final DatabaseClient db;

    public HotTopicsCacheRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 读最新缓存（含过期，调用方判 TTL 决定用不用）。 */
    public Mono<CachedEntry> readLatest() {
        return db.sql("SELECT items::text AS items, fetched_at FROM intelligence_cached_hot_topics"
                        + " WHERE provider = :provider ORDER BY fetched_at DESC LIMIT 1")
                .bind("provider", PROVIDER)
                .map(row -> new CachedEntry(
                        row.get("items", String.class),
                        toInstant(row.get("fetched_at", OffsetDateTime.class))))
                .one();
    }

    /** 覆盖写（DELETE 旧 + INSERT 新，与 legacy 同口径）。 */
    public Mono<Void> persist(String itemsJson) {
        return db.sql("DELETE FROM intelligence_cached_hot_topics WHERE provider = :provider")
                .bind("provider", PROVIDER)
                .then()
                .then(db.sql("INSERT INTO intelligence_cached_hot_topics(provider, items) "
                                + "VALUES (:provider, CAST(:items AS jsonb))")
                        .bind("provider", PROVIDER)
                        .bind("items", itemsJson)
                        .then());
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    /** 缓存条目。 */
    public record CachedEntry(String itemsJson, Instant fetchedAt) {}
}
