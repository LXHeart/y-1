package com.grassland.intelligence.douyin;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 抖音热点单项（移植 legacy {@code DouyinHotItem}）。{@code rank/title/source} 恒有；
 * {@code hotValue/url/cover} 可空（受信主机校验未通过或上游缺失时省略）。
 *
 * <p>{@link JsonInclude.Include#NON_NULL} 复刻 legacy「undefined 字段不出现在 JSON」语义。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DouyinHotItem(int rank, String title, String hotValue, String url, String cover, String source) {}
