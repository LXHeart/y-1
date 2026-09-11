package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.grassland.intelligence.IntelligenceItSupport;
import com.grassland.intelligence.credits.CreditCharge;
import com.grassland.intelligence.credits.CreditFeature;
import com.grassland.intelligence.credits.CreditsClient;
import com.grassland.storage.ObjectStorageAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #100 C100-08：首个专业模式里程碑的集成验收 IT（媒体/选择/合成真正一致）。
 *
 * <p>五镜分镜、每镜两候选（take1=红 2s、take2=绿 2s，ffmpeg lavfi 真实 mp4 + 440Hz 音轨）；
 * 采用非推荐候选（第 1 镜选绿、其余选红）后真实 FFmpeg 合成，对成片抽帧证明
 * <b>输出片段顺序与颜色 = 服务端选片</b>；结算按实际秒；SRT 按镜绝对偏移；
 * 跨账号详情 404。环境无 ffmpeg 时整类跳过（VideoCompositionIT 同款先例）。
 */
@DisplayName("Video canvas milestone (C100-08)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class VideoCanvasMilestoneIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "84848484-8484-8484-8484-848484848484";
    private static final String ACCOUNT_B = "85858585-8585-8585-8585-858585858585";
    private static final String IMAGE = "data:image/png;base64,AAAA";
    private static final int SHOT_COUNT = 5;
    private static final int SHOT_SECONDS = 2;

    @MockitoBean
    CreditsClient credits;

    @MockitoBean
    ObjectStorageAdapter storage;

    @Autowired
    VideoProductionTaskService taskService;

    @Autowired
    VideoCompositionService composition;

    @Autowired
    TtsWorker ttsWorker;

    @Autowired
    VideoShotAudioRepository audios;

    @Autowired
    VideoShotTakeRepository takes;

    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();

    @BeforeAll
    static void requireFfmpeg() {
        assumeTrue(ffmpegAvailable(), "环境无 ffmpeg，跳过里程碑合成 IT");
    }

    static boolean ffmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version").start();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException error) {
            return false;
        }
    }

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> Mono.just(new CreditCharge(
                        invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2))));
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
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed-srt"));

        db.sql("DELETE FROM video_shot_take").then()
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM video_storyboard_workspace").then())
                .then(db.sql("DELETE FROM creation_draft_version").then())
                .then(db.sql("DELETE FROM creation_draft").then())
                .then(db.sql("DELETE FROM bgm_track").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .then(db.sql("DELETE FROM media_reference WHERE purpose IN ('video_take','video_tts')").then())
                .then(db.sql("DELETE FROM platform_model_concurrency_slot WHERE config_id IN "
                        + "(SELECT id FROM platform_model_config WHERE capability IN "
                        + "('video_generation','video_tts'))").then())
                .then(db.sql("DELETE FROM platform_model_config WHERE capability IN "
                        + "('video_generation','video_tts')").then())
                .then(db.sql("DELETE FROM platform_provider_credential WHERE name LIKE 'it-milestone-%' "
                        + "OR base_url LIKE '%.sandbox.invalid'").then())
                .block(Duration.ofSeconds(10));
        // video 模式（sandbox 渠道冻结价目）+ sandbox TTS（正弦波配音）
        seedCapability("video_generation", "sandbox-video-v1");
        seedCapability("video_tts", "sandbox-tts-v1");
    }

    @Test
    @DisplayName("五镜选片→真实合成：成片颜色顺序=服务端选片；按实际秒结算；SRT 绝对偏移；跨账号 404")
    void selectionDrivesRealComposition() throws Exception {
        // ---- 五镜分镜 + 任务（create 派生 2 take/镜 + 旁白音频行）----
        UUID storyboardId = seedStoryboard(SHOT_COUNT * SHOT_SECONDS + 15);
        List<UUID> shotIds = new ArrayList<>();
        for (int seq = 1; seq <= SHOT_COUNT; seq++) {
            shotIds.add(seedShot(storyboardId, seq, "第" + seq + "镜旁白内容八字"));
        }
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, "op-milestone"))
                .block(Duration.ofSeconds(30));
        assertThat(task.mode()).isEqualTo("video");

        // ---- 每镜两候选：take1=红、take2=绿（真实 mp4，440Hz 音轨）；sandbox TTS 配音 ----
        List<List<VideoShotTake>> shotTakes = new ArrayList<>();
        for (UUID shotId : shotIds) {
            List<VideoShotTake> pair = takes.findByShot(shotId).collectList().block(Duration.ofSeconds(5));
            assertThat(pair).hasSize(2);
            pair.sort(java.util.Comparator.comparingInt(VideoShotTake::takeNo));
            for (VideoShotTake take : pair) {
                boolean green = take.takeNo() == 2;
                byte[] mp4 = colorMp4(green ? "0x00FF00" : "0xFF0000");
                String key = "media/video_take/" + take.id();
                objectStore.put(key, mp4);
                seedMediaReference(take.id(), key, mp4.length);
                assertThat(takes.attachMedia(take.id(), take.id(), SHOT_SECONDS * 1000)
                        .block(Duration.ofSeconds(5))).isTrue();
            }
            shotTakes.add(pair);
            // 任务创建已派生音频行——直接取存量行喂 sandbox TTS（真实配音链）
            VideoShotAudio audio = audios.findByShot(shotId).block(Duration.ofSeconds(5));
            assertThat(audio).as("任务创建应派生旁白音频行").isNotNull();
            ttsWorker.process(audio).block(Duration.ofSeconds(30));
            assertTrue(audios.findByShot(shotId).block(Duration.ofSeconds(5)).isSettled(),
                    "sandbox TTS 应成功或跳过");
        }

        // ---- 采用：第 1 镜选非推荐（绿），其余选推荐（红）----
        UUID greenTake = shotTakes.get(0).get(1).id();
        List<VideoProductionTaskService.Selection> selections = new ArrayList<>();
        selections.add(new VideoProductionTaskService.Selection(shotIds.get(0), greenTake));
        for (int i = 1; i < SHOT_COUNT; i++) {
            selections.add(new VideoProductionTaskService.Selection(shotIds.get(i), shotTakes.get(i).get(0).id()));
        }
        taskService.select(task.id(), ACCOUNT, selections, false).block(Duration.ofSeconds(10));

        // ---- 合成（真实 FFmpeg）----
        task = taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        assertThat(task.phase()).isEqualTo(VideoProductionTask.PHASE_COMPOSING);
        composition.compose(task).block(Duration.ofSeconds(180));

        VideoProductionTask done = taskRepo.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(done.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);
        assertThat(done.finalMediaId()).isNotNull();

        // ---- 选片真实落库：非推荐选择持久 + 版本单调（TC-001 里程碑口径）----
        String selectionJson = db.sql("SELECT selection::text AS s FROM video_production_task "
                        + "WHERE id=CAST(:id AS uuid)")
                .bind("id", task.id().toString())
                .map(row -> row.get("s", String.class)).one().block(Duration.ofSeconds(5));
        Map<String, String> selection;
        try {
            selection = new com.fasterxml.jackson.databind.ObjectMapper().readValue(selectionJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
                    });
        } catch (Exception error) {
            throw new IllegalStateException("selection 解析失败: " + selectionJson, error);
        }
        assertThat(selection).hasSize(SHOT_COUNT);
        assertThat(selection.get(shotIds.get(0).toString())).isEqualTo(greenTake.toString());

        // ---- 成片 = 服务端选片的真实证据：首段绿、其余红（抽帧 rgb24 分类）----
        byte[] master = objectStore.get("media/video_master/" + done.id());
        assertThat(master).isNotNull();
        assertThat(master.length).isGreaterThan(10_000);
        assertThat(new String(master, 4, 4, StandardCharsets.US_ASCII)).isEqualTo("ftyp");
        List<String> frameColors = frameColors(master);
        assertThat(frameColors).isNotEmpty();
        // 每段 ≈2s（音频 8 字/4=2s 与视频 2s 取齐）；2fps 抽帧 → 前段绿随后全红，颜色只切换一次
        int firstRed = frameColors.indexOf("red");
        assertThat(firstRed).as("应有红段").isGreaterThan(0);
        assertThat(frameColors.subList(0, firstRed)).as("前段应为绿").containsOnly("green");
        assertThat(frameColors.subList(firstRed, frameColors.size())).as("后段应全红").containsOnly("red");
        assertThat(firstRed).as("绿段应只覆盖第 1 镜（≈2s）").isLessThanOrEqualTo(6);
        // 音轨存在（合成链保留声音）
        assertThat(hasAudioStream(master)).isTrue();

        // ---- 结算按实际秒（一口价多退少补）----
        assertThat(done.actualDurationSeconds()).isBetween(8, 15);
        assertThat(done.actualCostCents()).isEqualTo(done.actualDurationSeconds() * done.unitPriceCents());

        // ---- SRT：每镜一条 cue，绝对偏移累计（第 2 镜 cue 起点应 ≥ 第 1 镜时长）----
        String srt = new String(objectStore.get("media/video_master_srt/" + done.id()),
                StandardCharsets.UTF_8);
        assertThat(srt.split("\n\n")).hasSize(SHOT_COUNT);
        assertThat(srt).contains("00:00:02").contains("第1镜").contains("第5镜");

        // ---- 详情（属主）与跨账号 404（TC-004 里程碑口径）----
        client().get().uri("/api/video-production/tasks/{id}", done.id())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.phase").isEqualTo("succeeded")
                .jsonPath("$.data.selection." + shotIds.get(0)).isEqualTo(greenTake.toString());
        client().get().uri("/api/video-production/tasks/{id}", done.id())
                .header("X-Grassland-Identity", sign(ACCOUNT_B, "recommender"))
                .exchange().expectStatus().isNotFound();
    }

    // ---------------- helpers ----------------

    @Autowired
    VideoProductionTaskRepository taskRepo;

    /** lavfi 纯色 540×960、SHOT_SECONDS 秒、440Hz 音轨的真实 mp4（临时文件——mp4 muxer 不支持管道输出）。 */
    private static byte[] colorMp4(String color) throws IOException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("grassland-milestone-color", ".mp4");
        try {
            Process process = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y", "-f", "lavfi",
                    "-i", "color=c=" + color + ":s=540x960:d=" + SHOT_SECONDS + ":r=30",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=" + SHOT_SECONDS,
                    "-shortest", "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast",
                    "-c:a", "aac", file.toString())
                    .redirectErrorStream(false).start();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            assertThat(process.exitValue()).as("ffmpeg 生成颜色素材失败: %s", stderr).isEqualTo(0);
            return java.nio.file.Files.readAllBytes(file);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    /** 2fps 抽帧缩到 4×4 后按平均 rgb 分类（red/green/other）。 */
    private static List<String> frameColors(byte[] mp4) throws IOException {
        Process process = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-i", "pipe:0",
                "-vf", "fps=2,scale=4:4", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1")
                .redirectErrorStream(false).start();
        process.getOutputStream().write(mp4);
        process.getOutputStream().close();
        byte[] raw = process.getInputStream().readAllBytes();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        assertThat(process.exitValue()).as("ffmpeg 抽帧失败: %s", stderr).isEqualTo(0);
        int frameBytes = 4 * 4 * 3;
        List<String> colors = new ArrayList<>();
        for (int offset = 0; offset + frameBytes <= raw.length; offset += frameBytes) {
            long r = 0, g = 0, b = 0;
            for (int i = 0; i < frameBytes; i += 3) {
                r += raw[offset + i] & 0xFF;
                g += raw[offset + i + 1] & 0xFF;
                b += raw[offset + i + 2] & 0xFF;
            }
            int pixels = frameBytes / 3;
            r /= pixels;
            g /= pixels;
            b /= pixels;
            if (g > r + 40 && g > b + 40) {
                colors.add("green");
            } else if (r > g + 40 && r > b + 40) {
                colors.add("red");
            } else {
                colors.add("other");
            }
        }
        return colors;
    }

    private static boolean hasAudioStream(byte[] mp4) throws IOException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("grassland-milestone", ".mp4");
        java.nio.file.Files.write(file, mp4);
        try {
            Process process = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "a",
                    "-show_entries", "stream=codec_type", "-of", "csv=p=0", file.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return output.contains("audio");
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private void seedCapability(String capability, String model) {
        db.sql("""
                WITH cred AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
                    VALUES (:name, 'sandbox', :baseUrl, true)
                    ON CONFLICT DO NOTHING
                    RETURNING id, base_url
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT :capability, 'primary', 'sandbox', :model, :baseUrl, 'healthy', true, 1, cred.id
                FROM cred
                """)
                .bind("name", "it-milestone-" + capability)
                .bind("capability", capability)
                .bind("baseUrl", "https://" + capability + ".sandbox.invalid")
                .bind("model", model)
                .then().block(Duration.ofSeconds(10));
    }

    /** 直插 take 媒体行（镜像 VideoAssetArchiveService.store，跳过 outbox/送审——合成只读 objectKey）。 */
    private void seedMediaReference(UUID mediaId, String objectKey, int sizeBytes) {
        db.sql("""
                INSERT INTO media_reference(id, owner_account_id, organization_id, purpose, domain_type,
                    domain_id, object_key, upload_key, mime_type, size_bytes, checksum, source, status)
                VALUES (CAST(:id AS uuid), :account, NULL, 'video_take', 'video_shot_take',
                    CAST(:domain AS uuid), :key, NULL, 'video/mp4', :size, 'it-milestone', 'generated', 'active')
                ON CONFLICT (id) DO NOTHING
                """)
                .bind("id", mediaId.toString())
                .bind("account", ACCOUNT)
                .bind("domain", mediaId.toString())
                .bind("key", objectKey)
                .bind("size", sizeBytes)
                .then().block(Duration.ofSeconds(5));
    }

    private UUID seedStoryboard(int targetDurationSeconds) {
        String payload = "{\"images\":[\"" + IMAGE + "\"],\"shopName\":\"里程碑店\"}";
        return UUID.fromString(db.sql("INSERT INTO video_storyboard(account_id, target_duration_seconds, "
                        + "request_payload) VALUES (:account, :duration, CAST(:payload AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT).bind("duration", targetDurationSeconds)
                .bind("payload", payload)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private UUID seedShot(UUID storyboardId, int seq, String narration) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt)
                        VALUES (CAST(:sb AS uuid), :seq, '画面', :narration, 5, '固定机位', 1, '提示词')
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString()).bind("seq", seq).bind("narration", narration)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }
}
