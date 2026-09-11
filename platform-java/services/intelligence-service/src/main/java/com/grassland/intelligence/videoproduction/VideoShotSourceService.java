package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.intelligence.media.MediaStatus;
import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.storage.ObjectStorageAdapter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 每镜制作来源保存（任务书 #100 C100-11 / API-10 / §6.5）。
 *
 * <p>校验全在扣费前：归属/可读状态（跨账号或已删 400）、元数据大小复核、下载后实测
 * （ffprobe 时长与音轨）。批量全部校验通过才进编辑闸事务（draft-only + expectedEditVersion
 * CAS + 实际变化才提升 edit_version）；越界/无音轨 source 模式等非法输入零写入。
 * 删除镜头同步清来源行（{@link #deleteSourcesForShots} 由编辑闸删镜路径调用）。
 */
@org.springframework.stereotype.Service
public class VideoShotSourceService {

    private static final Logger log = LoggerFactory.getLogger(VideoShotSourceService.class);

    private final MediaReferenceRepository mediaRefs;
    private final VideoShotRepository shots;
    private final VideoShotSourceRepository sources;
    private final VideoStoryboardEditService editGate;
    private final org.springframework.beans.factory.ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final VideoMediaProbe probe;

    public VideoShotSourceService(MediaReferenceRepository mediaRefs, VideoShotRepository shots,
            VideoShotSourceRepository sources, VideoStoryboardEditService editGate,
            org.springframework.beans.factory.ObjectProvider<ObjectStorageAdapter> storageProvider,
            VideoMediaProbe probe) {
        this.mediaRefs = mediaRefs;
        this.shots = shots;
        this.sources = sources;
        this.editGate = editGate;
        this.storageProvider = storageProvider;
        this.probe = probe;
    }

    /** API-10 载荷项：{shotId, source}；source 形状见 §6.1 ShotMediaSource。 */
    public record SourceItem(UUID shotId, Map<String, Object> source) {
    }

    public record SaveResult(UUID storyboardId, long editVersion, List<Map<String, Object>> sources) {
    }

    public Mono<SaveResult> saveSources(String accountId, UUID storyboardId, Long expectedEditVersion,
            List<SourceItem> items) {
        if (items == null || items.isEmpty() || items.size() > 30) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "来源项须为 1～30 条"));
        }
        Set<UUID> seen = new HashSet<>();
        for (SourceItem item : items) {
            if (item.shotId() == null || !seen.add(item.shotId())) {
                return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "镜头必须存在且不重复"));
            }
        }
        return shots.findByStoryboard(storyboardId).collectList()
                .flatMap(shotRows -> {
                    Map<UUID, VideoShot> byId = new LinkedHashMap<>();
                    for (VideoShot shot : shotRows) {
                        byId.put(shot.id(), shot);
                    }
                    for (SourceItem item : items) {
                        if (!byId.containsKey(item.shotId())) {
                            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
                                    "来源引用了不属于该分镜的镜头"));
                        }
                    }
                    // 逐条校验（读库 + 实测）全绿后再进编辑闸（闸内不再做 IO 校验）
                    List<Mono<VideoShotSource>> validated = new ArrayList<>();
                    for (SourceItem item : items) {
                        validated.add(validateItem(accountId, storyboardId, byId.get(item.shotId()),
                                item.source()));
                    }
                    return Flux.concat(validated).collectList()
                            .flatMap(rows -> applyInEditGate(accountId, storyboardId, expectedEditVersion,
                                    shotRows, rows));
                });
    }

    /** 校验单条来源（结构 + 归属 + 实测），返回可落库行。 */
    private Mono<VideoShotSource> validateItem(String accountId, UUID storyboardId, VideoShot shot,
            Map<String, Object> source) {
        if (source == null || !(source.get("kind") instanceof String kind)) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "source.kind 必填"));
        }
        if (VideoShotSource.KIND_GENERATED.equals(kind)) {
            if (source.size() != 1) {
                return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
                        "generated 来源不允许携带其他字段"));
            }
            return Mono.just(new VideoShotSource(shot.id(), storyboardId, VideoShotSource.KIND_GENERATED,
                    null, null, null, VideoShotSource.AUDIO_NARRATION, null, null));
        }
        if (!VideoShotSource.KIND_OWN_MEDIA.equals(kind)) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "未知 source.kind"));
        }
        String mediaIdText = source.get("mediaId") instanceof String value ? value : null;
        if (mediaIdText == null || mediaIdText.isBlank()) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "own-media 必须携带 mediaId"));
        }
        UUID mediaId;
        try {
            mediaId = UUID.fromString(mediaIdText);
        } catch (Exception e) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "mediaId 必须是 UUID"));
        }
        Long trimStart = asLongOrNull(source.get("trimStartMs"));
        Long trimEnd = asLongOrNull(source.get("trimEndMs"));
        String audioMode = source.get("audioMode") instanceof String mode ? mode : null;
        if (audioMode == null || !Set.of(VideoShotSource.AUDIO_SOURCE, VideoShotSource.AUDIO_NARRATION,
                VideoShotSource.AUDIO_MUTE).contains(audioMode)) {
            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT", "audioMode 非法"));
        }
        return mediaRefs.findById(mediaId)
                .filter(ref -> accountId.equals(ref.ownerAccountId()) && ref.deletedAt() == null
                        && MediaStatus.ACTIVE.equals(ref.status()))
                .switchIfEmpty(Mono.error(new IntelligenceException(400, "CANVAS_MEDIA_UNAVAILABLE",
                        "素材不可用或无权使用")))
                .flatMap(ref -> {
                    if (ref.mimeType() == null || !(ref.mimeType().startsWith("video/")
                            || ref.mimeType().startsWith("image/"))) {
                        return Mono.error(new IntelligenceException(400, "CANVAS_MEDIA_UNAVAILABLE",
                                "素材类型必须是图片或视频"));
                    }
                    if (ref.sizeBytes() > VideoShotSource.MAX_SOURCE_BYTES) {
                        return Mono.error(new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED",
                                "素材超过 200MiB 上限"));
                    }
                    boolean isImage = ref.mimeType().startsWith("image/");
                    if (isImage) {
                        if (trimStart != null || trimEnd != null) {
                            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
                                    "图片来源不允许裁剪区间"));
                        }
                        if (VideoShotSource.AUDIO_SOURCE.equals(audioMode)) {
                            return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
                                    "图片不支持 source 音轨模式"));
                        }
                        return Mono.just(row(shot, storyboardId, mediaId, null, null, audioMode));
                    }
                    // 视频：读对象 → ffprobe 实测（工作线程边界，不阻塞事件循环）
                    ObjectStorageAdapter storage = storageProvider.getIfAvailable();
                    if (storage == null) {
                        return Mono.error(new IntelligenceException(503, "素材读取暂不可用"));
                    }
                    return Mono.fromCallable(() -> {
                                byte[] content = storage.getObject(ref.objectKey());
                                if (content == null || content.length > VideoShotSource.MAX_SOURCE_BYTES) {
                                    throw new IntelligenceException(400, "CANVAS_LIMIT_EXCEEDED",
                                            "素材实际大小超过 200MiB 上限");
                                }
                                String extension = ref.mimeType().endsWith("/mp4") ? "mp4" : "bin";
                                return probe.probe(content, extension);
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(probed -> {
                                if (trimStart == null || trimEnd == null) {
                                    return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
                                            "视频来源必须携带裁剪区间"));
                                }
                                if (trimStart < 0 || trimStart >= trimEnd || trimEnd > probed.durationMs()) {
                                    return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
                                            "裁剪区间必须满足 0 ≤ start < end ≤ 实测时长"));
                                }
                                if (trimEnd - trimStart != shot.plannedSeconds() * 1000L) {
                                    return Mono.error(new IntelligenceException(400, "CANVAS_INVALID_INPUT",
                                            "截取长度必须等于镜头时长（" + shot.plannedSeconds() + "s）"));
                                }
                                if (VideoShotSource.AUDIO_SOURCE.equals(audioMode) && !probed.hasAudio()) {
                                    return Mono.error(new IntelligenceException(400, "CANVAS_MEDIA_UNAVAILABLE",
                                            "素材没有音轨，不能使用 source 音轨模式"));
                                }
                                return Mono.just(row(shot, storyboardId, mediaId, trimStart, trimEnd, audioMode));
                            });
                });
    }

    private Mono<SaveResult> applyInEditGate(String accountId, UUID storyboardId, Long expectedEditVersion,
            List<VideoShot> shotRows, List<VideoShotSource> rows) {
        return editGate.inEditLockWithValue(accountId, storyboardId, expectedEditVersion,
                "已制作内容通过独立方案修改", storyboard -> {
                    java.util.Map<UUID, VideoShotSource> existing = new java.util.HashMap<>();
                    return sources.findByStoryboard(storyboardId)
                            .collectMap(VideoShotSource::shotId)
                            .flatMap(current -> {
                                boolean changed = false;
                                List<VideoShotSource> result = new ArrayList<>();
                                for (VideoShotSource next : rows) {
                                    VideoShotSource previous = current.get(next.shotId());
                                    if (!sameSource(previous, next)) {
                                        changed = true;
                                    }
                                    result.add(next);
                                }
                                if (!changed) {
                                    return Mono.just(new VideoStoryboardEditService.EditWritePayload<>(
                                            List.copyOf(result), VideoStoryboardEditService.EditWrite.UNCHANGED));
                                }
                                List<String> shotIds = rows.stream().map(item -> item.shotId().toString()).toList();
                                return Flux.fromIterable(rows)
                                        .concatMap(sources::upsert)
                                        .then(Mono.just(new VideoStoryboardEditService.EditWritePayload<>(
                                                List.copyOf(result),
                                                new VideoStoryboardEditService.EditWrite(true, shotIds))));
                            });
                })
                .map(outcome -> new SaveResult(storyboardId, outcome.outcome().editVersion(),
                        outcome.value().stream().map(VideoShotSource::toView).toList()));
    }

    /** 删镜级联（编辑闸事务内调用）：清来源行，返回删除数。 */
    public Mono<Long> deleteSourcesForShots(java.util.Collection<UUID> shotIds) {
        return sources.deleteByShotIds(shotIds);
    }

    /** 读侧：分镜权威来源视图（shotId → view；缺行由调用方补 generated 默认）。 */
    public Mono<Map<UUID, Map<String, Object>>> sourcesByShot(UUID storyboardId) {
        return sources.findByStoryboard(storyboardId)
                .collectMap(VideoShotSource::shotId, VideoShotSource::toView);
    }

    private static boolean sameSource(VideoShotSource previous, VideoShotSource next) {
        if (previous == null) {
            return false;
        }
        return previous.sourceKind().equals(next.sourceKind())
                && java.util.Objects.equals(previous.mediaId(), next.mediaId())
                && java.util.Objects.equals(previous.trimStartMs(), next.trimStartMs())
                && java.util.Objects.equals(previous.trimEndMs(), next.trimEndMs())
                && previous.audioMode().equals(next.audioMode());
    }

    private static VideoShotSource row(VideoShot shot, UUID storyboardId, UUID mediaId, Long trimStart,
            Long trimEnd, String audioMode) {
        return new VideoShotSource(shot.id(), storyboardId, VideoShotSource.KIND_OWN_MEDIA, mediaId,
                trimStart, trimEnd, audioMode, null, null);
    }

    private static Long asLongOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer number) {
            return number.longValue();
        }
        if (value instanceof Long number) {
            return number;
        }
        throw new IntelligenceException(400, "CANVAS_INVALID_INPUT", "裁剪区间必须是整数毫秒");
    }
}
