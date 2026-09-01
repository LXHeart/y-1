package com.grassland.intelligence.videoproduction;

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** video_shot 读写（任务书 #64 卡1）。 */
@Component
public class VideoShotRepository {

    private static final String COLS = "id::text, storyboard_id::text, seq, visual, narration, "
            + "planned_seconds, camera_move, anchor_image_index, prompt, status, created_at, updated_at";

    /** JOIN 查询用的带别名列表（别名列名与 COLS 一致，共用同一个 map）。 */
    private static final String JOIN_COLS = "s.id::text, s.storyboard_id::text, s.seq, s.visual, "
            + "s.narration, s.planned_seconds, s.camera_move, s.anchor_image_index, s.prompt, "
            + "s.status, s.created_at, s.updated_at";

    private final DatabaseClient db;

    public VideoShotRepository(DatabaseClient db) {
        this.db = db;
    }

    /**
     * 逐镜写入（卡3 SSE 边收边落）。同 (storyboard_id, seq) 重放时覆盖内容而非报错——
     * LLM 流可能重发某一镜，UNIQUE 冲突不该让整条流失败。
     */
    public Mono<VideoShot> upsert(UUID storyboardId, int seq, String visual, String narration,
            int plannedSeconds, String cameraMove, int anchorImageIndex, String prompt) {
        return db.sql("INSERT INTO video_shot(storyboard_id,seq,visual,narration,planned_seconds,"
                        + "camera_move,anchor_image_index,prompt) "
                        + "VALUES(CAST(:sb AS uuid),:seq,:visual,:narration,:planned,:move,:anchor,:prompt) "
                        + "ON CONFLICT(storyboard_id,seq) DO UPDATE SET visual=EXCLUDED.visual,"
                        + "narration=EXCLUDED.narration,planned_seconds=EXCLUDED.planned_seconds,"
                        + "camera_move=EXCLUDED.camera_move,anchor_image_index=EXCLUDED.anchor_image_index,"
                        + "prompt=EXCLUDED.prompt,updated_at=now() RETURNING " + COLS)
                .bind("sb", storyboardId.toString())
                .bind("seq", seq)
                .bind("visual", visual)
                .bind("narration", narration)
                .bind("planned", plannedSeconds)
                .bind("move", cameraMove)
                .bind("anchor", anchorImageIndex)
                .bind("prompt", prompt)
                .map(VideoShotRepository::map)
                .one();
    }

    public Flux<VideoShot> findByStoryboard(UUID storyboardId) {
        return db.sql("SELECT " + COLS + " FROM video_shot WHERE storyboard_id=CAST(:sb AS uuid) ORDER BY seq")
                .bind("sb", storyboardId.toString())
                .map(VideoShotRepository::map)
                .all();
    }

    /** 镜头按 owner 定位：storyboard.account_id 是唯一归属闸，越权取不到行（调用方 404）。 */
    public Mono<VideoShot> findByIdForAccount(UUID id, String accountId) {
        return db.sql("SELECT " + JOIN_COLS + " FROM video_shot s "
                        + "JOIN video_storyboard b ON b.id=s.storyboard_id "
                        + "WHERE s.id=CAST(:id AS uuid) AND b.account_id=:accountId")
                .bind("id", id.toString())
                .bind("accountId", accountId)
                .map(VideoShotRepository::map)
                .one();
    }

    /** 用户编辑（卡4）：只允许改内容字段，seq 与归属不动。 */
    public Mono<Boolean> updateContent(UUID id, String visual, String narration, int plannedSeconds,
            String cameraMove, int anchorImageIndex, String prompt) {
        return db.sql("UPDATE video_shot SET visual=:visual,narration=:narration,planned_seconds=:planned,"
                        + "camera_move=:move,anchor_image_index=:anchor,prompt=:prompt,updated_at=now() "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString())
                .bind("visual", visual)
                .bind("narration", narration)
                .bind("planned", plannedSeconds)
                .bind("move", cameraMove)
                .bind("anchor", anchorImageIndex)
                .bind("prompt", prompt)
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    public Mono<Boolean> updateStatus(UUID id, String status) {
        return db.sql("UPDATE video_shot SET status=:status,updated_at=now() WHERE id=CAST(:id AS uuid)")
                .bind("id", id.toString())
                .bind("status", status)
                .fetch().rowsUpdated().map(rows -> rows > 0);
    }

    static VideoShot map(Row r, RowMetadata m) {
        return new VideoShot(
                UUID.fromString(r.get("id", String.class)),
                UUID.fromString(r.get("storyboard_id", String.class)),
                r.get("seq", Integer.class),
                r.get("visual", String.class),
                r.get("narration", String.class),
                r.get("planned_seconds", Integer.class),
                r.get("camera_move", String.class),
                r.get("anchor_image_index", Integer.class),
                r.get("prompt", String.class),
                r.get("status", String.class),
                r.get("created_at", OffsetDateTime.class),
                r.get("updated_at", OffsetDateTime.class));
    }
}
