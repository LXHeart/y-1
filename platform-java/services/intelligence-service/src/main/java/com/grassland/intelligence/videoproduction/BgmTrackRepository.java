package com.grassland.intelligence.videoproduction;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * bgm_track 读写（任务书 #64 卡1/卡7，P3）。
 *
 * <p>{@code object_key} 直指对象存储、不进 media_reference：BGM 是平台运营资产，无 owner
 * account，不参与用户配额、不随用户删除。曲库**种子为空**，靠治理台上传。
 */
@Component
public class BgmTrackRepository {

    private static final String COLS = "id::text, name, mood_tags::text, object_key, content_type, "
            + "size_bytes, duration_ms, enabled, uploaded_by, created_at, updated_at";

    private final DatabaseClient db;

    public BgmTrackRepository(DatabaseClient db) {
        this.db = db;
    }

    /** 治理台上传落库（卡7）。{@code moodTags} 是受控值集的 JSON 数组文本，校验在 BgmTrack。 */
    public Mono<BgmTrack> create(String name, String moodTags, String objectKey, String contentType,
            long sizeBytes, Integer durationMs, String uploadedBy) {
        return db.sql("INSERT INTO bgm_track(name,mood_tags,object_key,content_type,size_bytes,"
                        + "duration_ms,uploaded_by) "
                        + "VALUES(:name,CAST(:moods AS jsonb),:key,:type,:size,:ms,:uploader) "
                        + "RETURNING " + COLS)
                .bind("name", name)
                .bind("moods", moodTags)
                .bind("key", objectKey)
                .bind("type", contentType)
                .bind("size", sizeBytes)
                .bind("ms", nullable(durationMs, Integer.class))
                .bind("uploader", nullable(uploadedBy, String.class))
                .map(BgmTrackRepository::map)
                .one();
    }

    public Mono<BgmTrack> findById(UUID id) {
        return db.sql("SELECT " + COLS + " FROM bgm_track WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString())
                .map(BgmTrackRepository::map)
                .one();
    }

    /** 用户可选曲目（卡9 选 BGM）：只回 enabled，走部分索引。 */
    public Flux<BgmTrack> findEnabled() {
        return db.sql("SELECT " + COLS + " FROM bgm_track WHERE enabled ORDER BY created_at DESC")
                .map(BgmTrackRepository::map)
                .all();
    }

    /** 治理台列表（卡7）：含已下架曲目。 */
    public Flux<BgmTrack> findAll(int limit, int offset) {
        return db.sql("SELECT " + COLS + " FROM bgm_track ORDER BY created_at DESC "
                        + "LIMIT :limit OFFSET :offset")
                .bind("limit", limit)
                .bind("offset", offset)
                .map(BgmTrackRepository::map)
                .all();
    }

    /** 上/下架：下架只影响新任务，已冻结 bgm_track_id 的在跑任务不受影响。 */
    public Mono<Boolean> setEnabled(UUID id, boolean enabled) {
        return db.sql("UPDATE bgm_track SET enabled=:enabled,updated_at=now() WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString())
                .bind("enabled", enabled)
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /**
     * 删除曲目行。**不删对象存储里的文件**——存量任务的 bgm_track_id 是裸 uuid、无 FK，
     * 直删会让历史成片的音源追溯不到；对象回收归卡10 的清理策略。
     */
    public Mono<Boolean> delete(UUID id) {
        return db.sql("DELETE FROM bgm_track WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString())
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    static BgmTrack map(Row r, RowMetadata m) {
        return new BgmTrack(
                UUID.fromString(r.get("id", String.class)),
                r.get("name", String.class),
                r.get("mood_tags", String.class),
                r.get("object_key", String.class),
                r.get("content_type", String.class),
                r.get("size_bytes", Long.class),
                r.get("duration_ms", Integer.class),
                Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
                r.get("uploaded_by", String.class),
                r.get("created_at", OffsetDateTime.class),
                r.get("updated_at", OffsetDateTime.class));
    }
}
