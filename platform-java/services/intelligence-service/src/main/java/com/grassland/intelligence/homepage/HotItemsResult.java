package com.grassland.intelligence.homepage;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.grassland.intelligence.hottopic.HotTopicTaxonomyMetadata;
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
        String fetchedAt,
        HotTopicTaxonomyMetadata taxonomy) {

    public static HotItemsResult of60s(
            List<HotItemGroup> groups, Instant fetchedAt, HotTopicTaxonomyMetadata taxonomy) {
        return new HotItemsResult("60s", List.of(), groups, iso(fetchedAt), taxonomy);
    }

    public static HotItemsResult ofAlapi(
            List<HotItem> items, Instant fetchedAt, HotTopicTaxonomyMetadata taxonomy) {
        return new HotItemsResult("alapi", items, null, iso(fetchedAt), taxonomy);
    }

    private static String iso(Instant value) {
        return (value == null ? Instant.now() : value).toString();
    }
}
