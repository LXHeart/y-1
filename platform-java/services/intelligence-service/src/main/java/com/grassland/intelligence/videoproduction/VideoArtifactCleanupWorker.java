package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.media.MediaReference;
import com.grassland.intelligence.media.MediaReferenceRepository;
import com.grassland.storage.ObjectStorageAdapter;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 中间产物清理（任务书 #64 卡10）：compose 成功 N 天（ai.video-production.artifact-retention-days，
 * 默认 7）后删除 take/audio 对象与 media 行。成片与 SRT（video_master）永久保留。
 *
 * <p>幂等标记：清完将 take/audio 行的 media_id 置空——重放扫描直接跳过（无软删列，行本身就是
 * 「已处理」凭证）。对象删除失败记日志跳过，下一轮重试。
 */
@Component
public class VideoArtifactCleanupWorker {

    private static final Logger log = LoggerFactory.getLogger(VideoArtifactCleanupWorker.class);
    private static final int BATCH = 50;

    private final VideoProductionTaskRepository tasks;
    private final VideoShotTakeRepository takes;
    private final VideoShotAudioRepository audios;
    private final MediaReferenceRepository mediaRefs;
    private final ObjectProvider<ObjectStorageAdapter> storageProvider;
    private final VideoProductionPipelineProperties pipeline;
    private final SegmentCacheService segments;

    public VideoArtifactCleanupWorker(VideoProductionTaskRepository tasks,
            VideoShotTakeRepository takes, VideoShotAudioRepository audios,
            MediaReferenceRepository mediaRefs, ObjectProvider<ObjectStorageAdapter> storageProvider,
            VideoProductionPipelineProperties pipeline, SegmentCacheService segments) {
        this.tasks = tasks;
        this.takes = takes;
        this.audios = audios;
        this.mediaRefs = mediaRefs;
        this.storageProvider = storageProvider;
        this.pipeline = pipeline;
        this.segments = segments;
    }

    @Scheduled(fixedDelayString = "${ai.video-production.artifact-cleanup-interval-ms:3600000}")
    public void dispatch() {
        cleanupOnce().subscribe();
    }

    public Mono<Void> cleanupOnce() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(
                Math.max(1, pipeline.getArtifactRetentionDays()));
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return Mono.empty();
        }
        return tasks.findSucceededBefore(cutoff, BATCH)
                .concatMap(task -> cleanStoryboard(task, storage)
                        .onErrorResume(error -> {
                            log.warn("artifact cleanup failed taskId={}", task.id(), error);
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> cleanStoryboard(VideoProductionTask task, ObjectStorageAdapter storage) {
        return Flux.concat(
                        takes.findByStoryboard(task.storyboardId())
                                .filter(take -> take.mediaId() != null)
                                .map(take -> new MediaRef(take.id(), take.mediaId(), true)),
                        audios.findByStoryboard(task.storyboardId())
                                .filter(audio -> audio.mediaId() != null)
                                .map(audio -> new MediaRef(audio.id(), audio.mediaId(), false)))
                .concatMap(artifact -> deleteArtifact(artifact.mediaId(), storage)
                        .then(clearMediaId(artifact)))
                // #65 卡6：段缓存目录同窗口回收（重合成窗口 = 保留期）
                .then(segments.deleteSegments(task.id()))
                .then()
                .doOnSuccess(ignored -> log.info(
                        "video artifacts cleaned metric=artifacts_cleaned taskId={} storyboardId={}",
                        task.id(), task.storyboardId()));
    }

    private Mono<Void> deleteArtifact(UUID mediaId, ObjectStorageAdapter storage) {
        return mediaRefs.findById(mediaId)
                .flatMap(reference -> Mono.fromRunnable(() -> {
                            try {
                                storage.deleteObject(reference.objectKey());
                            } catch (RuntimeException error) {
                                log.warn("artifact object delete failed mediaId={}", mediaId, error);
                            }
                        }).subscribeOn(Schedulers.boundedElastic())
                        .<MediaReference>then(Mono.fromCallable(
                                () -> mediaRefs.findById(mediaId).block(Duration.ofSeconds(5))))
                        .then())
                .then();
    }

    private Mono<Void> clearMediaId(MediaRef artifact) {
        return artifact.take()
                ? takes.clearMedia(artifact.rowId()).then()
                : audios.clearMedia(artifact.rowId()).then();
    }

    private record MediaRef(UUID rowId, UUID mediaId, boolean take) {}
}
