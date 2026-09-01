package com.grassland.intelligence.videoproduction;

import static com.grassland.intelligence.config.R2dbcBindings.nullable;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import java.util.List;
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

    /** 治理台列表（可选名称模糊）；count 与 search 同口径。 */
    public Mono<Long> count(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return db.sql("SELECT COUNT(*) AS total FROM bgm_track")
                    .map(row -> row.get("total", Long.class)).one();
        }
        return db.sql("SELECT COUNT(*) AS total FROM bgm_track WHERE name ILIKE :kw")
                .bind("kw", "%" + keyword.trim() + "%")
                .map(row -> row.get("total", Long.class)).one();
    }

    public Flux<BgmTrack> search(String keyword, int limit, long offset) {
        if (keyword == null || keyword.isBlank()) {
            return db.sql("SELECT " + COLS + " FROM bgm_track "
                            + "ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
                    .bind("limit", limit).bind("offset", offset)
                    .map(BgmTrackRepository::map).all();
        }
        return db.sql("SELECT " + COLS + " FROM bgm_track WHERE name ILIKE :kw "
                        + "ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
                .bind("kw", "%" + keyword.trim() + "%")
                .bind("limit", limit).bind("offset", offset)
                .map(BgmTrackRepository::map).all();
    }

    /** 治理台编辑：名称/情绪标签/启停。 */
    public Mono<Boolean> updateDetails(UUID id, String name, String moodTags, Boolean enabled) {
        return db.sql("UPDATE bgm_track SET name=COALESCE(:name,name),"
                        + "mood_tags=COALESCE(CAST(:moods AS jsonb),mood_tags),"
                        + "enabled=COALESCE(:enabled,enabled),updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString())
                .bind("name", nullable(name, String.class))
                .bind("moods", nullable(moodTags, String.class))
                .bind("enabled", nullable(enabled, Boolean.class))
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    /** 成片任务引用计数（删除守卫：>0 时仅停用）。 */
    public Mono<Long> countTaskReferences(UUID id) {
        return db.sql("SELECT COUNT(*) AS total FROM video_production_task "
                        + "WHERE bgm_track_id=CAST(:id AS uuid)")
                .bind("id", id.toString())
                .map(row -> row.get("total", Long.class)).one();
    }

    /**
     * 按情绪标签随机取一首启用曲（选曲服务用）。刻意不用 {@code ?|} 操作符——R2DBC 会把
     * {@code ?} 当参数占位符，改走 jsonb_array_elements_text + ANY。
     */
    public Mono<BgmTrack> pickRandomByAnyMood(List<String> moods) {
        String arrayLiteral = arrayLiteral(moods);
        return db.sql("SELECT " + COLS + " FROM bgm_track WHERE enabled=true "
                        + "AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(mood_tags) m "
                        + "WHERE m = ANY(" + arrayLiteral + ")) "
                        + "ORDER BY random() LIMIT 1")
                .map(BgmTrackRepository::map).one();
    }

    /** 随机取任意启用曲（无匹配回落）。 */
    public Mono<BgmTrack> pickRandomAny() {
        return db.sql("SELECT " + COLS + " FROM bgm_track WHERE enabled=true "
                        + "ORDER BY random() LIMIT 1")
                .map(BgmTrackRepository::map).one();
    }

    /** ARRAY[...] 字面量由受控情绪词表拼出（BgmTrack.MOODS 白名单校验过，无注入面）。 */
    private static String arrayLiteral(List<String> moods) {
        StringBuilder literal = new StringBuilder("ARRAY[");
        for (int index = 0; index < moods.size(); index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append('\'').append(moods.get(index).replace("'", "''")).append('\'');
        }
        return literal.append(']').toString();
    }

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
