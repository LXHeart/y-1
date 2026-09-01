package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 任务书 #64 卡10：中间产物清理——成功 N 天后删 take/audio 对象、置空 media_id（幂等标记）；
 * 成片/SRT 不动；新任务不受影响。
 */
@DisplayName("Video artifact cleanup")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false",
        "ai.video-production.artifact-retention-days=7" })
class VideoArtifactCleanupIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "92929292-9292-9292-9292-929292929292";

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    VideoArtifactCleanupWorker cleanup;

    @Autowired
    VideoShotTakeRepository takes;

    @Autowired
    VideoShotAudioRepository audios;

    private final List<String> deletedKeys = new CopyOnWriteArrayList<>();

    @BeforeEach
    void clean() {
        reset(storage);
        deletedKeys.clear();
        Mockito.doAnswer(invocation -> {
            deletedKeys.add(invocation.getArgument(0));
            return null;
        }).when(storage).deleteObject(anyString());

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM media_reference WHERE purpose IN "
                        + "('video_take','video_master')").then())
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("超期任务的 take/audio 对象被删、media_id 置空且幂等；成片对象保留")
    void cleansExpiredArtifactsAndKeepsMaster() {
        UUID storyboardId = seedStoryboard();
        UUID shotId = seedShot(storyboardId);
        UUID takeMedia = seedMedia("media/video_take/take-1", "video_take");
        UUID audioMedia = seedMedia("media/video_shot_audio/a-1", "speech_audio");
        UUID masterMedia = seedMedia("media/video_master/m-1", "video_master");
        seedTake(shotId, takeMedia);
        seedAudio(shotId, audioMedia);
        UUID oldTask = seedTask(storyboardId, masterMedia, OffsetDateTime.now().minusDays(9));
        UUID freshTaskStoryboard = seedStoryboard();
        UUID freshShot = seedShot(freshTaskStoryboard);
        seedTask(freshTaskStoryboard, seedMedia("media/video_master/m-2", "video_master"),
                OffsetDateTime.now().minusDays(1));
        seedTake(freshShot, seedMedia("media/video_take/take-2", "video_take"));

        cleanup.cleanupOnce().block(Duration.ofSeconds(30));

        assertThat(deletedKeys).containsExactlyInAnyOrder(
                "media/video_take/take-1", "media/video_shot_audio/a-1");
        // 成片对象不删；新任务（1 天前）的 take 不删
        assertThat(deletedKeys).doesNotContain("media/video_master/m-1", "media/video_take/take-2");
        // media_id 置空（幂等标记）：二次清理零动作
        cleanup.cleanupOnce().block(Duration.ofSeconds(30));
        assertThat(deletedKeys).hasSize(2);
        verify(storage, Mockito.times(1)).deleteObject("media/video_take/take-1");
        assertThat(takes.findByShot(shotId).collectList().block().getFirst().mediaId()).isNull();
        assertThat(audios.findByShot(shotId).block().mediaId()).isNull();
        // 旧任务行原样保留（历史可见）
        String phase = db.sql("SELECT phase FROM video_production_task "
                        + "WHERE id=CAST(:id AS uuid)").bind("id", oldTask.toString())
                .map(row -> row.get("phase", String.class)).one().block();
        assertThat(phase).isEqualTo("succeeded");
    }

    // ---------------- helpers ----------------

    private UUID seedStoryboard() {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES (:account, 15, '{}'::jsonb) RETURNING id::text
                        """).bind("account", ACCOUNT)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private UUID seedShot(UUID storyboardId) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt)
                        VALUES (CAST(:sb AS uuid), 1, 'v', 'n', 5, '固定机位', 0, 'p')
                        RETURNING id::text
                        """).bind("sb", storyboardId.toString())
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private UUID seedMedia(String key, String purpose) {
        return UUID.fromString(db.sql("""
                        INSERT INTO media_reference(id, owner_account_id, purpose, domain_type, domain_id,
                            object_key, mime_type, size_bytes, checksum, source, status, created_at)
                        VALUES (gen_random_uuid(), :account, :purpose, 'it', 'it', :key, 'video/mp4', 10,
                            'x', 'generated', 'active', now()) RETURNING id::text
                        """).bind("account", ACCOUNT).bind("purpose", purpose).bind("key", key)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private void seedTake(UUID shotId, UUID mediaId) {
        db.sql("""
                INSERT INTO video_shot_take(shot_id, take_no, provider, model, status, media_id, completed_at)
                VALUES (CAST(:shot AS uuid), 1, 'sandbox', 'sandbox-video-v1', 'succeeded',
                    CAST(:media AS uuid), now())
                """).bind("shot", shotId.toString()).bind("media", mediaId.toString())
                .then().block(Duration.ofSeconds(5));
    }

    private void seedAudio(UUID shotId, UUID mediaId) {
        db.sql("""
                INSERT INTO video_shot_audio(shot_id, provider, model, status, media_id, duration_ms,
                    completed_at)
                VALUES (CAST(:shot AS uuid), 'sandbox', 'sandbox-tts-v1', 'succeeded',
                    CAST(:media AS uuid), 2000, now())
                """).bind("shot", shotId.toString()).bind("media", mediaId.toString())
                .then().block(Duration.ofSeconds(5));
    }

    private UUID seedTask(UUID storyboardId, UUID masterMediaId, OffsetDateTime completedAt) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_production_task(storyboard_id, account_id, operation_id, mode,
                            phase, progress, final_media_id, target_duration_seconds, pricing_version,
                            unit_price_cents, estimated_cost_cents, actual_duration_seconds,
                            actual_cost_cents, completed_at)
                        VALUES (CAST(:sb AS uuid), :account, :op, 'slideshow', 'succeeded', 100,
                            CAST(:media AS uuid), 15, 'v1', 1, 15, 14, 14, :completedAt)
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString()).bind("account", ACCOUNT)
                .bind("op", "op-" + UUID.randomUUID())
                .bind("media", masterMediaId.toString())
                .bind("completedAt", completedAt)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }
}
