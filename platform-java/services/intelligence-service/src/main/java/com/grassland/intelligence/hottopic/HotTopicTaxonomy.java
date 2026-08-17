package com.grassland.intelligence.hottopic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 加载并校验版本化热点分类词表；解析失败在应用启动期 fail-fast。 */
@Component
public final class HotTopicTaxonomy {

    private static final Set<String> ORGANIZATION_INDUSTRIES = Set.of(
            "catering", "retail", "beauty", "education", "e_commerce", "healthcare", "finance",
            "real_estate", "travel", "children", "gambling", "adult", "other");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String version;
    private final Map<String, List<String>> industries;
    private final Map<String, String> industryLabels;
    private final List<String> cities;
    private final Map<String, List<String>> contentTypes;
    private final Map<String, String> contentTypeLabels;
    private final HotTopicTaxonomyMetadata metadata;

    public HotTopicTaxonomy() {
        Contract contract = loadContract();
        this.version = contract.version();
        this.industries = immutableMap(contract.industries());
        this.industryLabels = Map.copyOf(contract.industryLabels());
        this.cities = List.copyOf(contract.cities());
        this.contentTypes = immutableMap(contract.contentTypes());
        this.contentTypeLabels = Map.copyOf(contract.contentTypeLabels());
        this.metadata = new HotTopicTaxonomyMetadata(
                version,
                options(industries, industryLabels),
                cities,
                options(contentTypes, contentTypeLabels));
    }

    public String version() {
        return version;
    }

    public Map<String, List<String>> industries() {
        return industries;
    }

    public List<String> cities() {
        return cities;
    }

    public Map<String, List<String>> contentTypes() {
        return contentTypes;
    }

    public HotTopicTaxonomyMetadata metadata() {
        return metadata;
    }

    private static Contract loadContract() {
        try (InputStream stream = HotTopicTaxonomy.class.getClassLoader()
                .getResourceAsStream("contracts/hot-topic-taxonomy.json")) {
            if (stream == null) {
                throw new IllegalStateException("Missing contracts/hot-topic-taxonomy.json");
            }
            JsonNode root = MAPPER.readTree(stream);
            String version = root.path("version").asText().trim();
            Map<String, List<String>> industries = stringLists(root.path("industries"));
            Map<String, String> industryLabels = strings(root.path("industryLabels"));
            List<String> cities = MAPPER.convertValue(root.path("cities"), new TypeReference<List<String>>() {});
            Map<String, List<String>> contentTypes = stringLists(root.path("contentTypes"));
            Map<String, String> contentTypeLabels = strings(root.path("contentTypeLabels"));

            validate(version, industries, industryLabels, cities, contentTypes, contentTypeLabels);
            return new Contract(version, industries, industryLabels, cities, contentTypes, contentTypeLabels);
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalStateException("Cannot load hot topic taxonomy contract", error);
        }
    }

    private static void validate(
            String version,
            Map<String, List<String>> industries,
            Map<String, String> industryLabels,
            List<String> cities,
            Map<String, List<String>> contentTypes,
            Map<String, String> contentTypeLabels) {
        if (version.isBlank() || !industries.keySet().equals(ORGANIZATION_INDUSTRIES)) {
            throw new IllegalArgumentException("Hot topic industries must match organization industry values");
        }
        if (!industryLabels.keySet().equals(industries.keySet())
                || contentTypes.isEmpty()
                || !contentTypeLabels.keySet().equals(contentTypes.keySet())
                || cities.isEmpty()
                || new LinkedHashSet<>(cities).size() != cities.size()) {
            throw new IllegalArgumentException("Invalid hot topic taxonomy metadata");
        }
        industries.forEach(HotTopicTaxonomy::validateTerms);
        contentTypes.forEach(HotTopicTaxonomy::validateTerms);
        if (cities.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Hot topic cities must not be blank");
        }
    }

    private static void validateTerms(String key, List<String> values) {
        if (key == null || key.isBlank() || values == null
                || values.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("Invalid hot topic taxonomy terms for " + key);
        }
    }

    private static Map<String, List<String>> stringLists(JsonNode node) {
        return MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, List<String>>>() {});
    }

    private static Map<String, String> strings(JsonNode node) {
        return MAPPER.convertValue(node, new TypeReference<LinkedHashMap<String, String>>() {});
    }

    private static Map<String, List<String>> immutableMap(Map<String, List<String>> source) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, List.copyOf(value)));
        return java.util.Collections.unmodifiableMap(result);
    }

    private static List<HotTopicTaxonomyMetadata.Option> options(
            Map<String, List<String>> values, Map<String, String> labels) {
        return values.keySet().stream()
                .map(value -> new HotTopicTaxonomyMetadata.Option(value, labels.get(value)))
                .toList();
    }

    private record Contract(
            String version,
            Map<String, List<String>> industries,
            Map<String, String> industryLabels,
            List<String> cities,
            Map<String, List<String>> contentTypes,
            Map<String, String> contentTypeLabels) {}
}
