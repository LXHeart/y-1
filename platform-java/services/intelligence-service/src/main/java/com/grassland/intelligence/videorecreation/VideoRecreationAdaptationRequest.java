package com.grassland.intelligence.videorecreation;

import com.grassland.intelligence.ai.ContentPart;
import java.util.List;
import java.util.Map;

/** 已通过边界校验的视频内容改编命令；proxy URL 只用于入口校验，不进入 provider。 */
public record VideoRecreationAdaptationRequest(
        String platform,
        String proxyVideoUrl,
        Map<String, String> extractedContent,
        Map<String, String> userInstructions,
        List<ContentPart> referenceImages) {

    public VideoRecreationAdaptationRequest {
        platform = platform == null ? "" : platform;
        proxyVideoUrl = proxyVideoUrl == null ? "" : proxyVideoUrl;
        extractedContent = extractedContent == null ? Map.of() : Map.copyOf(extractedContent);
        userInstructions = userInstructions == null ? Map.of() : Map.copyOf(userInstructions);
        referenceImages = referenceImages == null ? List.of() : List.copyOf(referenceImages);
    }
}
