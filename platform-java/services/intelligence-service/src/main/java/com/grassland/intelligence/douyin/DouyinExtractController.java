package com.grassland.intelligence.douyin;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 抖音视频提取（草场 GL-P3-MEDIA-001）：{@code POST /api/douyin/extract-video}。
 *
 * <p>校验分享文本含允许 page host 的 https URL（文案对齐 legacy schema）→ {@link DouyinResolveService}
 * HTTP 阶段解析 → 解析出可播放地址时生成下载文件名、签发 {@link DouyinMediaTarget} token、按 legacy
 * {@code ExtractedDouyinVideoPayload} 契约返回（含 {@code downloadAudioUrl}/{@code usedSession}/{@code fetchStage}，
 * 前端 {@code useDouyinParse} 严格校验这三个字段）。
 *
 * <p>HTTP 阶段解析不出可播放地址时，由 Java Playwright 使用持久登录态进行浏览器增强；阻塞浏览器工作
 * 在 bounded-elastic 调度器执行，不占用 WebFlux 事件线程。
 *
 * <p>无 auth/credits（公开提取）。{@code proxyVideoUrl}/{@code downloadVideoUrl}/{@code downloadAudioUrl}
 * 沿用既有相对路径契约；视频代理、视频下载和 Java FFmpeg 音频提取使用同一 token。
 */
@RestController
public class DouyinExtractController {

    private final DouyinResolveService resolveService;
    private final DouyinProxyToken tokenCodec;
    private final DouyinFetchProperties fetchProps;
    private final DouyinBrowserService browser;

    public DouyinExtractController(
            DouyinResolveService resolveService,
            DouyinProxyToken tokenCodec,
            DouyinFetchProperties fetchProps,
            DouyinBrowserService browser) {
        this.resolveService = resolveService;
        this.tokenCodec = tokenCodec;
        this.fetchProps = fetchProps;
        this.browser = browser;
    }

    @PostMapping("/api/douyin/extract-video")
    public Mono<Map<String, Object>> extract(@RequestBody(required = false) Map<String, Object> body) {
        String input = requireInput(body);
        if (!DouyinResolveService.containsAllowedPageUrl(input)) {
            throw new IntelligenceException(400, "请输入包含有效抖音 HTTPS 链接的分享文本或链接");
        }
        return resolveService.resolve(input)
                .flatMap(source -> source.assetResolvable() ? Mono.just(source)
                        : Mono.fromCallable(() -> browser.enhance(input)).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(source -> {
                    if (!source.assetResolvable()) {
                        return Mono.error(new IntelligenceException(502, "抖音未能解析出可播放媒体地址，请稍后重试"));
                    }
                    return Mono.just(Map.<String, Object>of("success", true, "data", buildSuccess(source)));
                });
    }

    private static String requireInput(Map<String, Object> body) {
        Object value = body == null ? null : body.get("input");
        if (!(value instanceof String text) || text.trim().isEmpty()) {
            throw new IntelligenceException(400, "请输入抖音分享文本或链接");
        }
        return text.trim();
    }

    private Map<String, Object> buildSuccess(DouyinSourceMaterial source) {
        String filename = DouyinFilename.buildDownloadFilename(
                source.title(), source.author(), source.videoId());
        // 对齐 legacy buildVideoAsset：token 内带上游所需 UA + Referer（经 sanitize 白名单）
        Map<String, String> requestHeaders = new LinkedHashMap<>();
        requestHeaders.put("User-Agent", fetchProps.userAgent());
        requestHeaders.put("Referer", source.resolvedUrl());
        DouyinMediaTarget target = DouyinMediaTarget.progressive(
                source.playableVideoUrl(), requestHeaders, filename, source.durationSeconds());
        String token = tokenCodec.create(target);
        return buildData(source, token);
    }

    /** 对齐 legacy {@code ExtractedDouyinVideoPayload}：undefined 字段省略（null 不放入 data）。 */
    private static Map<String, Object> buildData(DouyinSourceMaterial source, String token) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceUrl", source.sourceUrl());
        data.put("platform", "douyin");
        putIfPresent(data, "videoId", source.videoId());
        putIfPresent(data, "author", source.author());
        putIfPresent(data, "title", source.title());
        putIfPresent(data, "coverUrl", source.coverUrl());
        putIfPresent(data, "durationSeconds", source.durationSeconds());
        data.put("proxyVideoUrl", "/api/douyin/proxy/" + token);
        data.put("downloadVideoUrl", "/api/douyin/download/" + token);
        data.put("downloadAudioUrl", "/api/douyin/audio/" + token);
        data.put("usedSession", source.usedSession());
        data.put("fetchStage", source.fetchStage());
        return data;
    }

    private static void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
