package com.grassland.intelligence.douyin;

import java.util.Map;

/**
 * 抖音解析结果（移植 legacy {@code douyin-resolve.service.ts} 的 {@code DouyinSourceMaterial} 必要子集）。
 *
 * <p>HTTP 解析结果和 Java Playwright 浏览器增强结果共用此结构；浏览器路径可设置
 * {@code usedSession=true} 并记录实际 {@code fetchStage}。
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
        boolean usedSession,
        String fetchStage,
        boolean challengePage) {

    /** 对齐 legacy {@code canResolveDouyinVideoAsset}：非挑战页且有可播放地址。 */
    public boolean assetResolvable() {
        return !challengePage && playableVideoUrl != null && !playableVideoUrl.isBlank();
    }
}
