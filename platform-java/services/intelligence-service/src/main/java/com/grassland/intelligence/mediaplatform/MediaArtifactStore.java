package com.grassland.intelligence.mediaplatform;

import com.grassland.intelligence.security.IntelligenceException;
import jakarta.annotation.PostConstruct;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MediaArtifactStore {
    public record Artifact(Path path, String filename, String contentType, Instant expiresAt) {}
    private static final String ID_PATTERN = "[0-9a-fA-F-]{36}";

    private final Path root;
    private final Duration ttl;
    private final Map<String, Artifact> artifacts = new ConcurrentHashMap<>();

    public MediaArtifactStore(Environment environment) {
        this.root = Path.of(environment.getProperty("media.platform.temp-dir", "/tmp/grassland-media")).toAbsolutePath().normalize();
        this.ttl = Duration.ofSeconds(environment.getProperty("media.platform.artifact-ttl-seconds", Long.class, 900L));
    }

    @PostConstruct
    void initialize() throws Exception { Files.createDirectories(root); }

    public Path createPath(String suffix) {
        return root.resolve(UUID.randomUUID() + suffix).normalize();
    }

    public String register(Path path, String filename, String contentType) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw new IllegalArgumentException("artifact must live under media.temp-dir");
        String id = UUID.randomUUID().toString();
        Path sharedPath = root.resolve(id);
        try {
            Files.move(normalized, sharedPath);
            writeMetadata(id, new Artifact(sharedPath, filename, contentType, Instant.now().plus(ttl)));
        } catch (Exception error) {
            try { Files.deleteIfExists(sharedPath); } catch (Exception ignored) {}
            throw new IntelligenceException(500, "无法注册媒体临时文件");
        }
        Artifact artifact = readMetadata(id, sharedPath);
        artifacts.put(id, artifact);
        return id;
    }

    public Artifact require(String id) {
        Artifact artifact = artifacts.get(id);
        if (artifact == null && validId(id)) {
            Path sharedPath = root.resolve(id).normalize();
            if (sharedPath.startsWith(root) && Files.isRegularFile(sharedPath)) {
                artifact = readMetadata(id, sharedPath);
                artifacts.put(id, artifact);
            }
        }
        if (artifact == null || artifact.expiresAt().isBefore(Instant.now()) || !Files.isRegularFile(artifact.path())) {
            remove(id);
            throw new IntelligenceException(404, "媒体文件不存在或已过期");
        }
        return artifact;
    }

    public void remove(String id) {
        Artifact artifact = artifacts.remove(id);
        Path path = artifact == null ? root.resolve(id == null ? "" : id).normalize() : artifact.path();
        if (path.startsWith(root) && !path.equals(root)) try { Files.deleteIfExists(path); } catch (Exception ignored) {}
        Path metadata = metadataPath(id);
        if (metadata != null) try { Files.deleteIfExists(metadata); } catch (Exception ignored) {}
    }

    @Scheduled(fixedDelayString = "${media.platform.cleanup-interval-ms:60000}")
    void cleanup() {
        Instant now = Instant.now();
        artifacts.forEach((id, artifact) -> { if (artifact.expiresAt().isBefore(now)) remove(id); });
        try (var files = Files.list(root)) {
            files.filter(Files::isRegularFile).forEach(path -> cleanupPath(path, now));
        } catch (Exception ignored) {}
    }

    private void cleanupPath(Path path, Instant now) {
        String name = path.getFileName().toString();
        if (validId(name)) {
            Artifact artifact = readMetadata(name, path);
            if (artifact.expiresAt().isBefore(now)) remove(name);
            return;
        }
        try {
            if (Files.getLastModifiedTime(path).toInstant().plus(ttl).isBefore(now)) {
                Files.deleteIfExists(path);
            }
        } catch (Exception ignored) {}
    }

    private void writeMetadata(String id, Artifact artifact) throws Exception {
        Properties values = new Properties();
        values.setProperty("filename", artifact.filename());
        values.setProperty("contentType", artifact.contentType());
        values.setProperty("expiresAtEpochMilli", String.valueOf(artifact.expiresAt().toEpochMilli()));
        Path destination = metadataPath(id);
        Path temporary = root.resolve(id + ".meta.tmp");
        try (var output = Files.newOutputStream(temporary)) { values.store(output, null); }
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private Artifact readMetadata(String id, Path path) {
        Path metadata = metadataPath(id);
        if (metadata != null && Files.isRegularFile(metadata)) {
            Properties values = new Properties();
            try (var input = Files.newInputStream(metadata)) {
                values.load(input);
                String filename = values.getProperty("filename");
                String contentType = values.getProperty("contentType");
                long expiresAt = Long.parseLong(values.getProperty("expiresAtEpochMilli"));
                if (filename != null && !filename.isBlank() && contentType != null && !contentType.isBlank()) {
                    return new Artifact(path, filename, contentType, Instant.ofEpochMilli(expiresAt));
                }
            } catch (Exception ignored) {}
        }
        try {
            return new Artifact(path, "analysis-media.mp4", "video/mp4",
                    Files.getLastModifiedTime(path).toInstant().plus(ttl));
        } catch (Exception error) {
            return new Artifact(path, "analysis-media.mp4", "video/mp4", Instant.EPOCH);
        }
    }

    private Path metadataPath(String id) {
        if (!validId(id)) return null;
        Path path = root.resolve(id + ".meta").normalize();
        return path.startsWith(root) ? path : null;
    }

    private static boolean validId(String id) { return id != null && id.matches(ID_PATTERN); }
}
