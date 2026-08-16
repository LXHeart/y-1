package com.grassland.identity.recommenderprofile;

import java.net.URI;

/**
 * 推荐官近期作品样本（任务书 #29+#30 #29）：自报的站外作品链接。
 *
 * <p>{@code url} 必须是 http(s) 绝对地址——作品样本是纯展示链接，非 http(s) 的 scheme
 * （javascript:、data: 等）会直接进商家浏览器，必须在入库前挡掉。{@code title} 可空。
 */
public record WorkSample(String platform, String title, String url) {

    public WorkSample {
        if (platform == null || platform.isBlank()) {
            throw new IllegalArgumentException("work sample platform is required");
        }
        platform = platform.trim();
        title = (title == null || title.isBlank()) ? null : title.trim();
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("work sample url is required");
        }
        url = url.trim();
        URI parsed;
        try {
            parsed = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("work sample url is invalid");
        }
        String scheme = parsed.getScheme();
        boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (parsed.getHost() == null || !httpScheme) {
            throw new IllegalArgumentException("work sample url must be http(s)");
        }
    }
}
