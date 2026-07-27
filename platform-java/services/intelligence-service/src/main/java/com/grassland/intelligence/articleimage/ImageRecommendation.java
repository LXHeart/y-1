package com.grassland.intelligence.articleimage;

import java.util.List;

/** 文章配图推荐响应。 */
public record ImageRecommendation(int recommendedCount, List<ImagePlacement> placements) {
    public ImageRecommendation {
        placements = List.copyOf(placements);
    }
}
