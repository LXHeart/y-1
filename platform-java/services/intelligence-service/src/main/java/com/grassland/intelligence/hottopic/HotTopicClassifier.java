package com.grassland.intelligence.hottopic;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 标题确定性词法分类：中文子串匹配，拉丁词按字母数字边界匹配。 */
@Component
public final class HotTopicClassifier {

    private final HotTopicTaxonomy taxonomy;

    public HotTopicClassifier(HotTopicTaxonomy taxonomy) {
        this.taxonomy = taxonomy;
    }

    public HotTopicTags classify(String title) {
        String source = title == null ? "" : title;
        List<String> industries = taxonomy.industries().entrySet().stream()
                .filter(entry -> matchesAny(source, entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        String city = firstMatch(source, taxonomy.cities());
        String contentType = taxonomy.contentTypes().entrySet().stream()
                .filter(entry -> matchesAny(source, entry.getValue()))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
        return new HotTopicTags(industries, city, contentType, taxonomy.version());
    }

    public HotTopicTaxonomy taxonomy() {
        return taxonomy;
    }

    private static String firstMatch(String source, List<String> terms) {
        return terms.stream().filter(term -> matches(source, term)).findFirst().orElse(null);
    }

    private static boolean matchesAny(String source, List<String> terms) {
        return terms.stream().anyMatch(term -> matches(source, term));
    }

    static boolean matches(String source, String term) {
        if (source == null || term == null || term.isBlank()) {
            return false;
        }
        String haystack = source.toLowerCase(Locale.ROOT);
        String needle = term.toLowerCase(Locale.ROOT);
        int fromIndex = 0;
        while (fromIndex <= haystack.length() - needle.length()) {
            int index = haystack.indexOf(needle, fromIndex);
            if (index < 0) {
                return false;
            }
            int end = index + needle.length();
            if (!containsLatinOrDigit(needle)
                    || ((!hasLetterOrDigitBefore(haystack, index)) && !hasLetterOrDigitAfter(haystack, end))) {
                return true;
            }
            fromIndex = index + 1;
        }
        return false;
    }

    private static boolean containsLatinOrDigit(String value) {
        return value.codePoints().anyMatch(codePoint ->
                (codePoint >= 'a' && codePoint <= 'z') || Character.isDigit(codePoint));
    }

    private static boolean hasLetterOrDigitBefore(String value, int index) {
        return index > 0 && isLatinLetterOrDigit(value.codePointBefore(index));
    }

    private static boolean hasLetterOrDigitAfter(String value, int index) {
        return index < value.length() && isLatinLetterOrDigit(value.codePointAt(index));
    }

    private static boolean isLatinLetterOrDigit(int codePoint) {
        return (codePoint >= 'a' && codePoint <= 'z')
                || (codePoint >= 'A' && codePoint <= 'Z')
                || Character.isDigit(codePoint);
    }
}
