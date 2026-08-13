package com.grassland.intelligence.videorecreation;

import com.grassland.intelligence.ai.ContentPart;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 已通过边界校验的视频内容改编命令；proxy URL 只用于入口校验，不进入 provider。 */
public record VideoRecreationAdaptationRequest(
        String platform,
        String proxyVideoUrl,
        Map<String, String> extractedContent,
        Map<String, String> userInstructions,
        List<ContentPart> referenceImages,
        String targetPlatform,
        boolean taskMode,
        UUID contextSnapshotId) {

    public VideoRecreationAdaptationRequest {
        platform = platform == null ? "" : platform;
        proxyVideoUrl = proxyVideoUrl == null ? "" : proxyVideoUrl;
        extractedContent = extractedContent == null ? Map.of() : Map.copyOf(extractedContent);
        userInstructions = userInstructions == null ? Map.of() : Map.copyOf(userInstructions);
        referenceImages = referenceImages == null ? List.of() : List.copyOf(referenceImages);
        targetPlatform = targetPlatform == null || targetPlatform.isBlank() ? null : targetPlatform.trim();
        if (taskMode && (targetPlatform == null || contextSnapshotId == null)) {
            throw new IllegalArgumentException("任务创作必须绑定目标平台和创作上下文快照");
        }
        if (!taskMode && contextSnapshotId != null) {
            throw new IllegalArgumentException("独立创作不能绑定任务上下文快照");
        }
        if (taskMode && !referenceImages.isEmpty()) {
            throw new IllegalArgumentException("任务复刻只能使用创作开始时冻结的授权素材");
        }
    }
}
