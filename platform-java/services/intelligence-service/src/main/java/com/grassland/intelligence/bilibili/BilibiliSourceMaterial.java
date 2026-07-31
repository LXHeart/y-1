package com.grassland.intelligence.bilibili;

import java.util.Map;

/**
 * Bilibili 解析结果（移植 legacy {@code BilibiliSourceMaterial}）。progressive = 单 progressive 流地址；
 * dash = 独立视频/音频轨地址（需 FFmpeg mux）。
 *
 * <p>公共字段（{@code sourceUrl..requestHeaders}）由两个 record 各自承载；{@link #playbackMode()} 区分形态。
 * {@code videoId/author/title/coverUrl/durationSeconds} 可空（页面未必提供）；{@code requestHeaders} 为
 * 代理视频流时发给上游 bilivideo CDN 的 {referer,user-agent,origin}（非空，经 token 白名单清洗）。
 */
public sealed interface BilibiliSourceMaterial
        permits BilibiliSourceMaterial.Progressive, BilibiliSourceMaterial.Dash {

    String sourceUrl();

    String resolvedUrl();

    String videoId();

    String author();

    String title();

    String coverUrl();

    Long durationSeconds();

    Map<String, String> requestHeaders();

    String playbackMode();

    record Progressive(
            String sourceUrl,
            String resolvedUrl,
            String videoId,
            String author,
            String title,
            String coverUrl,
            Long durationSeconds,
            String playableVideoUrl,
            Map<String, String> requestHeaders) implements BilibiliSourceMaterial {
        @Override
        public String playbackMode() {
            return "progressive";
        }
    }

    record Dash(
            String sourceUrl,
            String resolvedUrl,
            String videoId,
            String author,
            String title,
            String coverUrl,
            Long durationSeconds,
            String videoTrackUrl,
            String audioTrackUrl,
            Map<String, String> requestHeaders) implements BilibiliSourceMaterial {
        @Override
        public String playbackMode() {
            return "dash";
        }
    }
}
