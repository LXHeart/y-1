package com.grassland.marketplace.matching;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One auditable component of a deterministic match score. */
public record MatchDimension(
        String key, String label, int score, int maxScore,
        Map<String, Object> evidence, String reason) {

    public MatchDimension {
        evidence = Collections.unmodifiableMap(new LinkedHashMap<>(evidence));
    }
}
