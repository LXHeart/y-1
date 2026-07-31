package com.grassland.intelligence.bilibili;

import java.util.Set;

/**
 * Bilibili 受信主机白名单（移植 legacy {@code server/src/lib/bilibili-hosts.ts}）。静态主机名校验，不做 DNS。
 *
 * <p>{@link #isAllowedPageHost}（精确）用于分享页 URL 抽取；{@link #isAllowedVideoHost}（精确 mirror 或
 * {@code bilivideo.com}/{@code bilivideo.cn} 后缀）用于视频/封面流地址校验，是 progressive 代理的 SSRF 边界。
 */
public final class BilibiliHosts {

    private static final Set<String> PAGE_HOSTS = Set.of(
            "www.bilibili.com", "bilibili.com", "m.bilibili.com", "b23.tv");

    private static final Set<String> VIDEO_HOSTS = Set.of(
            "upos-sz-mirrorcosov.bilivideo.com",
            "upos-sz-mirror08c.bilivideo.com",
            "upos-sz-mirrorali.bilivideo.com",
            "upos-sz-mirroralibstar1.bilivideo.com",
            "upos-sz-mirrorhw.bilivideo.com",
            "upos-sz-mirrorcos.bilivideo.com");

    private static final Set<String> VIDEO_HOST_SUFFIXES = Set.of("bilivideo.com", "bilivideo.cn");

    private BilibiliHosts() {}

    /** page host 精确匹配（大小写不敏感）。 */
    public static boolean isAllowedPageHost(String hostname) {
        return hostname != null && PAGE_HOSTS.contains(hostname.toLowerCase());
    }

    /** video host 精确 mirror 或受信后缀匹配（大小写不敏感）。 */
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
