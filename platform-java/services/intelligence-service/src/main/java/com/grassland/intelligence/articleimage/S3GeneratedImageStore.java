package com.grassland.intelligence.articleimage;

import com.grassland.storage.ObjectStorageAdapter;
import com.grassland.storage.StoredObject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 对象存储（MinIO/S3）实现：UUID key、30 分钟 TTL（按对象 lastModified 判过期）、访问不续期。
 * 多副本共享同一 bucket，解决本地卷无法跨实例的问题。生产路径（{@code object-storage.enabled=true}）。
 *
 * <p>外部契约与 {@link LocalGeneratedImageStore} 一致：store 返回裸 UUID（URL 用），find 按 UUID 还原字节。
 */
@Component
@ConditionalOnProperty(prefix = "object-storage", name = "enabled", havingValue = "true")
public class S3GeneratedImageStore implements GeneratedImageStore {

    private static final Pattern ID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final String CONTENT_TYPE = "image/png";

    private final ObjectStorageAdapter storage;
    private final String keyPrefix;
    private final Duration ttl;
    private final Clock clock;

    @Autowired
    public S3GeneratedImageStore(
            ObjectStorageAdapter storage,
            @Value("${article-images.generated.key-prefix:article-generated}") String keyPrefix,
            @Value("${article-images.generated.ttl-seconds:1800}") long ttlSeconds) {
        this(storage, keyPrefix, ttlSeconds, Clock.systemUTC());
    }

    S3GeneratedImageStore(ObjectStorageAdapter storage, String keyPrefix, long ttlSeconds, Clock clock) {
        this.storage = storage;
        // 去掉首尾斜杠，保证 key 形如 "prefix/{uuid}.png"，不出现前导/重复斜杠。
        String trimmed = keyPrefix == null ? "" : keyPrefix.strip().replace("/", "");
        this.keyPrefix = trimmed.isEmpty() ? "article-generated" : trimmed;
        this.ttl = Duration.ofSeconds(ttlSeconds);
        this.clock = clock;
    }

    @Override
    public Mono<GeneratedImageStore.StoredRef> store(String base64) {
        return Mono.fromCallable(() -> {
                    String id = UUID.randomUUID().toString();
                    storage.putObject(keyOf(id), Base64.getDecoder().decode(base64), CONTENT_TYPE);
                    return new GeneratedImageStore.StoredRef(id, keyOf(id));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<StoredImage> find(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> findBlocking(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty);
    }

    /** 巡检孤儿对象：列出前缀下全部对象，删除已过期者（best-effort，单页 ≤1000 key）。 */
    @Scheduled(fixedDelayString = "${article-images.generated.cleanup-interval-ms:300000}")
    public void cleanupExpired() {
        Mono.fromRunnable(this::cleanupBlocking).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private StoredImage findBlocking(String id) {
        String key = keyOf(id);
        StoredObject meta = storage.headObject(key).orElse(null);
        if (meta == null) {
            return null;
        }
        if (isExpired(meta.lastModified())) {
            storage.deleteObject(key); // 访问即清理，与本地实现一致
            return null;
        }
        try {
            return new StoredImage(storage.getObject(key));
        } catch (Exception error) {
            // head 与 get 之间被并发清理等极端竞态：视为不存在。
            return null;
        }
    }

    /** 实际巡检逻辑（同步）；{@link #cleanupExpired()} 仅做异步包装。包级可见便于单测。 */
    void cleanupBlocking() {
        try {
            for (StoredObject object : storage.listObjects(keyPrefix)) {
                if (isExpired(object.lastModified())) {
                    storage.deleteObject(object.key());
                }
            }
        } catch (Exception ignored) {
            // best effort cleanup; 存储暂不可用时本轮跳过
        }
    }

    private boolean isExpired(Instant lastModified) {
        return lastModified != null
                && Duration.between(lastModified, clock.instant()).compareTo(ttl) > 0;
    }

    private String keyOf(String id) {
        return keyPrefix + "/" + id + ".png";
    }
}
