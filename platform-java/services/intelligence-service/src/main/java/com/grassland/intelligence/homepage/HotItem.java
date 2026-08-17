package com.grassland.intelligence.homepage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.grassland.intelligence.hottopic.HotTopicTags;

/** 单条热点（复刻 legacy HomepageHotItem；可选字段 null 时省略，对齐 legacy 的 undefined）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HotItem(
        int rank,
        String title,
        String hotValue,
        String url,
        String cover,
        String sourceLabel,
        HotTopicTags tags,
        String validUntil,
        Boolean expired) {

    /** 上游解析兼容构造器：分类与有效期在 HomepageHotService 缓存组装处补齐。 */
    public HotItem(int rank, String title, String hotValue, String url, String cover, String sourceLabel) {
        this(rank, title, hotValue, url, cover, sourceLabel, null, null, null);
    }

    public HotItem withTags(HotTopicTags value) {
        return new HotItem(rank, title, hotValue, url, cover, sourceLabel, value, validUntil, expired);
    }

    public HotItem withValidity(String nextValidUntil, boolean nextExpired) {
        return new HotItem(rank, title, hotValue, url, cover, sourceLabel, tags, nextValidUntil, nextExpired);
    }
}
