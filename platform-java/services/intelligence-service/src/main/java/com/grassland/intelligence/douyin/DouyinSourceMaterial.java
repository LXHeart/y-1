package com.grassland.intelligence.douyin;

import java.util.Map;

/**
 * 抖音解析结果（移植 legacy {@code DouyinSourceMaterial}）。
 *
 * <p>从抖音分享文本解析出的视频素材信息。包含视频ID、作者、标题、封面、时长等元数据，
 * 以及可播放的视频URL和请求所需的HTTP头。
 */
public record DouyinSourceMaterial(
        String sourceUrl,
        String resolvedUrl,
        String videoId,
        String author,
        String title,
        String coverUrl,
        Long durationSeconds,
        String playableVideoUrl,
        Map<String, String> requestHeaders,
        boolean usedSession) {

    /**
     * 解析结果的播放模式（对齐 legacy）。
     * 抖音当前只有 progressive 流，暂无 DASH。
     */
    public String playbackMode() {
        return "progressive";
    }
}
