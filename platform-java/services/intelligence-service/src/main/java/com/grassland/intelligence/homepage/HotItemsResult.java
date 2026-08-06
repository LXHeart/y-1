package com.grassland.intelligence.homepage;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * 首页热点响应体（复刻 legacy HomepageHotItemsResult）。
 *
 * <p>60s：items 为空数组、groups 有值。alapi：items 有值、groups 省略（与 legacy 一致）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HotItemsResult(
        String provider,
        List<HotItem> items,
        List<HotItemGroup> groups,
        String fetchedAt) {

    public static HotItemsResult of60s(List<HotItemGroup> groups, Instant fetchedAt) {
        return new HotItemsResult("60s", List.of(), groups, iso(fetchedAt));
    }

    public static HotItemsResult ofAlapi(List<HotItem> items, Instant fetchedAt) {
        return new HotItemsResult("alapi", items, null, iso(fetchedAt));
    }

    private static String iso(Instant value) {
        return (value == null ? Instant.now() : value).toString();
    }
}
