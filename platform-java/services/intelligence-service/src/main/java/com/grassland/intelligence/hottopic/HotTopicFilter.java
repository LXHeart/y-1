package com.grassland.intelligence.hottopic;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 热点请求过滤器：维度内 OR、跨维度 AND。 */
public record HotTopicFilter(
        Set<String> industries,
        Set<String> cities,
        Set<String> contentTypes,
        boolean includeExpired) {

    public static final HotTopicFilter DEFAULT = new HotTopicFilter(Set.of(), Set.of(), Set.of(), false);

    public HotTopicFilter {
        industries = normalize(industries, true);
        cities = normalize(cities, false);
        contentTypes = normalize(contentTypes, true);
    }

    public static HotTopicFilter from(
            List<String> industries, List<String> cities, List<String> contentTypes, boolean includeExpired) {
        return new HotTopicFilter(expand(industries), expand(cities), expand(contentTypes), includeExpired);
    }

    public boolean matches(HotTopicTags tags) {
        if (tags == null) {
            return industries.isEmpty() && cities.isEmpty() && contentTypes.isEmpty();
        }
        boolean industryMatches = industries.isEmpty()
                || tags.industries().stream().anyMatch(industries::contains);
        boolean cityMatches = cities.isEmpty() || (tags.city() != null && cities.contains(tags.city()));
        boolean contentTypeMatches = contentTypes.isEmpty()
                || (tags.contentType() != null && contentTypes.contains(tags.contentType()));
        return industryMatches && cityMatches && contentTypeMatches;
    }

    private static Set<String> expand(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> {
            if (value != null) {
                for (String part : value.split(",")) {
                    if (!part.isBlank()) {
                        result.add(part.trim());
                    }
                }
            }
        });
        return result;
    }

    private static Set<String> normalize(Set<String> values, boolean lowercase) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .map(value -> lowercase ? value.toLowerCase(Locale.ROOT) : value)
                .forEach(result::add);
        return Set.copyOf(result);
    }
}
