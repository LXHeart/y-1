package com.grassland.intelligence.douyin;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link DouyinHosts} 单测（移植 legacy {@code douyin-hosts.ts} 语义）。 */
class DouyinHostsTest {

    @Test
    @DisplayName("page host 精确匹配，大小写不敏感")
    void pageHostExactMatchCaseInsensitive() {
        assertThat(DouyinHosts.isAllowedPageHost("www.douyin.com")).isTrue();
        assertThat(DouyinHosts.isAllowedPageHost("v.douyin.com")).isTrue();
        assertThat(DouyinHosts.isAllowedPageHost("iesdouyin.com")).isTrue();
        assertThat(DouyinHosts.isAllowedPageHost("WWW.DOUYIN.COM")).isTrue();
    }

    @Test
    @DisplayName("page host 不做后缀/子域匹配")
    void pageHostRejectsSuffixAndSubdomain() {
        assertThat(DouyinHosts.isAllowedPageHost("sub.douyin.com")).isFalse();
        assertThat(DouyinHosts.isAllowedPageHost("evil.com")).isFalse();
        assertThat(DouyinHosts.isAllowedPageHost("douyin.com.evil.com")).isFalse();
        assertThat(DouyinHosts.isAllowedPageHost(null)).isFalse();
    }

    @Test
    @DisplayName("video host 精确匹配或受信 CDN 后缀匹配")
    void videoHostExactOrSuffix() {
        assertThat(DouyinHosts.isAllowedVideoHost("aweme.snssdk.com")).isTrue();
        assertThat(DouyinHosts.isAllowedVideoHost("byteimg.com")).isTrue();
        assertThat(DouyinHosts.isAllowedVideoHost("p9.byteimg.com")).isTrue();
        assertThat(DouyinHosts.isAllowedVideoHost("a.b.douyinvod.com")).isTrue();
    }

    @Test
    @DisplayName("video host 拒绝未知主机")
    void videoHostRejectsUnknown() {
        assertThat(DouyinHosts.isAllowedVideoHost("bilivideo.com")).isFalse();
        assertThat(DouyinHosts.isAllowedVideoHost("evil.com")).isFalse();
        assertThat(DouyinHosts.isAllowedVideoHost("douyin.com.evil.com")).isFalse();
        assertThat(DouyinHosts.isAllowedVideoHost(null)).isFalse();
    }
}
