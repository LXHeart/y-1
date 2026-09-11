package com.grassland.intelligence.videoproduction;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 每镜制作来源存取（任务书 #100 C100-11 / V74 / API-10）。
 *
 * <p>写入口 {@link #upsert}（shot_id 主键幂等覆盖）与 {@link #deleteByShotIds}（删镜级联，
 * 编辑闸事务内调用）。缺行 = generated（服务层读侧补默认，不回填行）。
 */
@Component
public class VideoShotSourceRepository {

    private final DatabaseClient db;

    public VideoShotSourceRepository(DatabaseClient db) {
        this.db = db;
    }

    private static final String COLS = "shot_id::text, storyboard_id::text, source_kind, media_id::text, "
            + "trim_start_ms, trim_end_ms, audio_mode, created_at, updated_at";

    public Mono<VideoShotSource> upsert(VideoShotSource source) {
        org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec spec = db.sql(
                        "INSERT INTO video_shot_media_source(shot_id, storyboard_id, source_kind, media_id, "
                        + "trim_start_ms, trim_end_ms, audio_mode) VALUES (CAST(:shot AS uuid), "
                        + "CAST(:storyboard AS uuid), :kind, CAST(:media AS uuid), :trimStart, :trimEnd, :audio) "
                        + "ON CONFLICT (shot_id) DO UPDATE SET source_kind = EXCLUDED.source_kind, "
                        + "media_id = EXCLUDED.media_id, trim_start_ms = EXCLUDED.trim_start_ms, "
                        + "trim_end_ms = EXCLUDED.trim_end_ms, audio_mode = EXCLUDED.audio_mode, "
                        + "updated_at = now() "
                        + "RETURNING " + COLS)
                .bind("shot", source.shotId().toString())
                .bind("storyboard", source.storyboardId().toString())
                .bind("kind", source.sourceKind())
                .bind("audio", source.audioMode());
        spec = source.mediaId() == null
                ? spec.bindNull("media", java.util.UUID.class)
                : spec.bind("media", source.mediaId().toString());
        spec = source.trimStartMs() == null
                ? spec.bindNull("trimStart", Long.class)
                : spec.bind("trimStart", source.trimStartMs());
        spec = source.trimEndMs() == null
                ? spec.bindNull("trimEnd", Long.class)
                : spec.bind("trimEnd", source.trimEndMs());
        return spec.map(VideoShotSourceRepository::map).one();
    }

    public Flux<VideoShotSource> findByStoryboard(UUID storyboardId) {
        return db.sql("SELECT " + COLS + " FROM video_shot_media_source "
                        + "WHERE storyboard_id = CAST(:storyboard AS uuid)")
                .bind("storyboard", storyboardId.toString())
                .map(VideoShotSourceRepository::map)
                .all();
    }

    /** 删镜级联清来源（编辑闸事务内）；返回删除行数。命名参数占位（DatabaseClient 不支持 ?）。 */
    public Mono<Long> deleteByShotIds(java.util.Collection<UUID> shotIds) {
        if (shotIds.isEmpty()) {
            return Mono.just(0L);
        }
        java.util.List<String> placeholders = new java.util.ArrayList<>();
        java.util.List<String> ids = new java.util.ArrayList<>();
        int index = 0;
        for (UUID shotId : shotIds) {
            placeholders.add("CAST(:id" + index + " AS uuid)");
            ids.add(shotId.toString());
            index += 1;
        }
        org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec spec =
                db.sql("DELETE FROM video_shot_media_source WHERE shot_id IN ("
                        + String.join(",", placeholders) + ")");
        for (int position = 0; position < ids.size(); position++) {
            spec = spec.bind("id" + position, ids.get(position));
        }
        return spec.fetch().rowsUpdated();
    }

    public Mono<Long> deleteByStoryboard(UUID storyboardId) {
        return db.sql("DELETE FROM video_shot_media_source WHERE storyboard_id = CAST(:storyboard AS uuid)")
                .bind("storyboard", storyboardId.toString())
                .fetch().rowsUpdated();
    }

    private static VideoShotSource map(Row row, RowMetadata meta) {
        String mediaId = row.get("media_id", String.class);
        return new VideoShotSource(UUID.fromString(row.get("shot_id", String.class)),
                UUID.fromString(row.get("storyboard_id", String.class)), row.get("source_kind", String.class),
                mediaId == null ? null : UUID.fromString(mediaId), row.get("trim_start_ms", Long.class),
                row.get("trim_end_ms", Long.class), row.get("audio_mode", String.class),
                row.get("created_at", OffsetDateTime.class), row.get("updated_at", OffsetDateTime.class));
    }
}
