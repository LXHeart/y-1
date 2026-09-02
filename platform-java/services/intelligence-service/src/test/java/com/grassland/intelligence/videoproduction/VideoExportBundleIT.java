package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.videoproduction.export.ExportBundleService;
import com.grassland.storage.ObjectStorageAdapter;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #66 卡B1/B2：导出双轨——B 轨通用素材包（zip 布局契约 + 属主/状态门）与
 * A 轨剪映草稿（draft_content.json 三轨最小集 + 版本区间透出）。造数为直插 succeeded
 * 任务行 + 假对象存储（zip 内容在服务端生成后从假存储读回断言）。
 */
@DisplayName("Video export bundle (B1) and jianying draft (B2)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class VideoExportBundleIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "62626262-6262-6262-6262-626262626262";
    private static final String OTHER = "97979797-9797-9797-9797-979797979797";

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    MediaReferenceRepository mediaRefs;

    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1),
                        invocation.getArgument(2))));
        when(credits.refund(any(), anyString())).thenReturn(Mono.empty());
        objectStore.clear();
        Mockito.doAnswer(invocation -> {
            objectStore.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(storage).putObject(anyString(), any(byte[].class), anyString());
        when(storage.getObject(anyString()))
                .thenAnswer(invocation -> objectStore.get(invocation.getArgument(0)));
        when(storage.presignDownload(anyString(), anyLong()))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed"));
        when(storage.presignDownload(anyString(), anyLong(), anyString()))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed-att"));

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM creation_generation").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("B 轨：属主导出 zip 布局契约 + lineage 登记；非属主 404；未完成 409")
    void bundleExportContractAndGates() {
        Seeded seeded = seedSucceededTask();

        java.util.Map<String, Object> body = client()
                .get().uri("/api/video-production/tasks/{id}/export/bundle", seeded.taskId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody(java.util.Map.class).returnResult().getResponseBody();
        assertThat(body.get("success")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) body.get("data");
        assertThat(data.get("kind")).isEqualTo("bundle");
        assertThat(data.get("downloadUrl").toString()).contains("signed");

        List<String> entries = zipEntries(objectStore.get(ExportBundleService.bundleKey(seeded.taskId())));
        assertThat(entries).contains("bundle/分镜稿.md", "bundle/master.mp4", "bundle/subtitle.srt",
                "bundle/audio/shot-1.wav", "bundle/audio/shot-2.wav",
                "bundle/segments/shot-1.mp4", "bundle/segments/shot-2.mp4");

        String markdown = zipText(objectStore.get(ExportBundleService.bundleKey(seeded.taskId())),
                "bundle/分镜稿.md");
        assertThat(markdown).contains("## 镜头1 / 5 秒").contains("老王面馆现熬骨汤")
                .contains("## 镜头2 / 4 秒").contains("每天现切这碗面");

        Long lineageRows = db.sql("SELECT COUNT(*) AS n FROM creation_generation "
                        + "WHERE kind='video_export'")
                .map(row -> row.get("n", Long.class)).one().block(Duration.ofSeconds(5));
        assertThat(lineageRows).isEqualTo(1L);

        client().get().uri("/api/video-production/tasks/{id}/export/bundle", seeded.taskId())
                .header("X-Grassland-Identity", sign(OTHER, "recommender"))
                .exchange().expectStatus().isNotFound();

        UUID unfinished = seedTaskInPhase("composing");
        client().get().uri("/api/video-production/tasks/{id}/export/bundle", unfinished)
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("A 轨：剪映草稿目录结构 + draft_content.json 三轨 + 版本区间透出")
    void jianyingDraftContract() throws Exception {
        Seeded seeded = seedSucceededTask();

        java.util.Map<String, Object> body = client()
                .get().uri("/api/video-production/tasks/{id}/export/jianying", seeded.taskId())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody(java.util.Map.class).returnResult().getResponseBody();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> data = (java.util.Map<String, Object>) body.get("data");
        assertThat(data.get("kind")).isEqualTo("jianying");
        assertThat(data.get("supportedVersionRange").toString()).contains("剪映专业版");
        String draftName = data.get("draftName").toString();

        byte[] zip = objectStore.get(ExportBundleService.jianyingKey(seeded.taskId()));
        List<String> entries = zipEntries(zip);
        assertThat(entries).contains(
                "jianying/" + draftName + "/draft_content.json",
                "jianying/" + draftName + "/draft_meta_info.json",
                "jianying/" + draftName + "/materials/video/shot-1.mp4",
                "jianying/" + draftName + "/materials/video/shot-2.mp4",
                "jianying/" + draftName + "/materials/audio/shot-1.wav",
                "jianying/" + draftName + "/materials/audio/shot-2.wav");

        JsonNode content = mapper.readTree(zipText(zip,
                "jianying/" + draftName + "/draft_content.json"));
        List<String> trackTypes = new ArrayList<>();
        content.path("tracks").forEach(track -> trackTypes.add(track.path("type").asText()));
        assertThat(trackTypes).containsExactly("video", "audio", "text");
        assertThat(content.path("duration").asLong())
                .isEqualTo((5 + 4) * 1_000_000L); // 计划时长回退（假 mp4 探不到）
        assertThat(content.path("materials").path("material_videos")).hasSize(2);
        assertThat(content.path("materials").path("material_audios")).hasSize(2);
        assertThat(content.path("materials").path("material_texts")).hasSize(2);
        assertThat(content.path("canvas_config").path("width").asInt()).isEqualTo(1080);

        JsonNode meta = mapper.readTree(zipText(zip,
                "jianying/" + draftName + "/draft_meta_info.json"));
        assertThat(meta.path("draft_name").asText()).isEqualTo(draftName);
        assertThat(meta.path("tm_duration").asLong()).isEqualTo(9_000_000L);
    }

    // ---------------- helpers ----------------

    private record Seeded(UUID taskId, UUID storyboardId) {}

    private Seeded seedSucceededTask() {
        UUID storyboardId = UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES (:account, 20, CAST('{"images":[],"shopName":"店"}' AS jsonb))
                        RETURNING id::text
                        """)
                .bind("account", ACCOUNT)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
        UUID shot1 = seedShot(storyboardId, 1, "老王面馆现熬骨汤", 5);
        UUID shot2 = seedShot(storyboardId, 2, "每天现切这碗面", 4);

        UUID taskId = UUID.randomUUID();
        UUID masterMediaId = insertMediaRef("media/video_master/" + taskId, "video/mp4");
        UUID srtMediaId = insertMediaRef("media/video_master_srt/" + taskId, "application/x-subrip");
        db.sql("""
                        INSERT INTO video_production_task(id, storyboard_id, account_id, operation_id, mode,
                            phase, progress, target_duration_seconds, pricing_version, unit_price_cents,
                            estimated_cost_cents, actual_cost_cents, actual_duration_seconds, provider, model,
                            final_media_id, srt_media_id, completed_at)
                        VALUES (CAST(:id AS uuid), CAST(:sb AS uuid), :account, :operation, 'video',
                            'succeeded', 100, 20,
                            'v1', 1, 20, 20, 9, 'sandbox', 'm', CAST(:master AS uuid), CAST(:srt AS uuid), now())
                        """)
                .bind("id", taskId.toString())
                .bind("sb", storyboardId.toString())
                .bind("account", ACCOUNT)
                .bind("operation", "export-it-" + taskId)
                .bind("master", masterMediaId.toString())
                .bind("srt", srtMediaId.toString())
                .then().block(Duration.ofSeconds(5));

        objectStore.put("media/video_master/" + taskId, fakeBytes("master"));
        objectStore.put("media/video_master_srt/" + taskId, fakeBytes("1\n00:00:01,000 --> 00:00:02,000\n旁白\n"));
        for (UUID shotId : List.of(shot1, shot2)) {
            objectStore.put("segments/" + taskId + "/" + shotId + ".mp4", fakeBytes("segment"));
            // TTS 归档约定：mediaId == video_shot_audio 行 id，对象键 media/video_shot_audio/{行 id}
            db.sql("""
                            INSERT INTO video_shot_audio(id, shot_id, status, media_id, duration_ms)
                            VALUES (CAST(:audio AS uuid), CAST(:shot AS uuid), 'succeeded',
                                CAST(:audio AS uuid), 4000)
                            """)
                    .bind("shot", shotId.toString())
                    .bind("audio", shotId.toString())
                    .then().block(Duration.ofSeconds(5));
            objectStore.put("media/video_shot_audio/" + shotId, fakeBytes("audio"));
        }
        return new Seeded(taskId, storyboardId);
    }

    private UUID seedTaskInPhase(String phase) {
        UUID storyboardId = UUID.fromString(db.sql("""
                        INSERT INTO video_storyboard(account_id, target_duration_seconds, request_payload)
                        VALUES (:account, 20, CAST('{"images":[],"shopName":"店"}' AS jsonb))
                        RETURNING id::text
                        """)
                .bind("account", ACCOUNT)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
        seedShot(storyboardId, 1, "镜头", 5);
        return UUID.fromString(db.sql("""
                        INSERT INTO video_production_task(storyboard_id, account_id, operation_id, mode,
                            phase, progress, target_duration_seconds, pricing_version, unit_price_cents,
                            estimated_cost_cents)
                        VALUES (CAST(:sb AS uuid), :account, :operation, 'video', :phase, 50, 20,
                            'v1', 1, 20)
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString())
                .bind("account", ACCOUNT)
                .bind("operation", "export-it-phase-" + phase)
                .bind("phase", phase)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private UUID seedShot(UUID storyboardId, int seq, String narration, int plannedSeconds) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt)
                        VALUES (CAST(:sb AS uuid), :seq, '画面', :narration, :planned, '固定机位', 0, 'p')
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString()).bind("seq", seq)
                .bind("narration", narration).bind("planned", plannedSeconds)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private UUID insertMediaRef(String key, String mime) {
        MediaReference reference = new MediaReference(UUID.randomUUID(), ACCOUNT, null,
                "video_master", "video_production_task", "export-it", key, mime,
                16, "deadbeef", "generated", MediaStatus.ACTIVE, Instant.now(), null, null);
        return mediaRefs.insert(reference).block(Duration.ofSeconds(5)).id();
    }

    private static byte[] fakeBytes(String marker) {
        return marker.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static List<String> zipEntries(byte[] zipBytes) {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
                zip.closeEntry();
            }
        } catch (Exception error) {
            throw new IllegalStateException("zip 解包失败", error);
        }
        return names;
    }

    private static String zipText(byte[] zipBytes, String entryName) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("zip 读取失败: " + entryName, error);
        }
        throw new IllegalStateException("zip 条目不存在: " + entryName);
    }
}
