package com.grassland.intelligence.bilibili;

import com.grassland.intelligence.security.IntelligenceException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Bilibili 视频提取（草场 Slice 13 Stage 3）：{@code POST /api/bilibili/extract-video}。
 *
 * <p>校验分享文本含允许 page host 的 https URL（对齐 legacy zod {@code extractBilibiliVideoRequest}，
 * 缺失/空→400、无有效 B 站链接→400）→ {@link BilibiliResolveService} 解析 → 据 title/author/videoId 生成下载文件名
 * → 签发 {@link BilibiliMediaTarget} token → 返回 {@code {success:true,data:{sourceUrl,platform:'bilibili',
 * videoId,author,title,coverUrl,durationSeconds,proxyVideoUrl,downloadVideoUrl,playbackMode}}}。
 *
 * <p>无 auth/credits（公开提取）。{@code proxyVideoUrl}/{@code downloadVideoUrl} 沿用 legacy 相对路径契约；
 * edge-bff 路由开关 {@code EDGE_ROUTE_BILIBILI_MEDIA_INTELLIGENCE} 默认 false（与 proxy/download 同 flag 同开同关）。
 */
@RestController
public class BilibiliExtractController {

    private final BilibiliResolveService resolveService;
    private final BilibiliProxyToken tokenCodec;

    public BilibiliExtractController(BilibiliResolveService resolveService, BilibiliProxyToken tokenCodec) {
        this.resolveService = resolveService;
        this.tokenCodec = tokenCodec;
    }

    @PostMapping("/api/bilibili/extract-video")
    public Mono<Map<String, Object>> extract(@RequestBody Map<String, Object> body) {
        String input = requireInput(body);
        if (!BilibiliResolveService.containsAllowedPageUrl(input)) {
            throw new IntelligenceException(400, "请输入包含有效 B 站 HTTPS 链接的分享文本或链接");
        }
        return resolveService.resolve(input).map(source -> {
            String filename = BilibiliFilename.buildDownloadFilename(
                    source.title(), source.author(), source.videoId());
            BilibiliMediaTarget target = toMediaTarget(source, filename);
            String token = tokenCodec.create(target);
            return Map.<String, Object>of("success", true, "data", buildData(source, token));
        });
    }

    private static String requireInput(Map<String, Object> body) {
        Object value = body == null ? null : body.get("input");
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IntelligenceException(400, "请输入包含 B 站链接的分享文本或链接");
        }
        return text.trim();
    }

    private static BilibiliMediaTarget toMediaTarget(BilibiliSourceMaterial source, String filename) {
        return switch (source) {
            case BilibiliSourceMaterial.Progressive p -> new BilibiliMediaTarget.Progressive(
                    p.playableVideoUrl(), p.requestHeaders(), filename, p.durationSeconds());
            case BilibiliSourceMaterial.Dash d -> new BilibiliMediaTarget.Dash(
                    d.videoTrackUrl(), d.audioTrackUrl(), d.requestHeaders(), filename, d.durationSeconds());
        };
    }

    /** 对齐 legacy：undefined 字段在 JSON 中省略——null 不放入 data（前端读取语义与 legacy 一致）。 */
    private static Map<String, Object> buildData(BilibiliSourceMaterial source, String token) {
        // token 为 base64url（[A-Za-z0-9_-]）+ '.'，属 URL 安全字符，encodeURIComponent 为 noop，直接拼接。
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceUrl", source.sourceUrl());
        data.put("platform", "bilibili");
        putIfPresent(data, "videoId", source.videoId());
        putIfPresent(data, "author", source.author());
        putIfPresent(data, "title", source.title());
        putIfPresent(data, "coverUrl", source.coverUrl());
        putIfPresent(data, "durationSeconds", source.durationSeconds());
        data.put("proxyVideoUrl", "/api/bilibili/proxy/" + token);
        data.put("downloadVideoUrl", "/api/bilibili/download/" + token);
        data.put("playbackMode", source.playbackMode());
        return data;
    }

    private static void putIfPresent(Map<String, Object> data, String key, Object value) {
        if (value != null) {
            data.put(key, value);
        }
    }
}
