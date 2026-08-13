package com.grassland.intelligence.videorecreation;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.Map;
import java.util.UUID;

/** Shared task-binding fields accepted by video analysis and recreation endpoints. */
public record VideoRecreationTaskRequest(
        boolean taskMode, UUID contextSnapshotId, String targetPlatform) {

    public static VideoRecreationTaskRequest parse(Map<String, Object> body) {
        Object rawTaskMode = body == null ? null : body.get("taskMode");
        boolean taskMode = rawTaskMode != null && (Boolean.TRUE.equals(rawTaskMode)
                || "true".equalsIgnoreCase(String.valueOf(rawTaskMode)));
        if (rawTaskMode != null && !taskMode && !Boolean.FALSE.equals(rawTaskMode)
                && !"false".equalsIgnoreCase(String.valueOf(rawTaskMode))) {
            throw new IntelligenceException(400, "请求参数无效");
        }
        UUID snapshotId = uuid(body == null ? null : body.get("contextSnapshotId"));
        String platform = text(body == null ? null : body.get("targetPlatform"));
        if (taskMode && (snapshotId == null || platform == null)) {
            throw new IntelligenceException(400, "任务创作必须绑定目标平台和创作上下文快照");
        }
        if (!taskMode && snapshotId != null) {
            throw new IntelligenceException(400, "独立创作不能绑定任务上下文快照");
        }
        return new VideoRecreationTaskRequest(taskMode, snapshotId, platform);
    }

    private static String text(Object value) {
        if (value == null) return null;
        if (!(value instanceof String string)) throw new IntelligenceException(400, "请求参数无效");
        String result = string.trim();
        return result.isEmpty() ? null : result;
    }

    private static UUID uuid(Object value) {
        String text = text(value);
        if (text == null) return null;
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException error) {
            throw new IntelligenceException(400, "创作上下文快照标识无效");
        }
    }
}
