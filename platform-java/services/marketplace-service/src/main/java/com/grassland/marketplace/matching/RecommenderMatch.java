package com.grassland.marketplace.matching;

import java.time.Instant;
import java.util.List;

/** Fully explainable score returned to merchants and frozen when inviting. */
public record RecommenderMatch(
        String accountId, int totalScore, String level, long reputationPolicyVersion,
        Instant computedAt, List<MatchDimension> dimensions, List<String> reasons,
        TaskRecommenderInvitation invitation) {

    public RecommenderMatch {
        dimensions = List.copyOf(dimensions);
        reasons = List.copyOf(reasons);
    }

    public int dimensionScore(String key) {
        return dimensions.stream().filter(dimension -> dimension.key().equals(key))
                .mapToInt(MatchDimension::score).findFirst().orElse(0);
    }
}
