package com.grassland.intelligence.contentsafety;

import com.grassland.intelligence.contentsafety.ContentSafetyLexicon.Category;
import com.grassland.intelligence.contentsafety.ContentSafetyLexicon.CompiledPattern;
import com.grassland.intelligence.contentsafety.ContentSafetyLexicon.Lexicon;
import com.grassland.intelligence.contentsafety.SafetyReport.Finding;
import java.util.ArrayList;
import java.util.List;

/**
 * L1 确定性检查（ADR-D16 D1/D3）：词库子串 + 正则，无模型依赖、纯内存毫秒级——每次生成必跑的底线。
 *
 * <p>匹配语义：中文（CJK）词组按子串匹配；拉丁词组按词边界（防止「best」命中「bestfriend」式误报——
 * 词库当前以中文为主，边界逻辑为拉丁词预留）。命中后经例外表豁免（豁免窗口=命中词+后两字符，
 * 覆盖「第一」vs「第一时间」类前缀命中）。同类目同区间只报首个命中（一词一报，不逐字重复）。
 */
public final class ContentSafetyChecker {

    private static final Lexicon LEXICON = ContentSafetyLexicon.get();

    private ContentSafetyChecker() {}

    /** L1 检查：返回 findings（可能为空）。纯内存，可在事件循环执行。 */
    public static List<Finding> check(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Finding> findings = new ArrayList<>();
        for (Category category : LEXICON.categories()) {
            for (String phrase : category.phrases()) {
                int from = 0;
                while (from < text.length()) {
                    int index = indexOfWithBoundary(text, phrase, from);
                    if (index < 0) {
                        break;
                    }
                    if (!LEXICON.isExcepted(text, index, index + phrase.length())) {
                        findings.add(new Finding(category.id(), category.severity(),
                                phrase, index, category.advice(), false));
                        break;
                    }
                    from = index + 1;
                }
            }
            for (CompiledPattern compiled : category.patterns()) {
                var matcher = compiled.pattern().matcher(text);
                while (matcher.find()) {
                    if (!LEXICON.isExcepted(text, matcher.start(), matcher.end())) {
                        findings.add(new Finding(category.id(), category.severity(),
                                matcher.group(), matcher.start(), category.advice(), false));
                        break;
                    }
                }
            }
        }
        return findings;
    }

    /**
     * 词组定位：CJK 词组按 {@link String#indexOf}；纯拉丁词组要求词边界
     * （命中字符两侧不是字母/数字才算，避免英文子串误报）。
     */
    private static int indexOfWithBoundary(String text, String phrase, int fromIndex) {
        if (isLatin(phrase)) {
            int from = fromIndex;
            while (true) {
                int index = text.indexOf(phrase, from);
                if (index < 0) {
                    return -1;
                }
                if (hasLatinBoundaries(text, index, index + phrase.length())) {
                    return index;
                }
                from = index + 1;
            }
        }
        return text.indexOf(phrase, fromIndex);
    }

    private static boolean isLatin(String phrase) {
        return phrase.chars().allMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '-' || c == '_');
    }

    private static boolean hasLatinBoundaries(String text, int start, int end) {
        boolean leftBoundary = start <= 0 || !isWordChar(text.charAt(start - 1));
        boolean rightBoundary = end >= text.length() || !isWordChar(text.charAt(end));
        return leftBoundary && rightBoundary;
    }

    private static boolean isWordChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-';
    }
}
