package com.grassland.intelligence.hottopic;

import java.util.List;

/** 前端筛选器所需的服务端权威 taxonomy 元数据，不下发关键词表。 */
public record HotTopicTaxonomyMetadata(
        String version,
        List<Option> industries,
        List<String> cities,
        List<Option> contentTypes) {

    public record Option(String value, String label) {}
}
