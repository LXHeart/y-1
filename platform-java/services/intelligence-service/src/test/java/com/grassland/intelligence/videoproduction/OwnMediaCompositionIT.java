package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

/**
 * 任务书 #100 C100-12：自有素材进入真实成片与既有计费环（§6.5）。
 *
 * <p>
 * TC-028 全自有（video provider 已配置仍强制 slideshow-v1 冻结价，零候选/零 TTS-for-source-mute）；
 * TC-029 幂等重放不二次预留；TC-030 own 镜头重抽 409、素材失效失败定位不偷换付费生成；
 * TC-031 真实 FFmpeg 合成：三镜 source/narration/mute 音轨策略 + 双色素材截取区间可验证、
 * 混合任务（generated+own）段顺序与颜色。环境无 ffmpeg 整类跳过（VideoCompositionIT 先例）。
 */
@DisplayName("Own media composition (C100-12)")
@TestPropertySource(properties = { "ai.video-generation.worker-enabled=false" })
class OwnMediaCompositionIT extends IntelligenceItSupport {

    private static final String ACCOUNT = "86868686-8686-8686-8686-868686868686";
    private static final String IMAGE = "data:image/png;base64,AAAA";
    private static final int SHOT_SECONDS = 5;

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
    VideoShotTakeRepository takeRepo;

    @Autowired
    VideoProductionTaskRepository taskRepo;

    private final Map<String, byte[]> objectStore = new ConcurrentHashMap<>();
    private final AtomicInteger reserveCount = new AtomicInteger();

    @BeforeAll
    static void requireFfmpeg() {
        assumeTrue(VideoCanvasMilestoneIT.ffmpegAvailable(), "环境无 ffmpeg，跳过自有素材合成 IT");
    }

    @BeforeEach
    void cleanAndSeed() {
        reset(credits, storage);
        reserveCount.set(0);
        when(credits.consume(anyString(), any(CreditFeature.class), anyString()))
                .thenAnswer(invocation -> {
                    reserveCount.incrementAndGet();
                    return Mono.just(new CreditCharge(invocation.getArgument(0),
                            invocation.getArgument(1), invocation.getArgument(2)));
                });
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
        when(storage.presignDownload(anyString(), anyLong(), any()))
                .thenAnswer(invocation -> java.net.URI.create("https://media.example.test/signed-att"));

        db.sql("DELETE FROM video_shot_media_source").then()
                .then(db.sql("DELETE FROM video_shot_take").then())
                .then(db.sql("DELETE FROM video_shot_audio").then())
                .then(db.sql("DELETE FROM video_production_task").then())
                .then(db.sql("DELETE FROM video_shot").then())
                .then(db.sql("DELETE FROM video_storyboard").then())
                .then(db.sql("DELETE FROM media_reference").then())
                // compose 自动选曲（bgmSelection.pick(null)）会吞并库里任何泄漏的 BGM 行
                // （他类种子未清时音量漂移/对象缺失 compose_failed）——本类口径一并清空
                .then(db.sql("DELETE FROM bgm_track").then())
                .block(Duration.ofSeconds(10));

        // video provider 已配置（TC-028：全自有仍强制 slideshow-v1）+ sandbox TTS
        seedCapability("video_generation", "sandbox-video-v1");
        seedCapability("video_tts", "sandbox-tts-v1");
    }

    @Test
    @DisplayName("TC-028/029/031：全自有三镜 source/narration/mute → slideshow 冻结价、零候选零TTS(source/mute)、"
            + "真实合成音轨/颜色/区间正确、重放不二次预留")
    void allOwnComposition() throws Exception {
        UUID storyboardId = seedStoryboard(30);
        // 三镜素材：双色各 5s（红 0-5s、绿 5-10s）；source=红段、narration=绿段、mute=红段（各 5s 截取）
        byte[] twoColor = twoColorMp4("0xFF0000", "0x00FF00");
        UUID mediaId = seedOwnMedia("own/two-color", twoColor);
        List<UUID> shotIds = new ArrayList<>();
        shotIds.add(seedShot(storyboardId, 1, "第一镜旁白八个字"));
        shotIds.add(seedShot(storyboardId, 2, "第二镜旁白八个字"));
        shotIds.add(seedShot(storyboardId, 3, "第三镜旁白八个字"));
        insertSource(shotIds.get(0), storyboardId, mediaId, 500L, 500 + SHOT_SECONDS * 1000L,
                VideoShotSource.AUDIO_SOURCE);
        insertSource(shotIds.get(1), storyboardId, mediaId, 5000L, 5000 + SHOT_SECONDS * 1000L,
                VideoShotSource.AUDIO_NARRATION);
        insertSource(shotIds.get(2), storyboardId, mediaId, 500L, 500 + SHOT_SECONDS * 1000L,
                VideoShotSource.AUDIO_MUTE);

        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, "op-own-1"))
                .block(Duration.ofSeconds(30));
        // TC-028：video provider 已配置，但全自有强制 slideshow-v1 冻结
        assertThat(task.mode()).isEqualTo(VideoProductionTask.MODE_SLIDESHOW);
        assertThat(task.provider()).isEqualTo("sandbox");
        assertThat(task.model()).isEqualTo("slideshow-v1");
        assertThat(task.unitPriceCents()).isGreaterThan(0);
        assertThat(task.estimatedCostCents())
                .isEqualTo(storyboardId.toString().isEmpty() ? 0 : taskRepo
                        .findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5)).estimatedCostCents());
        // 零生成候选（own 镜头不派生；slideshow 模式本就不派生）
        assertThat(takeRepo.findByStoryboard(storyboardId).count().block(Duration.ofSeconds(5))).isZero();
        // 音频行：只有 narration 镜（source/mute 不派生 TTS 行）
        long audioRows = audios.findByStoryboard(storyboardId).count().block(Duration.ofSeconds(5));
        assertThat(audioRows).isEqualTo(1L);

        // narration 镜走真实 sandbox TTS
        VideoShotAudio narrationAudio = audios.findByStoryboard(storyboardId).next().block(Duration.ofSeconds(5));
        ttsWorker.process(narrationAudio).block(Duration.ofSeconds(30));
        VideoShotAudio settled = audios.findByStoryboard(storyboardId).next().block(Duration.ofSeconds(5));
        assertTrue(settled.isSettled());
        // 游离 worker 竞态自愈（全量套件两形态实锤）：更早 IT 类的缓存 context 留有 live
        // TtsWorker 轮询共享库，可能先认领本行——失败结算（mediaId=null）或把正弦 wav 归档
        // 进它自己的真实存储适配器（本类 mock 映射缺键），合成读到 null 后 narration 段退化
        // 静音。沙箱 TTS 是确定性合成：按 TtsWorker.complete 同口径补齐（幂等媒体行 +
        // attachMedia 置回 succeeded），保证合成输入与直跑一致。
        String narrationText = db.sql("SELECT narration FROM video_shot WHERE id=CAST(:id AS uuid)")
                .bind("id", shotIds.get(1).toString())
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5));
        int ttsMs = SandboxTtsProvider.durationMsFor(narrationText);
        String ttsKey = "media/video_shot_audio/" + settled.id();
        if (!settled.isVoiced() || objectStore.get(ttsKey) == null) {
            byte[] wav = SandboxTtsProvider.sineWavBytes(ttsMs);
            objectStore.put(ttsKey, wav);
            db.sql("INSERT INTO media_reference(id, owner_account_id, purpose, object_key, mime_type, "
                            + "size_bytes, source, status) VALUES (CAST(:id AS uuid), :account, 'speech_audio', "
                            + ":key, 'audio/wav', :size, 'generated', 'active') ON CONFLICT (id) DO NOTHING")
                    .bind("id", settled.id().toString())
                    .bind("account", ACCOUNT)
                    .bind("key", ttsKey)
                    .bind("size", wav.length)
                    .then().block(Duration.ofSeconds(5));
            audios.attachMedia(settled.id(), settled.id(),
                    TtsCues.toJson(TtsCues.build(narrationText, ttsMs)), ttsMs).block(Duration.ofSeconds(5));
        }

        // TC-029：同 opId 重放返回同一任务，不二次预留
        VideoProductionTask replayed = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, "op-own-1"))
                .block(Duration.ofSeconds(10));
        assertThat(replayed.id()).isEqualTo(task.id());
        assertThat(reserveCount.get()).isEqualTo(1);

        // 合成（真实 FFmpeg）
        task = taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        composition.compose(task).block(Duration.ofSeconds(180));
        VideoProductionTask done = taskRepo.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        // as 携带服务端错误码/信息：CI 并行负载下偶发 compose_failed 时失败信息自报原因
        assertThat(done.phase())
                .as("compose 应成功（errorCode=%s, errorMessage=%s）", done.errorCode(), done.errorMessage())
                .isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);

        // TC-031：颜色窗口 = 截取区间（红[500,2500) 绿[2000,4000) 红[500,2500)）
        byte[] master = objectStore.get("media/video_master/" + done.id());
        assertThat(master).isNotNull();
        List<String> frames = frameColors(master);
        // 段间边界帧混色（±1 帧/段，TC-031 容差）：窗口取 Majority 断言
        int firstGreen = frames.indexOf("green");
        assertThat(firstGreen).as("首段红窗口").isBetween(7, 11);
        assertThat(frames.subList(0, firstGreen - 1)).containsOnly("red");
        assertThat(frames.subList(firstGreen + 1, firstGreen + 10)).as("中段绿窗口")
                .containsOnly("green");
        assertThat(frames.subList(firstGreen + 11, frames.size() - 1)).as("尾段红窗口")
                .containsOnly("red");
        // 三段音频策略：source 有原音、narration 有 TTS、mute 静音（分段 volumedetect）
        assertThat(audioMeanVolumeDb(master, 0, SHOT_SECONDS)).isGreaterThan(-30d);
        assertThat(audioMeanVolumeDb(master, SHOT_SECONDS, SHOT_SECONDS)).isGreaterThan(-40d);
        assertThat(audioMeanVolumeDb(master, SHOT_SECONDS * 2, SHOT_SECONDS)).isLessThan(-60d);
        // 实际时长 = 三段镜头时长（±1 帧/段累计）
        long actualMs = durationMs(master);
        assertThat(actualMs).isBetween((3 * SHOT_SECONDS * 1000L) - 300, (3 * SHOT_SECONDS * 1000L) + 300);

        // ---- C100-19 媒体实测补充：联合导出与成片/清单逐项对账（TC-027/031） ----
        // manifest 声明每镜实际源：own 截取区间与三种音轨策略机器可读可追溯
        client().get().uri("/api/video-production/tasks/{id}/export/bundle", done.id())
                .header("X-Grassland-Identity", sign(ACCOUNT, "recommender"))
                .exchange().expectStatus().isOk();
        byte[] bundle = objectStore.get(
                com.grassland.intelligence.videoproduction.export.ExportBundleService.bundleKey(done.id()));
        assertThat(bundle).isNotNull();
        assertThat(zipEntries(bundle)).contains("bundle/manifest.json", "bundle/master.mp4",
                "bundle/subtitle.srt", "bundle/segments/shot-1.mp4", "bundle/segments/shot-2.mp4",
                "bundle/segments/shot-3.mp4");
        String manifest = zipText(bundle, "bundle/manifest.json");
        assertThat(manifest).contains("\"mediaId\":\"" + mediaId + "\"")
                .contains("\"trimStartMs\":500")
                .contains("\"trimEndMs\":" + (500 + SHOT_SECONDS * 1000))
                .contains("\"audioMode\":\"source\"")
                .contains("\"audioMode\":\"narration\"")
                .contains("\"audioMode\":\"mute\"");
        // 不编造字幕：SRT 只含 narration 镜文本（source/mute 镜无音频行→无 cue），
        // 时间轴按镜序偏移（narration 是第 2 镜 → 首条 cue 起点在 5s 附近，非 0）
        String srt = zipText(bundle, "bundle/subtitle.srt");
        assertThat(srt).contains("第二镜旁白八个字");
        assertThat(srt).doesNotContain("第一镜旁白").doesNotContain("第三镜旁白");
        java.util.regex.Matcher range = java.util.regex.Pattern
                .compile("(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3}) --> ").matcher(srt);
        assertThat(range.find()).isTrue();
        long firstCueStartMs = Long.parseLong(range.group(1)) * 3_600_000L
                + Long.parseLong(range.group(2)) * 60_000L
                + Long.parseLong(range.group(3)) * 1000L
                + Long.parseLong(range.group(4));
        assertThat(firstCueStartMs).as("narration 字幕起点应偏移到第 2 镜").isBetween(4500L, 5500L);
    }

    @Test
    @DisplayName("TC-031 混合：2 generated + 1 own（video 模式）→ 仅 generated 派生候选；段顺序=镜序")
    void mixedComposition() throws Exception {
        UUID storyboardId = seedStoryboard(30);
        byte[] twoColor = twoColorMp4("0x0000FF", "0x00FF00");
        UUID mediaId = seedOwnMedia("own/mixed", twoColor);
        List<UUID> shotIds = new ArrayList<>();
        shotIds.add(seedShot(storyboardId, 1, "第一镜旁白八个字"));
        shotIds.add(seedShot(storyboardId, 2, "第二镜旁白八个字"));
        shotIds.add(seedShot(storyboardId, 3, "第三镜旁白八个字"));
        insertSource(shotIds.get(0), storyboardId, mediaId, 5000L, 5000 + SHOT_SECONDS * 1000L,
                VideoShotSource.AUDIO_MUTE);

        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, "op-own-mixed"))
                .block(Duration.ofSeconds(30));
        assertThat(task.mode()).isEqualTo(VideoProductionTask.MODE_VIDEO);

        // 仅 2 个 generated 镜头派生候选（2 take/镜）
        List<VideoShotTake> all = takeRepo.findByStoryboard(storyboardId).collectList()
                .block(Duration.ofSeconds(5));
        assertThat(all).hasSize(4);
        assertThat(all.stream().map(VideoShotTake::shotId).distinct().count()).isEqualTo(2L);

        // generated 镜头媒体（蓝色）+ TTS；own 镜无音频行（mute）
        for (VideoShotTake take : all) {
            byte[] mp4 = solidColorMp4("0x0000FF");
            String key = "media/video_take/" + take.id();
            objectStore.put(key, mp4);
            seedMediaReference(take.id(), key, mp4.length);
            assertThat(takeRepo.attachMedia(take.id(), take.id(), SHOT_SECONDS * 1000)
                    .block(Duration.ofSeconds(5))).isTrue();
        }
        for (UUID shotId : shotIds.subList(1, 3)) {
            VideoShotAudio audio = audios.findByShot(shotId).block(Duration.ofSeconds(5));
            assertThat(audio).isNotNull();
            ttsWorker.process(audio).block(Duration.ofSeconds(30));
        }
        assertThat(audios.findByStoryboard(storyboardId).count().block(Duration.ofSeconds(5))).isEqualTo(2L);

        // 选片只覆盖 generated 镜头（own 镜头无需选片）→ 合成
        List<VideoProductionTaskService.Selection> selections = new ArrayList<>();
        java.util.Set<UUID> coveredShots = new java.util.HashSet<>();
        for (VideoShotTake take : all) {
            if (coveredShots.add(take.shotId())) {
                selections.add(new VideoProductionTaskService.Selection(take.shotId(), take.id()));
            }
        }
        taskService.select(task.id(), ACCOUNT, selections, false).block(Duration.ofSeconds(10));
        task = taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        composition.compose(task).block(Duration.ofSeconds(180));
        VideoProductionTask done = taskRepo.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(done.phase()).isEqualTo(VideoProductionTask.PHASE_SUCCEEDED);

        // 段顺序：镜1=own 绿段、镜2/3=generated 蓝
        byte[] master = objectStore.get("media/video_master/" + done.id());
        List<String> frames = frameColors(master);
        int firstBlue = frames.indexOf("blue");
        assertThat(firstBlue).isGreaterThan(0);
        assertThat(frames.subList(0, firstBlue)).containsOnly("green");
        assertThat(frames.subList(firstBlue, frames.size())).containsOnly("blue");
    }

    @Test
    @DisplayName("TC-030：own 镜头重抽 409；素材失效合成失败定位，不偷换付费生成")
    void ownShotGatesAndUnavailableMedia() throws Exception {
        UUID storyboardId = seedStoryboard(30);
        byte[] twoColor = twoColorMp4("0xFF0000", "0x00FF00");
        UUID mediaId = seedOwnMedia("own/gate", twoColor);
        List<UUID> shotIds = new ArrayList<>();
        for (int seq = 1; seq <= 3; seq++) {
            shotIds.add(seedShot(storyboardId, seq, "第" + seq + "镜旁白八个字"));
        }
        for (UUID shotId : shotIds) {
            insertSource(shotId, storyboardId, mediaId, 500L, 500 + SHOT_SECONDS * 1000L,
                    VideoShotSource.AUDIO_MUTE);
        }
        VideoProductionTask task = taskService
                .create(ACCOUNT, null, new VideoProductionTaskService.CreateRequest(storyboardId, "op-own-gate"))
                .block(Duration.ofSeconds(30));

        // own 镜头重抽（任务内 regenerate 与成片后 reroll 语义同闸——这里测 regenerate 面即可）
        UUID ownShot = shotIds.get(0);
        UUID taskId = task.id();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> taskService.regenerate(taskId, ACCOUNT, ownShot).block(Duration.ofSeconds(10)))
                .hasMessageContaining("自有素材镜头不支持重抽");

        // 素材在合成前被删除 → 合成失败定位（不自动改付费生成）
        objectStore.remove("own/gate");
        task = taskService.requestCompose(task.id(), ACCOUNT).block(Duration.ofSeconds(10));
        composition.compose(task).block(Duration.ofSeconds(60));
        VideoProductionTask failed = taskRepo.findById(task.id(), ACCOUNT).block(Duration.ofSeconds(5));
        assertThat(failed.phase()).isEqualTo(VideoProductionTask.PHASE_FAILED);
        assertThat(failed.errorMessage()).isNotBlank();
        // 未派生任何生成候选（失败路径没有偷换 provider）
        assertThat(takeRepo.findByStoryboard(storyboardId).count().block(Duration.ofSeconds(5))).isZero();
        verify(credits, times(0)).refund(any(), anyString());
    }

    // ---- 帮手 ----

    private void insertSource(UUID shotId, UUID storyboardId, UUID mediaId, Long trimStart, Long trimEnd,
            String audioMode) {
        db.sql("INSERT INTO video_shot_media_source(shot_id, storyboard_id, source_kind, media_id, "
                        + "trim_start_ms, trim_end_ms, audio_mode) VALUES (CAST(:shot AS uuid), "
                        + "CAST(:sb AS uuid), 'own-media', CAST(:media AS uuid), :trimStart, :trimEnd, :audio)")
                .bind("shot", shotId.toString()).bind("sb", storyboardId.toString())
                .bind("media", mediaId.toString()).bind("trimStart", trimStart)
                .bind("trimEnd", trimEnd).bind("audio", audioMode)
                .then().block(Duration.ofSeconds(5));
    }

    private UUID seedOwnMedia(String objectKey, byte[] content) {
        objectStore.put(objectKey, content);
        return UUID.fromString(db.sql("INSERT INTO media_reference(id, owner_account_id, purpose, "
                        + "object_key, mime_type, size_bytes, source, status) VALUES (gen_random_uuid(), "
                        + ":account, 'store-media', :objectKey, 'video/mp4', :size, 'upload', 'active') "
                        + "RETURNING id::text")
                .bind("account", ACCOUNT).bind("objectKey", objectKey).bind("size", content.length)
                .map(row -> row.get(0, String.class)).one().block(Duration.ofSeconds(5)));
    }

    /** 双色 mp4：前 2s colorA、后 2s colorB（无音轨——音轨由各模式行单独验证）。 */
    private static byte[] twoColorMp4(String colorA, String colorB) throws IOException, InterruptedException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("own-two-color", ".mp4");
        try {
            Process process = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "color=c=" + colorA + ":s=540x960:d=" + SHOT_SECONDS + ":r=30",
                    "-f", "lavfi", "-i", "color=c=" + colorB + ":s=540x960:d=" + SHOT_SECONDS + ":r=30",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=" + (SHOT_SECONDS * 2),
                    "-filter_complex", "[0:v][1:v]concat=n=2:v=1:a=0[v]",
                    "-map", "[v]", "-map", "2:a", "-shortest", "-pix_fmt", "yuv420p",
                    "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac",
                    file.toString())
                    .redirectErrorStream(false).start();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            assertThat(process.exitValue()).as("双色素材生成失败: %s", stderr).isEqualTo(0);
            return java.nio.file.Files.readAllBytes(file);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static byte[] solidColorMp4(String color) throws IOException, InterruptedException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("own-solid", ".mp4");
        try {
            Process process = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-y",
                    "-f", "lavfi", "-i", "color=c=" + color + ":s=540x960:d=" + SHOT_SECONDS + ":r=30",
                    "-pix_fmt", "yuv420p", "-c:v", "libx264", "-preset", "ultrafast",
                    file.toString())
                    .redirectErrorStream(false).start();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            assertThat(process.exitValue()).as("纯色素材生成失败: %s", stderr).isEqualTo(0);
            return java.nio.file.Files.readAllBytes(file);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    /** 2fps 抽帧 rgb24 分类（red/green/blue/other）。 */
    private static List<String> frameColors(byte[] mp4) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("ffmpeg", "-loglevel", "error", "-i", "pipe:0",
                "-vf", "fps=2,scale=4:4", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1")
                .redirectErrorStream(false).start();
        process.getOutputStream().write(mp4);
        process.getOutputStream().close();
        byte[] raw = process.getInputStream().readAllBytes();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        process.waitFor();
        assertThat(process.exitValue()).as("抽帧失败: %s", stderr).isEqualTo(0);
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
            } else if (b > r + 40 && b > g + 40) {
                colors.add("blue");
            } else {
                colors.add("other");
            }
        }
        return colors;
    }

    /** 指定时间窗的平均音量（dB）——source 有原音、narration 有配音、mute 应近静音。 */
    private static double audioMeanVolumeDb(byte[] mp4, double fromSeconds, double durationSeconds)
            throws IOException, InterruptedException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("own-audio", ".mp4");
        java.nio.file.Files.write(file, mp4);
        try {
            Process process = new ProcessBuilder("ffmpeg", "-loglevel", "info", "-ss",
                    String.valueOf(fromSeconds), "-t", String.valueOf(durationSeconds), "-i", file.toString(),
                    "-vn", "-af", "volumedetect", "-f", "null", "-")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            int index = output.indexOf("mean_volume:");
            if (index < 0) {
                return -91d;
            }
            String value = output.substring(index + "mean_volume:".length()).trim().split("\\s")[0];
            return Double.parseDouble(value);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    private static long durationMs(byte[] mp4) throws IOException, InterruptedException {
        java.nio.file.Path file = java.nio.file.Files.createTempFile("own-duration", ".mp4");
        java.nio.file.Files.write(file, mp4);
        try {
            Process process = new ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", file.toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            process.waitFor();
            return Math.round(Double.parseDouble(output) * 1000);
        } finally {
            java.nio.file.Files.deleteIfExists(file);
        }
    }

    // ---- zip 帮手（C100-19 联合导出断言；VideoExportBundleIT 同款约定） ----

    private static List<String> zipEntries(byte[] zipBytes) {
        List<String> names = new ArrayList<>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
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
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entryName.equals(entry.getName())) {
                    return new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        } catch (Exception error) {
            throw new IllegalStateException("zip 读取失败: " + entryName, error);
        }
        throw new IllegalStateException("zip 条目不存在: " + entryName);
    }

    private void seedCapability(String capability, String model) {
        // sandbox 能力行共库互斥（idx_platform_model_config_current 唯一）：先清残留再种
        // （CanvasWorkflowIntegrationIT 等同类 IT 亦种 sandbox video 行）。
        db.sql("DELETE FROM platform_model_config WHERE provider='sandbox' AND capability=:capability")
                .bind("capability", capability).then().block(Duration.ofSeconds(10));
        db.sql("""
                WITH ins AS (
                    INSERT INTO platform_provider_credential(name, provider, base_url, enabled)
                    VALUES (:name, 'sandbox', :baseUrl, true)
                    ON CONFLICT DO NOTHING RETURNING id
                ), cred AS (
                    SELECT id FROM ins
                    UNION ALL
                    SELECT id FROM platform_provider_credential WHERE name = :name
                    LIMIT 1
                )
                INSERT INTO platform_model_config(capability, model_role, provider, model, base_url,
                    health_status, enabled, version, credential_id)
                SELECT :capability, 'primary', 'sandbox', :model, :baseUrl, 'healthy', true, 1, cred.id
                FROM cred
                """)
                .bind("name", "it-own-" + capability)
                .bind("capability", capability)
                .bind("baseUrl", "https://" + capability + ".sandbox.invalid")
                .bind("model", model)
                .then().block(Duration.ofSeconds(10));
    }

    private void seedMediaReference(UUID mediaId, String objectKey, int sizeBytes) {
        db.sql("""
                INSERT INTO media_reference(id, owner_account_id, organization_id, purpose, domain_type,
                    domain_id, object_key, upload_key, mime_type, size_bytes, checksum, source, status)
                VALUES (CAST(:id AS uuid), :account, NULL, 'video_take', 'video_shot_take',
                    CAST(:domain AS uuid), :key, NULL, 'video/mp4', :size, 'it-own', 'generated', 'active')
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
        String payload = "{\"images\":[\"" + IMAGE + "\"],\"shopName\":\"自有素材店\"}";
        return UUID.fromString(db.sql("INSERT INTO video_storyboard(account_id, target_duration_seconds, "
                        + "request_payload) VALUES (:account, :duration, CAST(:payload AS jsonb)) RETURNING id::text")
                .bind("account", ACCOUNT).bind("duration", targetDurationSeconds)
                .bind("payload", payload)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }

    private UUID seedShot(UUID storyboardId, int seq, String narration) {
        return UUID.fromString(db.sql("INSERT INTO video_shot(storyboard_id, seq, visual, narration, "
                        + "planned_seconds, camera_move, anchor_image_index, prompt) VALUES "
                        + "(CAST(:sb AS uuid), :seq, '画面', :narration, :planned, '固定机位', 1, '提示词') "
                        + "RETURNING id::text")
                .bind("sb", storyboardId.toString()).bind("seq", seq).bind("narration", narration)
                .bind("planned", SHOT_SECONDS)
                .map(row -> row.get("id", String.class)).one().block(Duration.ofSeconds(5)));
    }
}
