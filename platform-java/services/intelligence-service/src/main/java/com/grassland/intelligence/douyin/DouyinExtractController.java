package com.grassland.intelligence.douyin;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 抖音视频提取（草场 GL-P3-MEDIA-001）：{@code POST /api/douyin/extract-video}。
 *
 * <p>校验分享文本含允许 page host 的 https URL（对齐 legacy）→ {@link DouyinResolveService} 解析
 * → 据 title/author/videoId 生成下载文件名 → 签发 {@link DouyinMediaTarget} token → 返回提取结果。
 *
 * <p>无 auth/credits（公开提取）。{@code proxyVideoUrl}/{@code downloadVideoUrl} 沿用 legacy 相对路径契约。
 */
@RestController
public class DouyinExtractController {

    private final DouyinResolveService resolveService;
    private final DouyinProxyToken tokenCodec;

    public DouyinExtractController(
            DouyinResolveService resolveService,
            DouyinProxyToken tokenCodec) {
        this.resolveService = resolveService;
        this.tokenCodec = tokenCodec;
    }

    @PostMapping("/api/douyin/extract-video")
    public Mono<Map<String, Object>> extract(@RequestBody Map<String, Object> body) {
        String input = requireInput(body);
        if (!DouyinResolveService.containsAllowedPageUrl(input)) {
            throw new IntelligenceException(400, "请输入包含有效抖音链接的分享文本或链接");
        }
        return resolveService.resolve(input).map(source -> {
            String filename = DouyinFilename.buildDownloadFilename(
                    source.title(), source.author(), source.videoId());
            DouyinMediaTarget target = toMediaTarget(source, filename);
            String token = tokenCodec.create(target);
            return Map.<String, Object>of("success", true, "data", buildData(source, token));
        });
    }

    private static String requireInput(Map<String, Object> body) {
        Object value = body == null ? null : body.get("input");
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IntelligenceException(400, "请输入包含抖音链接的分享文本或链接");
        }
        return text.trim();
    }

    private static DouyinMediaTarget toMediaTarget(DouyinSourceMaterial source, String filename) {
        return DouyinMediaTarget.progressive(
                source.playableVideoUrl(),
                source.requestHeaders(),
                filename,
                source.durationSeconds());
    }

    /** 对齐 legacy：undefined 字段在 JSON 中省略——null 不放入 data（前端读取语义与 legacy 一致）。 */
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
        data.put("playbackMode", source.playbackMode());
        return data;
    }

    private static void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
