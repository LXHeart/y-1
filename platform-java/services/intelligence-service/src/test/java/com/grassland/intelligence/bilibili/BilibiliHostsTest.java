package com.grassland.intelligence.bilibili;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link BilibiliHosts} 单测（移植 legacy {@code bilibili-hosts.ts} 语义）。 */
class BilibiliHostsTest {

    @Test
    @DisplayName("page host 精确匹配")
    void pageHostExact() {
        assertThat(BilibiliHosts.isAllowedPageHost("www.bilibili.com")).isTrue();
        assertThat(BilibiliHosts.isAllowedPageHost("bilibili.com")).isTrue();
        assertThat(BilibiliHosts.isAllowedPageHost("m.bilibili.com")).isTrue();
        assertThat(BilibiliHosts.isAllowedPageHost("b23.tv")).isTrue();
        assertThat(BilibiliHosts.isAllowedPageHost("WWW.BILIBILI.COM")).isTrue();
    }

    @Test
    @DisplayName("page host 拒绝未知/子域")
    void pageHostRejectsUnknown() {
        assertThat(BilibiliHosts.isAllowedPageHost("evil.com")).isFalse();
        assertThat(BilibiliHosts.isAllowedPageHost("sub.bilibili.com")).isFalse();
        assertThat(BilibiliHosts.isAllowedPageHost(null)).isFalse();
    }

    @Test
    @DisplayName("video host 精确 mirror 或 bilivideo 后缀匹配")
    void videoHostExactOrSuffix() {
        assertThat(BilibiliHosts.isAllowedVideoHost("upos-sz-mirrorali.bilivideo.com")).isTrue();
        assertThat(BilibiliHosts.isAllowedVideoHost("xyz.bilivideo.com")).isTrue();
        assertThat(BilibiliHosts.isAllowedVideoHost("abc.bilivideo.cn")).isTrue();
    }

    @Test
    @DisplayName("video host 拒绝未知主机")
    void videoHostRejectsUnknown() {
        assertThat(BilibiliHosts.isAllowedVideoHost("evil.com")).isFalse();
        assertThat(BilibiliHosts.isAllowedVideoHost("bilivideo.com.evil.com")).isFalse();
        assertThat(BilibiliHosts.isAllowedVideoHost(null)).isFalse();
    }
}
