package com.grassland.intelligence.homepage;

import io.r2dbc.spi.Readable;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * {@code hot_items_snapshot} 仓储（缺口清偿之八）：60s 每次刷新归档一行分类后的 groups，
 * 供「今天/本周」历史聚合。append-only；插入时顺带清理保留窗口外的旧行（失败不影响主流程）。
 */
@Component
public class HotItemsSnapshotRepository {

    private final DatabaseClient db;

    public HotItemsSnapshotRepository(DatabaseClient db) {
        this.db = db;
    }

    public record SnapshotRow(UUID id, Instant fetchedAt, String groupsJson) {}

    public Mono<Void> append(String groupsJson, Instant fetchedAt) {
        return db.sql("INSERT INTO hot_items_snapshot(fetched_at, groups) "
                        + "VALUES (:fetchedAt, CAST(:groups AS jsonb))")
                .bind("fetchedAt", fetchedAt.atOffset(java.time.ZoneOffset.UTC))
                .bind("groups", groupsJson)
                .then();
    }

    public Flux<SnapshotRow> findSince(Instant since) {
        return db.sql("SELECT id::text, fetched_at, groups::text AS groups FROM hot_items_snapshot"
                        + " WHERE fetched_at >= :since ORDER BY fetched_at ASC")
                .bind("since", since.atOffset(java.time.ZoneOffset.UTC))
                .map(HotItemsSnapshotRepository::map)
                .all();
    }

    /** 保留窗口清理（append 时 opportunistic 调用；失败由调用方吞掉）。 */
    public Mono<Void> deleteBefore(Instant cutoff) {
        return db.sql("DELETE FROM hot_items_snapshot WHERE fetched_at < :cutoff")
                .bind("cutoff", cutoff.atOffset(java.time.ZoneOffset.UTC))
                .then();
    }

    private static SnapshotRow map(Readable row) {
        OffsetDateTime at = row.get("fetched_at", OffsetDateTime.class);
        return new SnapshotRow(
                UUID.fromString(row.get("id", String.class)),
                at == null ? null : at.toInstant(),
                row.get("groups", String.class));
    }
}
