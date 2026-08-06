package com.grassland.intelligence.homepage;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 单条热点（复刻 legacy HomepageHotItem；可选字段 null 时省略，对齐 legacy 的 undefined）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HotItem(
        int rank,
        String title,
        String hotValue,
        String url,
        String cover,
        String sourceLabel) {}
