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
 * 任务书 #64 卡8：图文成片 sandbox 全链（真 ffmpeg）——zoompan 片段、concat、硬字幕、SRT、
 * 实际秒数结算、subtitle presign。环境无 ffmpeg 时整类跳过（VideoFrameExtractorTest 先例；
 * Docker 镜像内建 ffmpeg，CI/生产可跑）。
 */
@DisplayName("Video composition sandbox chain")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class VideoCompositionIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "81818181-8181-8181-8181-818181818181";
    private static final String IMAGE = "data:image/jpeg;base64,/9j/4AAQSkZJRgABAgAAAQABAAD//gAQTGF2YzYyLjI4LjEwMQD/2wBDAAgEBAQEBAUFBQUFBQYGBgYGBgYGBgYGBgYHBwcICAgHBwcGBgcHCAgICAkJCQgICAgJCQoKCgwMCwsODg4RERT/xABNAAEBAAAAAAAAAAAAAAAAAAAABgEBAQEAAAAAAAAAAAAAAAAAAAYHEAEAAAAAAAAAAAAAAAAAAAAAEQEAAAAAAAAAAAAAAAAAAAAA/8AAEQgB4AFAAwEiAAIRAAMRAP/aAAwDAQACEQMRAD8AiwEm38AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAB/9k=";

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

    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();

    @BeforeAll
    static void requireFfmpeg() {
        assumeTrue(ffmpegAvailable(), "环境无 ffmpeg，跳过成片合成 IT");
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
                .then(db.sql("DELETE FROM bgm_track").then())
                .then(db.sql("DELETE FROM ai_run").then())
                .then(db.sql("DELETE FROM platform_model_config WHERE capability IN "
                        + "('video_generation','video_tts')").then())
                .then(db.sql("DELETE FROM platform_provider_credential WHERE base_url LIKE '%.sandbox.invalid'").then())
                .block(Duration.ofSeconds(10));
        // 图文成片：无 video_generation 行 → slideshow；配音走 sandbox TTS
        seedCapability("video_tts", "sandbox", "sandbox-tts-v1");
    }

    @Test
    @DisplayName("2 镜图文成片全链：zoompan+配音+字幕+SRT+按实际秒结算+字幕下载")
    void slideshowComposeFullChain() {
        UUID storyboardId = seedStoryboard(15,
                "[\"" + IMAGE + "\",\"" + IMAGE + "\"]");
        UUID shot1 = seedShot(storyboardId, 1, "老王面馆现熬骨汤", 1);
        UUID shot2 = seedShot(storyboardId, 2, "每天现切这碗面", 2);
        seedTakeAndAudio(shot1);
        seedTakeAndAudio(shot2);

        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, null))
                .block(Duration.ofSeconds(30));
        assertThat(task.mode()).isEqualTo("slideshow");

        task = taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        assertThat(task.phase()).isEqualTo(VideoProductionTask.PHASE_COMPOSING);

        composition.compose(task).block(Duration.ofSeconds(120));

        VideoProductionTask done = taskServiceTask(task.id());
        assertThat(done.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);
        assertThat(done.finalMediaId()).isNotNull();
        assertThat(done.srtMediaId()).isNotNull();
        // 旁白 8 字/7 字 → 2000ms+2000ms（每镜至少 1s）→ 实际秒 3-6 容忍 ffmpeg 舍入
        assertThat(done.actualDurationSeconds()).isBetween(3, 6);
        assertThat(done.actualCostCents()).isEqualTo(done.actualDurationSeconds() * done.unitPriceCents());

        // 成片与 SRT 均落对象存储
        byte[] master = objectStore.get("media/video_master/" + done.id());
        assertThat(master).isNotNull();
        assertThat(master.length).isGreaterThan(10_000);
        assertThat(new String(master, 4, 4, StandardCharsets.US_ASCII)).isEqualTo("ftyp");
        String srt = new String(objectStore.get("media/video_master_srt/" + done.id()),
                StandardCharsets.UTF_8);
        // 每镜一段 cue（8/7 字无标点不切分）→ SRT 2 条、时间轴按镜累计（第二镜起 2000ms）
        assertThat(srt).contains("老王面馆").contains("现熬骨汤").contains("每天现切").contains("这碗面");
        assertThat(srt.split("\n\n")).hasSize(2);
        assertThat(srt).contains("00:00:02,000 --> 00:00:03");

        // 结算：ai_run completed
        String run = db.sql("SELECT status FROM ai_run WHERE id=CAST(:run AS uuid)")
                .bind("run", done.runId().toString())
                .map(row -> row.get("status", String.class)).one().block(Duration.ofSeconds(5));
        assertThat(run).isEqualTo("completed");

        // 字幕下载 presign
        client().get().uri("/api/video-production/tasks/{id}/subtitle", done.id())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.downloadUrl").isEqualTo("https://media.example.test/signed-srt");

        // 详情带成片与字幕 URL
        client().get().uri("/api/video-production/tasks/{id}", done.id())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.phase").isEqualTo("succeeded")
                .jsonPath("$.data.finalUrl").isEqualTo("https://media.example.test/signed")
                // 详情走 2 参 presign（与成片同桩）；带附件名的 3 参桩由 /subtitle 端点用
                .jsonPath("$.data.subtitleUrl").isEqualTo("https://media.example.test/signed");
    }

    @Test
    @DisplayName("video 模式未选片 → compose 409；分镜镜头缺失选中候选被拒")
    void composeRequiresCompleteSelection() {
        seedCapability("video_generation", "sandbox", "sandbox-video-v1");
        UUID storyboardId = seedStoryboard(15, "[\"" + IMAGE + "\"]");
        UUID shot1 = seedShot(storyboardId, 1, "旁白内容", 1);
        seedTakeAndAudio(shot1);

        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, null))
                .block(Duration.ofSeconds(30));
        assertThat(task.mode()).isEqualTo("video");

        client().post().uri("/api/video-production/tasks/{id}/compose", task.id())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    @DisplayName("#65 卡1：横版分镜（1920x1080）→ 成片 ffprobe 分辨率 1920x1080")
    void landscapeStoryboardComposesTo1920x1080() {
        UUID storyboardId = seedStoryboard("1920x1080", 15, "[\"" + IMAGE + "\",\"" + IMAGE + "\"]");
        UUID shot1 = seedShot(storyboardId, 1, "横版第一镜", 1);
        UUID shot2 = seedShot(storyboardId, 2, "横版第二镜", 2);
        seedTakeAndAudio(shot1);
        seedTakeAndAudio(shot2);

        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, null))
                .block(Duration.ofSeconds(30));
        assertThat(task.mode()).isEqualTo("slideshow");

        task = taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        composition.compose(task).block(Duration.ofSeconds(120));

        VideoProductionTask done = taskServiceTask(task.id());
        assertThat(done.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);
        byte[] master = objectStore.get("media/video_master/" + done.id());
        assertThat(master).isNotNull();
        assertThat(ffprobeResolution(master)).isEqualTo("1920x1080");
    }

    /** ffprobe 视频流宽高（"WxH"）；环境无 ffprobe 时返回空串（类级 assumeTrue 已挡主路径）。 */
    private static String ffprobeResolution(byte[] mp4) {
        try {
            java.nio.file.Path file = java.nio.file.Files.createTempFile("grassland-it-master", ".mp4");
            java.nio.file.Files.write(file, mp4);
            Process process = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=width,height", "-of", "csv=p=0", file.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            java.nio.file.Files.deleteIfExists(file);
            return output.replace(",", "x");
        } catch (IOException | InterruptedException error) {
            return "";
        }
    }

    // ---------------- helpers ----------------

    @Autowired
    VideoProductionTaskRepository taskRepo;

    private VideoProductionTask taskServiceTask(UUID id) {
        return taskRepo.findById(id, ACCOUNT).block(Duration.ofSeconds(5));
    }

    private void seedCapability(String capability, String provider, String model) {
        db.sql("""
                WITH cred AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
                    VALUES (:name, :provider, :baseUrl, true)
                    RETURNING id, base_url
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT :capability, 'primary', :provider, :model, cred.base_url, 'healthy', true, 1, cred.id
                FROM cred
                """)
                .bind("name", "it-compose-" + capability)
                .bind("capability", capability)
                .bind("provider", provider)
                .bind("baseUrl", "https://" + capability + ".sandbox.invalid")
                .bind("model", model)
                .then().block(Duration.ofSeconds(10));
    }

    private UUID seedStoryboard(int targetDurationSeconds, String imagesJson) {
        return seedStoryboard(null, targetDurationSeconds, imagesJson);
    }

    /** #65 卡1：resolution 可显式指定（null = 落列缺省竖版）。 */
    private UUID seedStoryboard(String resolution, int targetDurationSeconds, String imagesJson) {
        String payload = "{\"images\":" + imagesJson + ",\"shopName\":\"店\"}";
        String sql = "INSERT INTO video_storyboard(account_id, target_duration_seconds"
                + (resolution == null ? "" : ", resolution") + ", request_payload) VALUES (:account, :duration"
                + (resolution == null ? "" : ", :resolution") + ", CAST(:payload AS jsonb)) RETURNING id::text";
        var spec = db.sql(sql)
                .bind("account", ACCOUNT).bind("duration", targetDurationSeconds)
                .bind("payload", payload);
        if (resolution != null) {
            spec = spec.bind("resolution", resolution);
        }
        return UUID.fromString(spec.map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private UUID seedShot(UUID storyboardId, int seq, String narration, int anchor) {
        return UUID.fromString(db.sql("""
                        INSERT INTO video_shot(storyboard_id, seq, visual, narration, planned_seconds,
                            camera_move, anchor_image_index, prompt)
                        VALUES (CAST(:sb AS uuid), :seq, '画面', :narration, 5, '固定机位', :anchor, 'p')
                        RETURNING id::text
                        """)
                .bind("sb", storyboardId.toString()).bind("seq", seq)
                .bind("narration", narration).bind("anchor", anchor)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    /** 配音走真 TTS worker（sandbox 合成正弦波 wav + cues + 归档）。 */
    private void seedTakeAndAudio(UUID shotId) {
        VideoShotAudio audio = audios.create(shotId, null, null).block(Duration.ofSeconds(5));
        ttsWorker.process(audio).block(Duration.ofSeconds(30));
        VideoShotAudio done = audios.findByShot(shotId).block(Duration.ofSeconds(5));
        assertTrue(done.isSettled(), "sandbox TTS 应成功或跳过：" + done.status());
    }
}
