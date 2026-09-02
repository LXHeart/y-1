package com.grassland.intelligence.videoproduction.export;

import com.grassland.intelligence.creationlineage.CreationGeneration;
import com.grassland.intelligence.creationlineage.CreationGenerationRecorder;
import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.speech.AudioDurationProbe;
import com.grassland.intelligence.videoproduction.VideoProductionTask;
import com.grassland.intelligence.videoproduction.VideoProductionTaskRepository;
import com.grassland.intelligence.videoproduction.VideoShot;
import com.grassland.intelligence.videoproduction.VideoShotAudio;
import com.grassland.intelligence.videoproduction.VideoShotAudioRepository;
import com.grassland.intelligence.videoproduction.VideoShotRepository;
import com.grassland.intelligence.videoproduction.VideoStoryboardRepository;
import com.grassland.storage.ObjectStorageAdapter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * B 轨通用素材包导出（任务书 #66 卡B1，§3 布局契约）：
 *
 * <pre>
 * bundle/分镜稿.md              逐镜：序号/画面/旁白/时长/运镜
 * bundle/audio/shot-{n}.wav     逐镜旁白（TTS 归档件）
 * bundle/subtitle.srt           成片字幕（合成时归档）
 * bundle/segments/shot-{n}.mp4  逐镜段（#65 段缓存直接复用）
 * bundle/master.mp4             成片
 * </pre>
 *
 * <p>权限=任务属主（findById 带账号，非属主 404 不泄露存在性——与详情/字幕端点同口径）；
 * 仅 phase=succeeded 可导出（409）；zip 汇集后写对象存储、一次性 presign；每次导出落
 * creationlineage（kind=video_export）审计。段缓存已被卡10 清理回收的镜头条目静默缺省
 * （master/SRT/分镜稿恒在），导出 advisory 不阻断。
 */
@Service
public class ExportBundleService {

    private static final Logger log = LoggerFactory.getLogger(ExportBundleService.class);
    private static final String BUNDLE_KEY_PREFIX = "media/video_export/";
    private static final String SEGMENT_KEY_PREFIX = "segments/";

    public record ExportArtifact(String downloadUrl, long expiresInSeconds, int entryCount) {}

    /** A 轨产物：zip 里是剪映 draft 目录（draft_name 供前端展示）。 */
    public record JianyingArtifact(String downloadUrl, long expiresInSeconds, int entryCount,
            String draftName) {}

    private final VideoProductionTaskRepository tasks;
    private final VideoShotRepository shots;
    private final VideoShotAudioRepository audios;
    private final MediaReferenceRepository mediaRefs;
    private final CreationGenerationRecorder lineage;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final VideoStoryboardRepository storyboards;
    private final JianyingDraftBuilder jianyingBuilder;
    private final AudioDurationProbe durationProbe;

    public ExportBundleService(VideoProductionTaskRepository tasks, VideoShotRepository shots,
            VideoShotAudioRepository audios, MediaReferenceRepository mediaRefs,
            CreationGenerationRecorder lineage, ObjectProvider<ObjectStorageAdapter> storageProvider,
            VideoStoryboardRepository storyboards, JianyingDraftBuilder jianyingBuilder,
            AudioDurationProbe durationProbe) {
        this.tasks = tasks;
        this.shots = shots;
        this.audios = audios;
        this.mediaRefs = mediaRefs;
        this.lineage = lineage;
        this.storageProvider = storageProvider;
        this.storyboards = storyboards;
        this.jianyingBuilder = jianyingBuilder;
        this.durationProbe = durationProbe;
    }

    public Mono<ExportArtifact> exportBundle(UUID taskId, String accountId, long ttlSeconds) {
        return tasks.findById(taskId, accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "任务不存在")))
                .flatMap(task -> {
                    if (!VideoProductionTask.PHASE_SUCCEEDED.equals(task.phase())) {
                        return Mono.error(new IntelligenceException(409, "任务尚未完成，暂不能导出"));
                    }
                    return assemble(task, accountId, ttlSeconds);
                });
    }

    /**
     * A 轨剪映草稿导出（任务书 #66 卡B2）：draft_content.json（三轨最小集）+ meta + 媒体副本，
     * zip 根为 {@code jianying/{draftName}/}，解包即可放入剪映 draft 根。
     */
    public Mono<JianyingArtifact> exportJianying(UUID taskId, String accountId, long ttlSeconds) {
        return tasks.findById(taskId, accountId)
                .switchIfEmpty(Mono.error(new IntelligenceException(404, "任务不存在")))
                .flatMap(task -> {
                    if (!VideoProductionTask.PHASE_SUCCEEDED.equals(task.phase())) {
                        return Mono.error(new IntelligenceException(409, "任务尚未完成，暂不能导出"));
                    }
                    return assembleJianying(task, ttlSeconds);
                });
    }

    private Mono<JianyingArtifact> assembleJianying(VideoProductionTask task, long ttlSeconds) {
        return Mono.zip(
                        shots.findByStoryboard(task.storyboardId()).collectList(),
                        audios.findByStoryboard(task.storyboardId()).collectList(),
                        storyboards.findById(task.storyboardId()))
                .flatMap(tuple -> Mono.fromCallable(() -> buildJianyingZip(task, tuple.getT1(),
                                tuple.getT2(), tuple.getT3()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(draft -> {
                    String key = jianyingKey(task.id());
                    ObjectStorageAdapter storage = requireStorage();
                    return Mono.fromRunnable(() -> storage.putObject(key, draft.zipBytes(),
                                    "application/zip"))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(recordLineage(task, "jianying", draft.entryCount()))
                            .thenReturn(new JianyingArtifact(
                                    storage.presignDownload(key, ttlSeconds,
                                            "attachment; filename=\"video-jianying-" + task.id()
                                                    + ".zip\"").toString(),
                                    ttlSeconds, draft.entryCount(), draft.draftName()));
                });
    }

    private record JianyingAssembled(byte[] zipBytes, int entryCount, String draftName) {}

    private JianyingAssembled buildJianyingZip(VideoProductionTask task, List<VideoShot> shotList,
            List<VideoShotAudio> audioList,
            com.grassland.intelligence.videoproduction.VideoStoryboard storyboard) throws IOException {
        String draftName = JianyingDraftBuilder.draftNameOf(task.id());
        boolean landscape = "landscape".equals(storyboard.resolutionOrDefault());
        Map<String, VideoShotAudio> audioByShotId = new LinkedHashMap<>();
        audioList.forEach(audio -> audioByShotId.put(audio.shotId().toString(), audio));
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();

        List<JianyingDraftBuilder.ShotInput> inputs = new ArrayList<>();
        for (VideoShot shot : shotList) {
            byte[] segment = storage == null ? null
                    : objectBytes(storage, SEGMENT_KEY_PREFIX + task.id() + "/" + shot.id() + ".mp4");
            VideoShotAudio audio = audioByShotId.get(shot.id().toString());
            byte[] audioBytes = audio == null || audio.mediaId() == null || storage == null ? null
                    : objectBytes(storage, "media/video_shot_audio/" + audio.mediaId());
            long durationMs = plannedDurationMs(shot, segment);
            inputs.add(new JianyingDraftBuilder.ShotInput(shot.seq(), durationMs, segment,
                    audioBytes, shot.narration()));
        }
        JianyingDraftBuilder.DraftBuild draft = jianyingBuilder.build(task.id().toString(), draftName,
                landscape, inputs);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int entries = 0;
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("jianying/" + draftName + "/draft_content.json"));
            zip.write(draft.draftContentJson().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("jianying/" + draftName + "/draft_meta_info.json"));
            zip.write(draft.draftMetaJson().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            entries += 2;
            for (Map.Entry<String, byte[]> material : draft.materials().entrySet()) {
                zip.putNextEntry(new ZipEntry("jianying/" + draftName + "/" + material.getKey()));
                zip.write(material.getValue());
                zip.closeEntry();
                entries++;
            }
            zip.finish();
        }
        return new JianyingAssembled(buffer.toByteArray(), entries, draftName);
    }

    /** 段时长：ffprobe 实测优先（合成段与 planned 有出入），探不到回退计划时长。 */
    private long plannedDurationMs(VideoShot shot, byte[] segment) {
        if (segment != null) {
            try {
                return durationProbe.probe(segment);
            } catch (RuntimeException probeFailed) {
                // 回退计划时长
            }
        }
        return shot.plannedSeconds() * 1000L;
    }

    private Mono<ExportArtifact> assemble(VideoProductionTask task, String accountId, long ttlSeconds) {
        return Mono.zip(
                        shots.findByStoryboard(task.storyboardId()).collectList(),
                        audios.findByStoryboard(task.storyboardId()).collectList(),
                        objectOf(task.finalMediaId()),
                        objectOf(task.srtMediaId()))
                .flatMap(tuple -> Mono.fromCallable(() -> zip(task, tuple.getT1(), tuple.getT2(),
                                tuple.getT3(), tuple.getT4()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(bundle -> {
                    String key = bundleKey(task.id());
                    ObjectStorageAdapter storage = requireStorage();
                    return Mono.fromRunnable(() -> storage.putObject(key, bundle.zipBytes(),
                                    "application/zip"))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(recordLineage(task, "bundle", bundle.entryCount()))
                            .thenReturn(new ExportArtifact(
                                    storage.presignDownload(key, ttlSeconds,
                                            "attachment; filename=\"video-bundle-" + task.id() + ".zip\"")
                                            .toString(),
                                    ttlSeconds, bundle.entryCount()));
                });
    }

    private record AssembledBundle(byte[] zipBytes, int entryCount) {}

    private AssembledBundle zip(VideoProductionTask task, List<VideoShot> shotList,
            List<com.grassland.intelligence.videoproduction.VideoShotAudio> audioList,
            byte[] masterBytes, byte[] srtBytes) throws IOException {
        Map<String, com.grassland.intelligence.videoproduction.VideoShotAudio> audioByShotId =
                new LinkedHashMap<>();
        audioList.forEach(audio -> audioByShotId.put(audio.shotId().toString(), audio));

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int entries = 0;
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            zip.putNextEntry(new ZipEntry("bundle/分镜稿.md"));
            zip.write(storyboardMarkdown(task, shotList).getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            entries++;

            if (masterBytes != null) {
                zip.putNextEntry(new ZipEntry("bundle/master.mp4"));
                zip.write(masterBytes);
                zip.closeEntry();
                entries++;
            }
            if (srtBytes != null) {
                zip.putNextEntry(new ZipEntry("bundle/subtitle.srt"));
                zip.write(srtBytes);
                zip.closeEntry();
                entries++;
            }
            ObjectStorageAdapter storage = storageProvider.getIfAvailable();
            for (VideoShot shot : shotList) {
                com.grassland.intelligence.videoproduction.VideoShotAudio audio =
                        audioByShotId.get(shot.id().toString());
                if (audio != null && audio.mediaId() != null) {
                    byte[] audioBytes = objectBytes(storage, "media/video_shot_audio/" + audio.mediaId());
                    if (audioBytes != null) {
                        zip.putNextEntry(new ZipEntry("bundle/audio/shot-" + shot.seq() + ".wav"));
                        zip.write(audioBytes);
                        zip.closeEntry();
                        entries++;
                    }
                }
                // 段缓存被清理回收的镜头条目静默缺省（advisory，master 恒在）
                byte[] segment = objectBytes(storage,
                        SEGMENT_KEY_PREFIX + task.id() + "/" + shot.id() + ".mp4");
                if (segment != null) {
                    zip.putNextEntry(new ZipEntry("bundle/segments/shot-" + shot.seq() + ".mp4"));
                    zip.write(segment);
                    zip.closeEntry();
                    entries++;
                }
            }
            zip.finish();
        }
        return new AssembledBundle(buffer.toByteArray(), entries);
    }

    /** 逐镜分镜稿（§3 模板：`## 镜头N / 时长 / 运镜` + 画面 + 旁白）。 */
    static String storyboardMarkdown(VideoProductionTask task, List<VideoShot> shotList) {
        StringBuilder md = new StringBuilder();
        md.append("# 分镜稿\n\n");
        md.append("- 任务：").append(task.id()).append('\n');
        md.append("- 模式：").append(task.mode()).append('\n');
        md.append("- 目标时长：").append(task.targetDurationSeconds()).append(" 秒\n");
        if (task.actualDurationSeconds() != null) {
            md.append("- 实际时长：").append(task.actualDurationSeconds()).append(" 秒\n");
        }
        md.append('\n');
        for (VideoShot shot : shotList) {
            md.append("## 镜头").append(shot.seq())
                    .append(" / ").append(shot.plannedSeconds()).append(" 秒 / ")
                    .append(shot.cameraMove() == null ? "—" : shot.cameraMove()).append("\n\n");
            md.append("- 画面：").append(nullToDash(shot.visual())).append('\n');
            md.append("- 旁白：").append(nullToDash(shot.narration())).append('\n');
            md.append('\n');
        }
        return md.toString();
    }

    private Mono<Void> recordLineage(VideoProductionTask task, String format, int entryCount) {
        List<UUID> resultMediaIds = new ArrayList<>();
        if (task.finalMediaId() != null) {
            resultMediaIds.add(task.finalMediaId());
        }
        if (task.srtMediaId() != null) {
            resultMediaIds.add(task.srtMediaId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", format);
        result.put("entries", entryCount);
        result.put("masterMediaId", task.finalMediaId() == null ? null : task.finalMediaId().toString());
        return lineage.record(new CreationGenerationRecorder.Command(
                CreationGeneration.Kind.VIDEO_EXPORT, CreationGeneration.Mode.TASK,
                task.contextSnapshotId(), task.runId(), CreationGeneration.Resolution.PLATFORM,
                task.provider(), task.model(), task.platformModelVersion(), null,
                "video export bundle", Map.of(), List.of(), result, resultMediaIds,
                task.accountId(), task.organizationId()))
                .onErrorResume(error -> {
                    // lineage 登记失败不阻断导出（审计 advisory），仅记日志
                    log.warn("video export lineage record failed taskId={} cause={}", task.id(),
                            String.valueOf(error.getMessage()));
                    return Mono.empty();
                })
                .then();
    }

    public static String bundleKey(UUID taskId) {
        return BUNDLE_KEY_PREFIX + taskId + "/bundle.zip";
    }

    public static String jianyingKey(UUID taskId) {
        return BUNDLE_KEY_PREFIX + taskId + "/jianying.zip";
    }

    private Mono<byte[]> objectOf(UUID mediaId) {
        if (mediaId == null) {
            return Mono.just(new byte[0]);
        }
        return mediaRefs.findById(mediaId)
                .flatMap(reference -> Mono.fromCallable(
                                () -> objectBytes(requireStorage(), reference.objectKey()))
                        .subscribeOn(Schedulers.boundedElastic()))
                .defaultIfEmpty(new byte[0]);
    }

    private static byte[] objectBytes(ObjectStorageAdapter storage, String key) {
        try {
            byte[] bytes = storage.getObject(key);
            return bytes == null || bytes.length == 0 ? null : bytes;
        } catch (RuntimeException missingOrFailed) {
            return null;
        }
    }

    private ObjectStorageAdapter requireStorage() {
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            throw new IntelligenceException(503, "对象存储未启用");
        }
        return storage;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
