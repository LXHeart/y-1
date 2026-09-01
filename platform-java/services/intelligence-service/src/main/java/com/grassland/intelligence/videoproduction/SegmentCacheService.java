package com.grassland.intelligence.videoproduction;

import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.StoredObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 段缓存（任务书 #65 卡6）：合成时逐镜段落存对象存储 {@code segments/{taskId}/{shotId}.mp4}，
 * 指纹（prompt+anchorMediaId+takeId+时长+分辨率+配音）写 sidecar {@code .fp}。
 * 重合成时未变镜头直接复用段文件，只重 normalize 变更镜头——180 秒级任务的合成耗时
 * 从 O(全镜) 降到 O(1 镜)。
 *
 * <p>advisory 姿态：任何存储异常都静默降级为「缓存未命中」（重渲染），绝不阻断合成；
 * 写失败只记日志。段文件生命周期随 #64 卡10 清理策略（成片 N 天后回收）。
 */
@Service
public class SegmentCacheService {

    private static final Logger log = LoggerFactory.getLogger(SegmentCacheService.class);
    private static final String SEGMENT_PREFIX = "segments/";
    private static final String SEGMENT_MIME = "video/mp4";

    private final ObjectProvider<ObjectStorageAdapter> storageProvider;

    public SegmentCacheService(ObjectProvider<ObjectStorageAdapter> storageProvider) {
        this.storageProvider = storageProvider;
    }

    /** 单镜段计划：命中返回缓存字节，未命中返回 null（调用方渲染）。 */
    public record SegmentPlan(String fingerprint, byte[] cachedBytes) {
        public boolean hit() {
            return cachedBytes != null;
        }
    }

    static String segmentKey(UUID taskId, UUID shotId) {
        return SEGMENT_PREFIX + taskId + "/" + shotId + ".mp4";
    }

    static String fingerprintKey(UUID taskId, UUID shotId) {
        return SEGMENT_PREFIX + taskId + "/" + shotId + ".fp";
    }

    /**
     * 段内容指纹：卡面锚定 prompt+anchorMediaId+takeId；纳入全部影响段字节的输入
     * （plannedSeconds、resolution、配音媒体）——任一变化即视为段失效。
     */
    static String fingerprintOf(VideoShot shot, VideoShotTake take, VideoShotAudio audio, String resolution) {
        String canonical = String.join("|",
                nullSafe(shot.prompt()),
                nullSafe(shot.anchorMediaId() == null ? null : shot.anchorMediaId().toString()),
                String.valueOf(shot.plannedSeconds()),
                nullSafe(resolution),
                take == null ? "-" : nullSafe(take.id().toString()),
                audio == null || audio.mediaId() == null ? "-" : nullSafe(audio.mediaId().toString()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    /** 命中则返回段字节；未命中/指纹变化/存储异常 → empty（降级重渲染）。 */
    public Mono<SegmentPlan> plan(UUID taskId, UUID shotId, String fingerprint) {
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return Mono.just(new SegmentPlan(fingerprint, null));
        }
        return Mono.fromCallable(() -> {
            try {
                String cached = new String(storage.getObject(fingerprintKey(taskId, shotId)),
                        StandardCharsets.UTF_8);
                if (!fingerprint.equals(cached.trim())) {
                    return new SegmentPlan(fingerprint, null);
                }
                return new SegmentPlan(fingerprint, storage.getObject(segmentKey(taskId, shotId)));
            } catch (RuntimeException error) {
                // 对象不存在/读失败 = 未命中
                return new SegmentPlan(fingerprint, null);
            }
        }).subscribeOn(Schedulers.boundedElastic()).onErrorReturn(new SegmentPlan(fingerprint, null));
    }

    /** 段落写缓存（指纹 + 段文件）；失败仅记日志。调用方须在 boundedElastic 上执行（阻塞 IO）。 */
    public void store(UUID taskId, UUID shotId, SegmentPlan plan, byte[] segment) {
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return;
        }
        try {
            storage.putObject(fingerprintKey(taskId, shotId),
                    plan.fingerprint().getBytes(StandardCharsets.UTF_8), "text/plain");
            storage.putObject(segmentKey(taskId, shotId), segment, SEGMENT_MIME);
        } catch (RuntimeException error) {
            log.warn("segment cache store failed taskId={} shotId={}", taskId, shotId, error);
        }
    }

    /** 卡10 清理：任务段目录整体回收（对象删除幂等，失败记日志下一轮重试）。 */
    public Mono<Void> deleteSegments(UUID taskId) {
        ObjectStorageAdapter storage = storageProvider.getIfAvailable();
        if (storage == null) {
            return Mono.empty();
        }
        String prefix = SEGMENT_PREFIX + taskId + "/";
        return Mono.fromCallable(() -> storage.listObjects(prefix))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .map(StoredObject::key)
                .concatMap(key -> Mono.fromRunnable(() -> {
                    try {
                        storage.deleteObject(key);
                    } catch (RuntimeException error) {
                        log.warn("segment object delete failed key={}", key, error);
                    }
                }).subscribeOn(Schedulers.boundedElastic()).then())
                .then()
                .onErrorResume(error -> {
                    log.warn("segment list failed taskId={}", taskId, error);
                    return Mono.empty();
                });
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
