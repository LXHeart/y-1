package com.grassland.intelligence.articleimage;

/** 文章配图位置。 */
public record ImagePlacement(
        String position,
        String description,
        String searchKeywords,
        String prompt) {}
