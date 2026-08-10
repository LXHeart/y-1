package com.grassland.intelligence.bilibili;

import java.util.Map;

/**
 * Bilibili 代理目标（移植 legacy {@code BilibiliMediaTarget}）。progressive = 单 progressive 流；
 * dash = 独立视频/音频轨（由 Java 下载并调用 FFmpeg mux）。
 *
 * <p>{@code requestHeaders/filename/durationSeconds} 可空（来自 token payload，受信字段经白名单清洗）。
 */
public sealed interface BilibiliMediaTarget permits BilibiliMediaTarget.Progressive, BilibiliMediaTarget.Dash {

    String kind();

    Map<String, String> requestHeaders();

    String filename();

    Long durationSeconds();

    record Progressive(
            String playableVideoUrl,
            Map<String, String> requestHeaders,
            String filename,
            Long durationSeconds) implements BilibiliMediaTarget {
        @Override
        public String kind() {
            return "progressive";
        }
    }

    record Dash(
            String videoTrackUrl,
            String audioTrackUrl,
            Map<String, String> requestHeaders,
            String filename,
            Long durationSeconds) implements BilibiliMediaTarget {
        @Override
        public String kind() {
            return "dash";
        }
    }
}
