package com.grassland.intelligence.douyin;

import java.util.Map;

/**
 * 抖音解析结果（移植 legacy {@code douyin-resolve.service.ts} 的 {@code DouyinSourceMaterial} 必要子集）。
 *
 * <p>Java 侧只做 HTTP 阶段（desktop_http / mobile_http），不含 Playwright browser 阶段与登录态
 * （{@code usedSession} 恒 false、{@code fetchStage} 恒 {@code page_json}）；解析不出可播放地址时
 * {@code playableVideoUrl=null}，由 controller 整体回落 legacy（session/浏览器增强）。
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
