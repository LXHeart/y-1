package com.grassland.intelligence.articleimage;

/** 文章搜图结果，字段与 legacy JSON 响应一致。 */
public record ImageSearchResult(
        String url,
        String thumbnailUrl,
        String sourceUrl,
        String description,
        Integer width,
        Integer height) {}
