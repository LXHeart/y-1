package com.grassland.intelligence.creationcontext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads the versioned platform rules shared with the frontend contract. */
final class PlatformCreationRuleCatalog {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Contract CONTRACT = loadContract();
    static final String VERSION = CONTRACT.version();

    private PlatformCreationRuleCatalog() {}

    static Map<String, Object> snapshot(String platform, String contentForm) {
        JsonNode rule = CONTRACT.platforms().stream()
                .filter(candidate -> platform.equals(candidate.path("platformId").asText()))
                .findFirst()
                .orElseThrow(() -> new IntelligenceException(400, "平台规则不存在"));
        Map<String, Object> snapshot = MAPPER.convertValue(
                rule, new TypeReference<Map<String, Object>>() {});
        snapshot.put("version", VERSION);
        snapshot.put("platform", platform);
        snapshot.put("contentForm", contentForm);
        snapshot.put("requiresCreatorConfirmation", true);
        return new LinkedHashMap<>(snapshot);
    }

    private static Contract loadContract() {
        try (InputStream stream = PlatformCreationRuleCatalog.class.getClassLoader()
                .getResourceAsStream("contracts/platform-format-rules.json")) {
            if (stream == null) {
                throw new IllegalStateException("Missing contracts/platform-format-rules.json");
            }
            JsonNode root = MAPPER.readTree(stream);
            String version = root.path("version").asText();
            List<JsonNode> platforms = MAPPER.convertValue(
                    root.path("platforms"), new TypeReference<List<JsonNode>>() {});
            if (version.isBlank() || platforms.isEmpty()) {
                throw new IllegalStateException("Invalid platform format rule contract");
            }
            return new Contract(version, platforms);
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load platform format rule contract", error);
        }
    }

    private record Contract(String version, List<JsonNode> platforms) {}
}
