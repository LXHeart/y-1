package com.grassland.intelligence.creationlineage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One immutable prompt-to-result lineage row. */
public record CreationGeneration(
        UUID id,
        String ownerAccountId,
        String organizationId,
        Kind kind,
        Mode mode,
        UUID contextSnapshotId,
        UUID aiRunId,
        Resolution resolution,
        String provider,
        String model,
        Integer platformModelVersion,
        String upstreamRunId,
        String promptText,
        Map<String, Object> inputSummary,
        List<UUID> inputMediaIds,
        Map<String, Object> result,
        List<UUID> resultMediaIds,
        Instant createdAt) {

    public enum Kind {
        VIDEO_ADAPTATION("video_adaptation"),
        ASSET_IMAGE("asset_image"),
        SCENE_IMAGE("scene_image");

        private final String db;

        Kind(String db) { this.db = db; }
        public String db() { return db; }

        public static Kind fromDb(String value) {
            for (Kind kind : values()) if (kind.db.equals(value)) return kind;
            throw new IllegalArgumentException("Unknown creation generation kind: " + value);
        }
    }

    public enum Mode {
        INDEPENDENT("independent"), TASK("task");
        private final String db;
        Mode(String db) { this.db = db; }
        public String db() { return db; }
        public static Mode fromDb(String value) {
            for (Mode mode : values()) if (mode.db.equals(value)) return mode;
            throw new IllegalArgumentException("Unknown creation generation mode: " + value);
        }
    }

    public enum Resolution {
        PLATFORM("platform"), BYOK("byok");
        private final String db;
        Resolution(String db) { this.db = db; }
        public String db() { return db; }
        public static Resolution fromDb(String value) {
            for (Resolution resolution : values()) if (resolution.db.equals(value)) return resolution;
            throw new IllegalArgumentException("Unknown creation generation resolution: " + value);
        }
    }
}
