package com.grassland.intelligence.douyin;

import java.util.Map;

/**
 * 抖音代理目标（对齐 legacy {@code DouyinMediaTarget}）。
 *
 * <p>用于 token 编解码，包含可播放视频URL、请求头、文件名和时长等信息。
 */
public record DouyinMediaTarget(
        String kind,
        String playableVideoUrl,
        Map<String, String> requestHeaders,
        String filename,
        Long durationSeconds) {

    /**
     * 创建 progressive 类型的代理目标。
     */
    public static DouyinMediaTarget progressive(
            String playableVideoUrl,
            Map<String, String> requestHeaders,
            String filename,
            Long durationSeconds) {
        return new DouyinMediaTarget("progressive", playableVideoUrl, requestHeaders, filename, durationSeconds);
    }
}
