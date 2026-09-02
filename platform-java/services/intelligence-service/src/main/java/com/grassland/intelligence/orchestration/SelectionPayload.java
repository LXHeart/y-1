package com.grassland.intelligence.orchestration;

import java.io.Serializable;
import java.util.Map;

/** submitSelections 信号载荷：shotId → takeId（String 形态，行上的 selection 列才是真相源）。 */
public record SelectionPayload(Map<String, String> selections) implements Serializable {

    public static SelectionPayload of(Map<String, ?> chosen) {
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        chosen.forEach((shotId, takeId) -> raw.put(shotId, String.valueOf(takeId)));
        return new SelectionPayload(Map.copyOf(raw));
    }
}
