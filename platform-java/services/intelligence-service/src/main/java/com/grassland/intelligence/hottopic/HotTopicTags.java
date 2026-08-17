package com.grassland.intelligence.hottopic;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 缓存期生成的热点分类标签；taxonomyVersion 用于识别旧缓存或词表升级。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HotTopicTags(
        List<String> industries,
        String city,
        String contentType,
        String taxonomyVersion) {

    public HotTopicTags {
        industries = industries == null ? List.of() : List.copyOf(industries);
    }
}
