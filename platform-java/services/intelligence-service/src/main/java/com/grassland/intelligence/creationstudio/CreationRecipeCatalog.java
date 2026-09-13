package com.grassland.intelligence.creationstudio;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.grassland.intelligence.security.IntelligenceException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 任务书 #101 C101-01：创作场景模板目录（contracts/creation-recipes.v1.json，前后端单源）。
 *
 * <p>{@link #resolve} 必须返回唯一合法定义或受控 400，不能回退到第一个模板；
 * enabled=false 的模板不出现在可见集合，也不能参与生成（后续卡按里程碑启用）。
 * 公开响应不携带 prompt——契约本身不含内部提示词。
 */
public final class CreationRecipeCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern SEMVER = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");
    private static final Set<String> ASPECTS = Set.of("3:4", "9:16", "1:1", "16:9", "2.35:1");
    private static final Contract CONTRACT = loadContract();

    static final String VERSION = CONTRACT.version();

    private CreationRecipeCatalog() {}

    /** 模板定义（§6.3 RecipeDefinition；字段与前端 creation-studio.ts 同名）。 */
    public record RecipeDefinition(
            String id,
            String version,
            String label,
            boolean enabled,
            List<String> platformIds,
            List<String> contentForms,
            List<String> processingModes,
            int minItems,
            int maxItems,
            String defaultAspect,
            List<String> supportedStrategies) {

        public boolean applies(String platform, String contentForm, String processingMode) {
            return platformIds.contains(platform)
                    && contentForms.contains(contentForm)
                    && processingModes.contains(processingMode);
        }
    }

    /** API101-01：过滤平台／形式／加工方式后的可见（enabled）集合。 */
    public static List<RecipeDefinition> visible(String platform, String contentForm, String processingMode) {
        List<RecipeDefinition> items = new ArrayList<>();
        for (RecipeDefinition recipe : CONTRACT.recipes()) {
            if (!recipe.enabled()) {
                continue;
            }
            if (platform != null && !recipe.platformIds().contains(platform)) {
                continue;
            }
            if (contentForm != null && !recipe.contentForms().contains(contentForm)) {
                continue;
            }
            if (processingMode != null && !recipe.processingModes().contains(processingMode)) {
                continue;
            }
            items.add(recipe);
        }
        return items;
    }

    public static RecipeDefinition byId(String id) {
        for (RecipeDefinition recipe : CONTRACT.recipes()) {
            if (recipe.id().equals(id)) {
                return recipe;
            }
        }
        return null;
    }

    /**
     * 唯一合法定义或受控 400（未知 ID／未知版本／禁用／不适用）；永不静默降级到其他模板。
     */
    public static RecipeDefinition resolve(String id, String version, String platform, String contentForm,
            String processingMode) {
        RecipeDefinition recipe = byId(id);
        if (recipe == null) {
            throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "未知模板：" + id);
        }
        if (version != null && !version.equals(recipe.version())) {
            throw new IntelligenceException(400, "STUDIO_INVALID_INPUT",
                    "模板 " + id + " 不存在版本 " + version + "（当前 " + recipe.version() + "）");
        }
        if (!recipe.enabled()) {
            throw new IntelligenceException(400, "STUDIO_INVALID_INPUT", "模板「" + recipe.label() + "」暂未开放");
        }
        if (!recipe.applies(platform, contentForm, processingMode)) {
            throw new IntelligenceException(400, "STUDIO_INVALID_INPUT",
                    "模板「" + recipe.label() + "」不适用于当前平台／加工方式");
        }
        return recipe;
    }

    private static Contract loadContract() {
        try (InputStream stream = CreationRecipeCatalog.class.getClassLoader()
                .getResourceAsStream("contracts/creation-recipes.v1.json")) {
            if (stream == null) {
                throw new IllegalStateException("Missing contracts/creation-recipes.v1.json");
            }
            JsonNode root = MAPPER.readTree(stream);
            String version = root.path("version").asText();
            List<RecipeDefinition> recipes = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (JsonNode node : root.path("recipes")) {
                String id = node.path("id").asText();
                String recipeVersion = node.path("version").asText();
                if (version.isBlank() || id.isBlank() || !SEMVER.matcher(recipeVersion).matches()) {
                    throw new IllegalStateException("Invalid creation recipe contract entry: " + id);
                }
                if (!ids.add(id)) {
                    throw new IllegalStateException("Duplicate creation recipe id: " + id);
                }
                String aspect = node.path("defaultAspect").isNull() ? null : node.path("defaultAspect").asText();
                if (aspect != null && !ASPECTS.contains(aspect)) {
                    throw new IllegalStateException("Illegal default aspect for recipe " + id + ": " + aspect);
                }
                int minItems = node.path("minItems").asInt(-1);
                int maxItems = node.path("maxItems").asInt(-1);
                if (minItems < 0 || maxItems < minItems) {
                    throw new IllegalStateException("Illegal item bounds for recipe " + id);
                }
                recipes.add(new RecipeDefinition(id, recipeVersion, node.path("label").asText(),
                        node.path("enabled").asBoolean(false), listOf(node.path("platformIds")),
                        listOf(node.path("contentForms")), listOf(node.path("processingModes")), minItems, maxItems,
                        aspect, listOf(node.path("supportedStrategies"))));
            }
            if (recipes.isEmpty()) {
                throw new IllegalStateException("Empty creation recipe contract");
            }
            return new Contract(version, List.copyOf(recipes));
        } catch (IOException error) {
            throw new IllegalStateException("Cannot load creation recipe contract", error);
        }
    }

    private static List<String> listOf(JsonNode node) {
        List<String> values = MAPPER.convertValue(node, new TypeReference<List<String>>() {});
        return values == null ? List.of() : List.copyOf(values);
    }

    private record Contract(String version, List<RecipeDefinition> recipes) {}
}
