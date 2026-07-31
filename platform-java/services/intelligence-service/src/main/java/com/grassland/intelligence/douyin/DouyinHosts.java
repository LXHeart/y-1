package com.grassland.intelligence.douyin;

import java.util.List;
import java.util.Set;

/**
 * 抖音受信主机白名单（移植 legacy {@code server/src/lib/douyin-hosts.ts}）。静态主机名校验，不做 DNS 解析。
 *
 * <p>{@link #isAllowedPageHost} 用于热点链接 {@code link}（精确匹配）；{@link #isAllowedVideoHost} 用于封面图
 * {@code cover}（精确匹配或已知视频 CDN 后缀匹配）。与 legacy 行为逐字一致。
 */
public final class DouyinHosts {

    private static final Set<String> PAGE_HOSTS = Set.of(
            "douyin.com", "www.douyin.com", "v.douyin.com", "iesdouyin.com", "www.iesdouyin.com");

    private static final Set<String> VIDEO_HOSTS = Set.of(
            "douyin.com", "www.douyin.com", "iesdouyin.com", "www.iesdouyin.com", "aweme.snssdk.com");

    private static final List<String> VIDEO_HOST_SUFFIXES = List.of(
            "zjcdn.com", "douyinvod.com", "byteimg.com", "bytedance.com");

    private DouyinHosts() {}

    /** page host 精确匹配（大小写不敏感）。 */
    public static boolean isAllowedPageHost(String hostname) {
        return hostname != null && PAGE_HOSTS.contains(hostname.toLowerCase());
    }

    /** video host 精确匹配或受信 CDN 后缀匹配（大小写不敏感）。 */
    public static boolean isAllowedVideoHost(String hostname) {
        if (hostname == null) {
            return false;
        }
        String h = hostname.toLowerCase();
        if (VIDEO_HOSTS.contains(h)) {
            return true;
        }
        for (String suffix : VIDEO_HOST_SUFFIXES) {
            if (h.equals(suffix) || h.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }
}
