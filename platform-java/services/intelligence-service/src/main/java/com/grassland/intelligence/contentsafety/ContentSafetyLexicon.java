package com.grassland.intelligence.contentsafety;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 内容安全词库（任务书 #34 / ADR-D16 D2）：classpath 加载 {@code contracts/content-safety-lexicon.json}，
 * 静态单例 + 版本冻结——镜像 {@code PlatformCreationRuleCatalog} 的契约消费机制（processResources 拷入）。
 *
 * <p>词库内容是运营资产（改词 = 提 PR 发新版本）；本类只负责结构、加载与编译。词库版本随创作上下文
 * 快照冻结（§4.7：规则更新不能改变历史生成记录的检查结论）。
 */
public final class ContentSafetyLexicon {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Lexicon LEXICON = load();

    private ContentSafetyLexicon() {}

    public static String version() {
        return LEXICON.version();
    }

    public static Lexicon get() {
        return LEXICON;
    }

    /** 编译后的词库：六类目录（词组 + 正则）+ 例外表（词组 + 正则）。正则在加载期编译一次。 */
    public record Lexicon(
            String version,
            List<Category> categories,
            List<String> exceptions,
            List<CompiledPattern> exceptionPatterns) {

        /** 例外命中判定：词组子串或例外正则任一命中即视为「此处误报已豁免」。 */
        public boolean isExcepted(String text, int start, int end) {
            // 只有完整例外表达覆盖当前命中区间才豁免；不能用 exception.contains(window)，否则文本末尾
            // 单独出现「顶级」也会被「顶级食材之源」错误豁免。
            for (String exception : exceptions) {
                int exceptionStart = text.lastIndexOf(exception, start);
                if (exceptionStart >= 0 && exceptionStart <= start
                        && exceptionStart + exception.length() >= end) {
                    return true;
                }
            }
            for (CompiledPattern pattern : exceptionPatterns) {
                var matcher = pattern.pattern().matcher(text);
                while (matcher.find()) {
                    if (matcher.start() <= start && matcher.end() >= end) {
                        return true;
                    }
                    if (matcher.start() > start) {
                        break;
                    }
                }
            }
            return false;
        }
    }

    public record Category(String id, String severity, String advice,
                           List<String> phrases, List<CompiledPattern> patterns) {}

    /** 契约正则的编译形态（加载期编译，检查期复用）。 */
    public record CompiledPattern(String id, Pattern pattern) {}

    private static Lexicon load() {
        try (var stream = ContentSafetyLexicon.class.getClassLoader()
                .getResourceAsStream("contracts/content-safety-lexicon.json")) {
            if (stream == null) {
                throw new IllegalStateException("content-safety-lexicon.json 不在 classpath（检查 processResources）");
            }
            JsonNode root = MAPPER.readTree(stream);
            List<Category> categories = new ArrayList<>();
            for (JsonNode categoryNode : root.path("categories")) {
                List<CompiledPattern> patterns = new ArrayList<>();
                for (JsonNode patternNode : categoryNode.path("patterns")) {
                    patterns.add(new CompiledPattern(
                            patternNode.path("id").asText(),
                            Pattern.compile(patternNode.path("regex").asText())));
                }
                categories.add(new Category(
                        categoryNode.path("id").asText(),
                        categoryNode.path("severity").asText(),
                        categoryNode.path("advice").asText(),
                        List.copyOf(toTextList(categoryNode.path("phrases"))),
                        List.copyOf(patterns)));
            }
            List<CompiledPattern> exceptionPatterns = new ArrayList<>();
            for (JsonNode patternNode : root.path("exceptionPatterns")) {
                exceptionPatterns.add(new CompiledPattern(
                        patternNode.path("id").asText(),
                        Pattern.compile(patternNode.path("regex").asText())));
            }
            return new Lexicon(
                    root.path("version").asText(),
                    List.copyOf(categories),
                    List.copyOf(toTextList(root.path("exceptions"))),
                    List.copyOf(exceptionPatterns));
        } catch (Exception failure) {
            throw new IllegalStateException("内容安全词库加载失败", failure);
        }
    }

    private static List<String> toTextList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String text = item.asText("");
                if (!text.isBlank()) {
                    values.add(text);
                }
            });
        }
        return values;
    }
}
