package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.JsonNode;
import com.grassland.intelligence.ai.run.AiExecutionService;
import com.grassland.intelligence.media.MediaPurpose;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.mediaplatform.MediaProcessRunner;
import com.grassland.intelligence.speech.AudioDurationProbe;
import com.grassland.storage.ObjectStorageAdapter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 成片合成（任务书 #64 卡8，§4.7）：逐镜 normalize（1080×1920/30fps/h264+aac、音画对齐）→
 * concat → 硬字幕烧录 + BGM 混音 → 成片/SRT 归档（video_master）→ ffprobe 实际秒数一口价结算。
 * 图文成片：无视频渠道任务的镜头片段由 zoompan Ken Burns 直接渲染（锚定图复用相邻）。
 *
 * <p>ffmpeg 全部经 {@link MediaProcessRunner#ffmpeg(List, Duration)}（上限 600s），临时文件
 * 建在 MediaArtifactStore 约定外的独立 compose 目录并 finally 清理。失败 → 任务 failed +
 * handleFailure 释放预留（P2 全额退）。
 */
@Service
public class VideoCompositionService {

    private static final Logger log = LoggerFactory.getLogger(VideoCompositionService.class);
    private static final Duration COMPOSE_TIMEOUT = Duration.ofSeconds(600);
    private static final String VIDEO_ENCODER = "libx264";

    private final VideoProductionTaskRepository tasks;
    private final VideoStoryboardRepository storyboards;
    private final VideoShotRepository shots;
    private final VideoShotTakeRepository takes;
    private final VideoShotAudioRepository audios;
    private final MediaReferenceRepository mediaRefs;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final MediaProcessRunner runner;
    private final AudioDurationProbe durationProbe;
    private final AiExecutionService executions;
    private final VideoProductionTaskService taskService;
    private final BgmSelectionService bgmSelection;
    private final BgmTrackRepository bgmTracks;
    private final com.grassland.messaging.outbox.OutboxRepository outbox;
    private final TransactionalOperator transactions;

    public VideoCompositionService(VideoProductionTaskRepository tasks,
            VideoStoryboardRepository storyboards, VideoShotRepository shots,
            VideoShotTakeRepository takes, VideoShotAudioRepository audios,
            MediaReferenceRepository mediaRefs, ObjectProvider<ObjectStorageAdapter> storageProvider,
            MediaProcessRunner runner, AudioDurationProbe durationProbe, AiExecutionService executions,
            VideoProductionTaskService taskService, BgmSelectionService bgmSelection,
            BgmTrackRepository bgmTracks, com.grassland.messaging.outbox.OutboxRepository outbox,
            TransactionalOperator transactions) {
        this.tasks = tasks;
        this.storyboards = storyboards;
        this.shots = shots;
        this.takes = takes;
        this.audios = audios;
        this.mediaRefs = mediaRefs;
        this.storageProvider = storageProvider;
        this.runner = runner;
        this.durationProbe = durationProbe;
        this.executions = executions;
        this.taskService = taskService;
        this.bgmSelection = bgmSelection;
        this.bgmTracks = bgmTracks;
        this.outbox = outbox;
        this.transactions = transactions;
    }

    /** worker 以已领单任务驱动；失败退款收口在这里。 */
    public Mono<Void> compose(VideoProductionTask task) {
        return Mono.fromCallable(() -> Path.of(System.getProperty("java.io.tmpdir"),
                        "grassland-compose", task.id().toString()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(workDir -> composeInDir(task, workDir)
                        .doFinally(signal -> deleteRecursively(workDir)))
                .onErrorResume(error -> failTask(task, error));
    }

    private Mono<Void> composeInDir(VideoProductionTask task, Path workDir) {
        return Mono.fromCallable(() -> {
            Files.createDirectories(workDir);
            return true;
        }).subscribeOn(Schedulers.boundedElastic())
                .then(storyboards.findById(task.storyboardId()))
                .flatMap(storyboard -> Mono.zip(
                        shots.findByStoryboard(storyboard.id()).collectList(),
                        audios.findByStoryboard(storyboard.id()).collectList(),
                        takes.findByStoryboard(storyboard.id()).collectList(),
                        Mono.just(selectionOf(task)),
                        imagesOf(storyboard)))
                // 空曲库 → 无 BGM 合成（§4.7）；Optional 包装修空 Mono 短路
                .flatMap(tuple -> bgmSelection.pick(null)
                        .map(java.util.Optional::of)
                        .defaultIfEmpty(java.util.Optional.empty())
                        .flatMap(bgm -> renderAndSettle(task, workDir, tuple.getT1(), tuple.getT2(),
                                tuple.getT3(), tuple.getT4(), tuple.getT5(), bgm.orElse(null))));
    }

    // ---------------- 渲染（boundedElastic 阻塞段） ----------------

    private Mono<Void> renderAndSettle(VideoProductionTask task, Path workDir,
            List<VideoShot> shotList, List<VideoShotAudio> audioList, List<VideoShotTake> takeList,
            Map<String, UUID> selection, List<String> imageList, BgmTrack bgm) {
        return Mono.fromCallable(() ->
                        render(task, workDir, shotList, audioList, takeList, selection, imageList, bgm))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(rendered -> settle(task, rendered));
    }

    /** 全部阻塞 ffmpeg 调用集中在这里（worker 调度线程外执行）。 */
    private Rendered render(VideoProductionTask task, Path workDir, List<VideoShot> shotList,
            List<VideoShotAudio> audioList, List<VideoShotTake> takeList,
            Map<String, UUID> selection, List<String> imageList, BgmTrack bgm) throws IOException {
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new IllegalStateException("成片合成需要启用对象存储");
        }
        extractFonts(workDir);

        Map<String, VideoShotAudio> audioByShot = new LinkedHashMap<>();
        audioList.forEach(audio -> audioByShot.put(audio.shotId().toString(), audio));
        Map<String, VideoShotTake> selectedTakes = new LinkedHashMap<>();
        for (VideoShotTake take : takeList) {
            if (take.id().equals(selection.get(take.shotId().toString()))) {
                selectedTakes.put(take.shotId().toString(), take);
            }
        }

        List<Path> segments = new ArrayList<>();
        List<SrtBuilder.Cue> absoluteCues = new ArrayList<>();
        double offsetSeconds = 0;
        for (VideoShot shot : shotList) {
            VideoShotAudio audio = audioByShot.get(shot.id().toString());
            boolean voiced = audio != null && audio.isVoiced();
            byte[] audioBytes = voiced ? storage.getObject(mediaObjectKey(audio.mediaId())) : null;
            Double audioSeconds = voiced && audio.durationMs() != null
                    ? audio.durationMs() / 1000.0 : null;

            Path segment = workDir.resolve("seg-" + shot.seq() + ".mp4");
            if (task.isSlideshow()) {
                renderSlideshowSegment(workDir, segment, shot, imageList, audioBytes, audioSeconds);
            } else {
                VideoShotTake take = selectedTakes.get(shot.id().toString());
                if (take == null || !take.isSelectable()) {
                    throw new IllegalStateException("第 " + shot.seq() + " 镜没有已选候选，无法合成");
                }
                renderVideoSegment(workDir, segment, take, shot,
                        storage.getObject(mediaObjectKey(take.mediaId())), audioBytes, audioSeconds);
            }

            double segmentSeconds = durationProbe.probe(java.nio.file.Files.readAllBytes(segment)) / 1000.0;
            if (voiced) {
                for (SrtBuilder.Cue cue : SrtBuilder.parseCues(audio.cues(), shot.narration())) {
                    absoluteCues.add(new SrtBuilder.Cue(cue.text(),
                            cue.startMs() + Math.round(offsetSeconds * 1000),
                            cue.endMs() + Math.round(offsetSeconds * 1000)));
                }
            }
            offsetSeconds += segmentSeconds;
            segments.add(segment);
        }
        if (segments.isEmpty()) {
            throw new IllegalStateException("没有可合成的镜头");
        }

        // concat 清单
        Path listFile = workDir.resolve("list.txt");
        Files.writeString(listFile, segments.stream()
                .map(path -> "file '" + path.getFileName() + "'")
                .reduce((a, b) -> a + "\n" + b).orElseThrow(), StandardCharsets.UTF_8);

        // SRT（无字幕也落文件——P4 SRT 下载恒可用）
        String srt = SrtBuilder.buildSrt(absoluteCues);
        Path srtFile = workDir.resolve("subs.srt");
        Files.writeString(srtFile, srt, StandardCharsets.UTF_8);

        boolean hasVoice = shotList.stream()
                .anyMatch(shot -> audioByShot.get(shot.id().toString()) != null
                        && audioByShot.get(shot.id().toString()).isVoiced());
        byte[] bgmBytes = bgm == null ? null : storage.getObject(bgm.objectKey());
        if (bgmBytes != null) {
            Path bgmFile = workDir.resolve("bgm.bin");
            Files.write(bgmFile, bgmBytes);
        }

        Path finalVideo = workDir.resolve("master.mp4");
        renderFinal(workDir, finalVideo, hasVoice, bgmBytes != null);

        byte[] masterBytes = Files.readAllBytes(finalVideo);
        long actualMs = durationProbe.probe(masterBytes);
        int actualSeconds = (int) Math.round(actualMs / 1000.0);
        return new Rendered(masterBytes, srt.getBytes(StandardCharsets.UTF_8), actualSeconds);
    }

    /** 逐镜视频片段：normalize + 音画对齐（§4.7），统一 h264+aac(48k stereo) 保证 concat 可 copy。 */
    private void renderVideoSegment(Path workDir, Path segment, VideoShotTake take, VideoShot shot,
            byte[] videoBytes, byte[] audioBytes, Double audioSeconds) throws IOException {
        Path videoFile = workDir.resolve("take-" + take.id() + ".mp4");
        Files.write(videoFile, videoBytes);
        double videoSeconds = durationProbe.probe(videoBytes) / 1000.0;
        CompositionMath.Alignment alignment =
                CompositionMath.planAlignment(videoSeconds, audioSeconds, shot.plannedSeconds());

        List<String> args = new ArrayList<>(List.of("-y", "-i", videoFile.getFileName().toString()));
        String audioLabel;
        if (audioBytes != null) {
            Path audioFile = workDir.resolve("audio-" + shot.id() + ".bin");
            Files.write(audioFile, audioBytes);
            args.addAll(List.of("-i", audioFile.getFileName().toString()));
            audioLabel = "1:a";
        } else {
            args.addAll(List.of("-f", "lavfi", "-t",
                    String.valueOf(alignment.targetSeconds() + 1), "-i", "anullsrc=r=48000:cl=stereo"));
            audioLabel = "1:a";
        }

        StringBuilder filters = new StringBuilder();
        filters.append("[0:v]scale=1080:1920:force_original_aspect_ratio=decrease,")
                .append("pad=1080:1920:(ow-iw)/2:(oh-ih)/2,fps=30,setsar=1[v0];");
        filters.append("[v0]tpad=stop_mode=clone:stop_duration=")
                .append(alignment.padSeconds()).append("[v1];");
        filters.append("[v1]trim=duration=").append(alignment.targetSeconds())
                .append(",setpts=PTS-STARTPTS[v];");
        if (alignment.atempo() != null) {
            filters.append("[").append(audioLabel).append("]atempo=")
                    .append(alignment.atempo()).append(",apad,atrim=duration=")
                    .append(alignment.targetSeconds()).append(",asetpts=PTS-STARTPTS[a]");
        } else {
            filters.append("[").append(audioLabel).append("]apad,atrim=duration=")
                    .append(alignment.targetSeconds()).append(",asetpts=PTS-STARTPTS[a]");
        }

        args.addAll(List.of("-filter_complex", filters.toString(),
                "-map", "[v]", "-map", "[a]",
                "-c:v", VIDEO_ENCODER, "-preset", "veryfast", "-pix_fmt", "yuv420p", "-r", "30",
                "-c:a", "aac", "-ar", "48000", "-ac", "2",
                "-t", String.valueOf(alignment.targetSeconds()),
                segment.getFileName().toString()));
        runner.ffmpeg(args, COMPOSE_TIMEOUT, workDir);
    }

    /** 图文成片片段：锚定图 zoompan Ken Burns（奇推近/偶拉远）+ 音轨（真配音或静音）。 */
    private void renderSlideshowSegment(Path workDir, Path segment, VideoShot shot,
            List<String> imageList, byte[] audioBytes, Double audioSeconds) throws IOException {
        String image = anchorImageFor(shot, imageList);
        Path imageFile = workDir.resolve("img-" + shot.seq() + ".jpg");
        String base64 = image.contains(",") ? image.substring(image.indexOf(',') + 1) : image;
        Files.write(imageFile, Base64.getDecoder().decode(base64));

        double target = audioSeconds != null && audioSeconds > 0
                ? audioSeconds : shot.plannedSeconds();
        CompositionMath.Alignment alignment = CompositionMath.planAlignment(target, audioSeconds,
                shot.plannedSeconds());

        List<String> args = new ArrayList<>(List.of("-y", "-i", imageFile.getFileName().toString()));
        if (audioBytes != null) {
            Path audioFile = workDir.resolve("audio-" + shot.id() + ".bin");
            Files.write(audioFile, audioBytes);
            args.addAll(List.of("-i", audioFile.getFileName().toString()));
        } else {
            args.addAll(List.of("-f", "lavfi", "-t", String.valueOf(target + 1), "-i",
                    "anullsrc=r=48000:cl=stereo"));
        }

        int frames = (int) Math.ceil(alignment.targetSeconds() * 30);
        String filters = "[0:v]scale=-2:2160,zoompan=z='" + CompositionMath.zoompanExpression(shot.seq())
                + "':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=" + frames
                + ":s=1080x1920:fps=30,trim=duration=" + alignment.targetSeconds()
                + ",setpts=PTS-STARTPTS[v];"
                + "[1:a]" + (alignment.atempo() != null ? "atempo=" + alignment.atempo() + "," : "")
                + "apad,atrim=duration=" + alignment.targetSeconds() + ",asetpts=PTS-STARTPTS[a]";
        args.addAll(List.of("-filter_complex", filters.toString(),
                "-map", "[v]", "-map", "[a]",
                "-c:v", VIDEO_ENCODER, "-preset", "veryfast", "-pix_fmt", "yuv420p", "-r", "30",
                "-c:a", "aac", "-ar", "48000", "-ac", "2",
                "-t", String.valueOf(alignment.targetSeconds()),
                segment.getFileName().toString()));
        runner.ffmpeg(args, COMPOSE_TIMEOUT, workDir);
    }

    /** 终段：concat + 硬字幕 + BGM 混音（§4.7 音量：配音 1.0/BGM 0.2；无配音 BGM 0.9）。 */
    private void renderFinal(Path workDir, Path output, boolean hasVoice, boolean hasBgm)
            throws IOException {
        List<String> args = new ArrayList<>(List.of("-y",
                "-f", "concat", "-safe", "0", "-i", "list.txt"));
        if (hasBgm) {
            args.addAll(List.of("-stream_loop", "-1", "-i", "bgm.bin"));
        }
        // libass 缺席（如部分 Homebrew 构建）时降级不烧录——SRT 文件恒交付（P4 二合一的另一半）
        String videoChain = subtitlesAvailable()
                ? "[0:v]subtitles=" + CompositionMath.subtitleFilter("subs.srt", "fonts") + "[vout]"
                : "[0:v]copy[vout]";
        StringBuilder filters = new StringBuilder(videoChain + ";");
        String audioMap;
        if (hasVoice && hasBgm) {
            filters.append("[0:a]volume=1.0[va];[1:a]volume=0.2[ba];")
                    .append("[va][ba]amix=inputs=2:duration=first:dropout_transition=0[aout]");
            audioMap = "[aout]";
        } else if (!hasVoice && hasBgm) {
            filters.append("[1:a]volume=0.9[aout]");
            audioMap = "[aout]";
        } else {
            filters.append("[0:a]anull[aout]");
            audioMap = "[aout]";
        }
        args.addAll(List.of("-filter_complex", filters.toString(),
                "-map", "[vout]", "-map", audioMap,
                "-c:v", VIDEO_ENCODER, "-preset", "veryfast", "-pix_fmt", "yuv420p", "-r", "30",
                "-c:a", "aac", "-ar", "48000", "-ac", "2",
                "-movflags", "+faststart",
                output.getFileName().toString()));
        runner.ffmpeg(args, COMPOSE_TIMEOUT, workDir);
    }

    // ---------------- 结算与归档 ----------------

    private Mono<Void> settle(VideoProductionTask task, Rendered rendered) {
        long startedAt = System.currentTimeMillis();
        int actualCents = Math.multiplyExact(rendered.actualSeconds(), task.unitPriceCents());
        return archive(task, rendered.finalBytes(), "video/mp4",
                        "media/video_master/" + task.id(), MediaPurpose.VIDEO_MASTER)
                .flatMap(finalReference -> archive(task, rendered.srtBytes(),
                                "application/x-subrip", "media/video_master_srt/" + task.id(),
                                MediaPurpose.VIDEO_MASTER)
                        .flatMap(srtReference -> tasks.attachResult(task.id(),
                                mediaIdOf(finalReference), mediaIdOf(srtReference),
                                rendered.actualSeconds(), actualCents)))
                .then(taskService.rebuildContext(task)
                        .flatMap(ctx -> executions.settleSuccessWithCost(ctx, actualCents, 0, 0, 0,
                                rendered.actualSeconds())))
                .then()
                .doOnSuccess(ignored -> log.info(
                        "video master composed metric=compose_completed taskId={} actualSeconds={} "
                                + "actualCents={} estimatedCents={} revenueDeltaCents={} "
                                + "elapsedMs={} status=succeeded",
                        task.id(), rendered.actualSeconds(), actualCents, task.estimatedCostCents(),
                        actualCents - task.estimatedCostCents(),
                        System.currentTimeMillis() - startedAt));
    }

    private Mono<MediaReference> archive(VideoProductionTask task, byte[] bytes, String mime,
            String objectKey, MediaPurpose purpose) {
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return Mono.error(new IllegalStateException("成片归档需要启用对象存储"));
        }
        MediaReference reference = new MediaReference(UUID.nameUUIDFromBytes(
                objectKey.getBytes(StandardCharsets.UTF_8)), task.accountId(), task.organizationId(),
                purpose.db(), "video_production_task", task.id().toString(), objectKey, mime,
                bytes.length, VideoAssetArchiveService.VideoArchiveChecksums.sha256(bytes), "generated",
                com.grassland.intelligence.media.MediaStatus.ACTIVE, Instant.now(), null, null);
        return Mono.fromRunnable(() -> storage.putObject(objectKey, bytes, mime))
                .subscribeOn(Schedulers.boundedElastic())
                .then(transactions.transactional(mediaRefs.insert(reference)
                        .flatMap(active -> outbox.append(
                                com.grassland.intelligence.media.MediaLifecycleEvents.activated(active))
                                .thenReturn(active))));
    }

    private Mono<Void> failTask(VideoProductionTask task, Throwable error) {
        log.error("video composition failed taskId={}", task.id(), error);
        String message = error.getMessage() == null ? "成片合成失败" : error.getMessage();
        return taskService.rebuildContext(task)
                .flatMap(ctx -> executions.handleFailure(ctx, message))
                .then(tasks.markFailed(task.id(), "compose_failed", message))
                .onErrorResume(cleanupError -> {
                    log.error("composition failure handling failed taskId={}", task.id(), cleanupError);
                    return Mono.empty();
                })
                .then();
    }

    // ---------------- 工具 ----------------

    private Map<String, UUID> selectionOf(VideoProductionTask task) {
        Map<String, UUID> selection = new LinkedHashMap<>();
        if (task.selection() != null && !task.selection().isBlank()) {
            try {
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(task.selection())
                        .fields().forEachRemaining(entry -> {
                            try {
                                selection.put(entry.getKey(), UUID.fromString(entry.getValue().asText()));
                            } catch (IllegalArgumentException ignored) {
                                // 脏 selection 由合成时逐镜校验兜底
                            }
                        });
            } catch (Exception ignored) {
                // 同上
            }
        }
        return selection;
    }

    /** 请求快照里的素材图列表（响应式——事件循环上禁 block）。 */
    private Mono<List<String>> imagesOf(VideoStoryboard storyboard) {
        return Mono.fromCallable(() -> {
            JsonNode images = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(storyboard.requestPayload()).path("images");
            List<String> list = new ArrayList<>();
            images.forEach(image -> list.add(image.asText("")));
            return List.copyOf(list);
        }).onErrorReturn(List.of());
    }

    /** 无锚定图镜头复用相邻锚定图（前向优先；全 0 回落第 1 张，§2 范围外约定）。 */
    static String anchorImageFor(VideoShot shot, List<String> imageList) {
        if (imageList.isEmpty()) {
            throw new IllegalStateException("图文成片需要至少一张素材图");
        }
        if (shot.anchorImageIndex() >= 1 && shot.anchorImageIndex() <= imageList.size()) {
            return imageList.get(shot.anchorImageIndex() - 1);
        }
        int fallback = Math.min(Math.max(shot.seq() - 1, 0), imageList.size() - 1);
        return imageList.get(fallback);
    }

    private String mediaObjectKey(UUID mediaId) {
        MediaReference reference = mediaRefs.findById(mediaId).block(Duration.ofSeconds(5));
        if (reference == null) {
            throw new IllegalStateException("媒体引用不存在：" + mediaId);
        }
        return reference.objectKey();
    }

    private void extractFonts(Path workDir) throws IOException {
        Path fontsDir = workDir.resolve("fonts");
        Files.createDirectories(fontsDir);
        try (var input = new ClassPathResource("/fonts/Inter-Regular.ttf").getInputStream()) {
            Files.copy(input, fontsDir.resolve("Inter-Regular.ttf"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static UUID mediaIdOf(MediaReference reference) {
        return reference.id();
    }

    private static volatile Boolean subtitlesFilterCache;

    /** ffmpeg 是否带 libass（subtitles 滤镜）。结果进程内缓存。 */
    static boolean subtitlesAvailable() {
        if (subtitlesFilterCache == null) {
            subtitlesFilterCache = probeSubtitlesFilter();
        }
        return subtitlesFilterCache;
    }

    private static boolean probeSubtitlesFilter() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-hide_banner", "-filters").start();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                boolean found = reader.lines().anyMatch(line -> line.contains(" subtitles "));
                process.waitFor();
                return found;
            }
        } catch (Exception error) {
            log.warn("subtitles filter probe failed; assuming unavailable", error);
            return false;
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 清理失败交给系统临时目录
                }
            });
        } catch (IOException ignored) {
            // 同上
        }
    }

    private record Rendered(byte[] finalBytes, byte[] srtBytes, int actualSeconds) {}
}
