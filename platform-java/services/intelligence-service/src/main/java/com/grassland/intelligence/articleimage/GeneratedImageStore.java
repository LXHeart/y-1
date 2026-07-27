package com.grassland.intelligence.articleimage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** 兼容 legacy 的本地 PNG 暂存：UUID 文件名、30 分钟 TTL、访问不续期。 */
@Component
public class GeneratedImageStore {

    private static final Pattern ID = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final Path directory;
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public GeneratedImageStore(
            @Value("${article-images.generated.directory:/tmp/grassland-intelligence/generated-images}") Path directory,
            @Value("${article-images.generated.ttl-seconds:1800}") long ttlSeconds) {
        this(directory, Clock.systemUTC(), Duration.ofSeconds(ttlSeconds));
    }

    GeneratedImageStore(Path directory, Clock clock, Duration ttl) {
        this.directory = directory.toAbsolutePath().normalize();
        this.clock = clock;
        this.ttl = ttl;
    }

    public Mono<String> store(String base64) {
        return Mono.fromCallable(() -> {
                    Files.createDirectories(directory);
                    String id = UUID.randomUUID().toString();
                    Path path = directory.resolve(id + ".png");
                    Files.write(path, Base64.getDecoder().decode(base64));
                    Files.setLastModifiedTime(path, FileTime.from(clock.instant()));
                    return id;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<StoredImage> find(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            return Mono.empty();
        }
        return Mono.fromCallable(() -> findBlocking(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty);
    }

    @Scheduled(fixedDelayString = "${article-images.generated.cleanup-interval-ms:300000}")
    public void cleanupExpired() {
        Mono.fromRunnable(this::cleanupBlocking).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private StoredImage findBlocking(String id) {
        Path path = directory.resolve(id + ".png").normalize();
        if (!path.startsWith(directory) || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            if (Duration.between(modified, clock.instant()).compareTo(ttl) > 0) {
                Files.deleteIfExists(path);
                return null;
            }
            return new StoredImage(path);
        } catch (Exception error) {
            return null;
        }
    }

    private void cleanupBlocking() {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".png"))
                    .forEach(path -> {
                        try {
                            Instant modified = Files.getLastModifiedTime(path).toInstant();
                            if (Duration.between(modified, clock.instant()).compareTo(ttl) > 0) {
                                Files.deleteIfExists(path);
                            }
                        } catch (Exception ignored) {
                            // best effort cleanup, matching legacy behavior
                        }
                    });
        } catch (Exception ignored) {
            // directory may not exist yet
        }
    }

    public record StoredImage(Path path) {}
}
