package com.grassland.intelligence.videoproduction;

import com.fasterxml.jackson.databind.JsonNode;

final class VideoProviderJson {
    private VideoProviderJson() {}

    static String text(JsonNode node, String... pointers) {
        for (String pointer : pointers) {
            JsonNode value = node.at(pointer);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    static Integer integer(JsonNode node, String... pointers) {
        for (String pointer : pointers) {
            JsonNode value = node.at(pointer);
            if (value.isIntegralNumber() && value.canConvertToInt()) {
                return value.asInt();
            }
        }
        return null;
    }

    static int progress(JsonNode node, int fallback) {
        Integer value = integer(node, "/progress", "/data/progress");
        return value == null ? fallback : Math.max(0, Math.min(100, value));
    }

    static String dataImage(String value) {
        return value.startsWith("data:") ? value : "data:image/jpeg;base64," + value;
    }
}
