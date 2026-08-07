package com.grassland.marketplace.reputation;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Lv5 邀请当前态；条件 UPSERT 同时覆盖首次写入和乐观锁更新。 */
@Component
public class Lv5AdmissionRepository {

    private final DatabaseClient db;

    public Lv5AdmissionRepository(DatabaseClient db) {
        this.db = db;
    }

    public Mono<Lv5Admission> find(String accountId) {
        return db.sql("""
                SELECT account_id::text, admitted, version, updated_by::text, note, updated_at
                FROM reputation_lv5_admission WHERE account_id = CAST(:accountId AS uuid)
                """).bind("accountId", accountId).map(Lv5AdmissionRepository::map).one();
    }

    /** 批量读取已有准入态；无记录的账号由 service 映射为未准入。 */
    public Mono<Map<String, Lv5Admission>> findAll(Collection<String> accountIds) {
        List<String> requested = accountIds.stream().distinct().toList();
        if (requested.isEmpty()) {
            return Mono.just(Map.of());
        }
        String parameters = IntStream.range(0, requested.size())
                .mapToObj(index -> "CAST(:accountId" + index + " AS uuid)")
                .collect(Collectors.joining(", "));
        GenericExecuteSpec spec = db.sql("""
                SELECT account_id::text, admitted, version, updated_by::text, note, updated_at
                FROM reputation_lv5_admission WHERE account_id IN (%s)
                """.formatted(parameters));
        for (int index = 0; index < requested.size(); index++) {
            spec = spec.bind("accountId" + index, requested.get(index));
        }
        return spec.map(Lv5AdmissionRepository::map).all()
                .collectMap(Lv5Admission::accountId, admission -> admission, LinkedHashMap::new)
                .map(Map::copyOf);
    }

    /** 已有准入记录的写前读取；首次写入仍由条件 UPSERT 解决竞争。 */
    public Mono<Lv5Admission> findForUpdate(String accountId) {
        return db.sql("""
                SELECT account_id::text, admitted, version, updated_by::text, note, updated_at
                FROM reputation_lv5_admission WHERE account_id = CAST(:accountId AS uuid)
                FOR UPDATE
                """).bind("accountId", accountId).map(Lv5AdmissionRepository::map).one();
    }

    /** 首次写 expectedVersion=0；已有行必须精确匹配当前版本，否则返回空。 */
    public Mono<Lv5Admission> update(String accountId, boolean admitted, long expectedVersion,
                                     String actorAccountId, String note) {
        GenericExecuteSpec spec = db.sql("""
                WITH updated AS (
                    UPDATE reputation_lv5_admission
                    SET admitted = :admitted,
                        version = version + 1,
                        updated_by = CAST(:actor AS uuid),
                        note = :note,
                        updated_at = now()
                    WHERE account_id = CAST(:accountId AS uuid)
                      AND version = :expected AND :expected > 0
                    RETURNING account_id::text, admitted, version, updated_by::text, note, updated_at
                ), inserted AS (
                    INSERT INTO reputation_lv5_admission(account_id, admitted, version, updated_by, note)
                    SELECT CAST(:accountId AS uuid), :admitted, 1, CAST(:actor AS uuid), :note
                    WHERE :expected = 0
                      AND NOT EXISTS (SELECT 1 FROM reputation_lv5_admission
                                      WHERE account_id = CAST(:accountId AS uuid))
                    ON CONFLICT(account_id) DO NOTHING
                    RETURNING account_id::text, admitted, version, updated_by::text, note, updated_at
                )
                SELECT * FROM updated UNION ALL SELECT * FROM inserted
                """).bind("accountId", accountId).bind("admitted", admitted)
                .bind("expected", expectedVersion).bind("actor", actorAccountId);
        spec = note == null ? spec.bindNull("note", String.class) : spec.bind("note", note);
        return spec.map(Lv5AdmissionRepository::map).one();
    }

    private static Lv5Admission map(Readable row) {
        Long version = row.get("version", Long.class);
        OffsetDateTime updatedAt = row.get("updated_at", OffsetDateTime.class);
        return new Lv5Admission(row.get("account_id", String.class),
                Boolean.TRUE.equals(row.get("admitted", Boolean.class)),
                version == null ? 0 : version.longValue(), row.get("updated_by", String.class),
                row.get("note", String.class), updatedAt == null ? null : updatedAt.toInstant());
    }
}
