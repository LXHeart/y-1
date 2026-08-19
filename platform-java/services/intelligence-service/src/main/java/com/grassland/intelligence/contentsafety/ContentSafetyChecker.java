package com.grassland.intelligence.contentsafety;

import com.grassland.intelligence.contentsafety.ContentSafetyLexicon.Category;
import com.grassland.intelligence.contentsafety.ContentSafetyLexicon.CompiledPattern;
import com.grassland.intelligence.contentsafety.ContentSafetyLexicon.Lexicon;
import com.grassland.intelligence.contentsafety.SafetyReport.Finding;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Deterministic L1 checks over the active base lexicon and optional platform/industry overlays. */
@Component
public class ContentSafetyChecker {

    private final ContentSafetyLexicon lexicons;

    public ContentSafetyChecker(ContentSafetyLexicon lexicons) {
        this.lexicons = lexicons;
    }

    public Mono<CheckResult> checkActive(String text, String platform, String industry) {
        return lexicons.activeLexicon().map(value -> check(value, text, platform, industry));
    }

    public CheckResult checkCached(String text, String platform, String industry) {
        return check(lexicons.cachedLexicon(), text, platform, industry);
    }

    /** Static compatibility using the classpath seed. */
    public static List<Finding> check(String text) {
        return check(ContentSafetyLexicon.get(), text, null, null).findings();
    }

    public static CheckResult check(Lexicon lexicon, String text, String platform, String industry) {
        if (text == null || text.isEmpty()) {
            return new CheckResult(List.of(), List.of(), lexicon.version());
        }
        List<Finding> findings = baseFindings(lexicon, text);
        List<String> applied = new ArrayList<>();

        String platformKey = normalize(platform);
        List<String> platformPhrases = platformKey == null
                ? List.of() : lexicon.overlays().platforms().getOrDefault(platformKey, List.of());
        if (!platformPhrases.isEmpty()) {
            applied.add(platformKey);
            findings.addAll(overlayFindings(
                    lexicon, text, platformPhrases, "platform_overlay", "low",
                    "该发布平台不推荐此类表达，建议改用平台内合规组件"));
        }

        String industryKey = resolveIndustry(lexicon, industry);
        List<String> industryPhrases = industryKey == null
                ? List.of() : lexicon.overlays().industries().getOrDefault(industryKey, List.of());
        if (!industryPhrases.isEmpty()) {
            applied.add(industryKey);
            findings.addAll(overlayFindings(
                    lexicon, text, industryPhrases, "industry_overlay", "medium",
                    "该行业表达存在较高合规风险，请改为可验证的客观描述"));
        }
        return new CheckResult(List.copyOf(findings), List.copyOf(applied), lexicon.version());
    }

    private static List<Finding> baseFindings(Lexicon lexicon, String text) {
        List<Finding> findings = new ArrayList<>();
        for (Category category : lexicon.categories()) {
            for (String phrase : category.phrases()) {
                int index = firstAllowedIndex(lexicon, text, phrase);
                if (index >= 0) {
                    findings.add(new Finding(category.id(), category.severity(),
                            phrase, index, category.advice(), false));
                }
            }
            for (CompiledPattern compiled : category.patterns()) {
                var matcher = compiled.pattern().matcher(text);
                while (matcher.find()) {
                    if (!lexicon.isExcepted(text, matcher.start(), matcher.end())) {
                        findings.add(new Finding(category.id(), category.severity(),
                                matcher.group(), matcher.start(), category.advice(), false));
                        break;
                    }
                }
            }
        }
        return findings;
    }

    private static List<Finding> overlayFindings(
            Lexicon lexicon, String text, List<String> phrases,
            String category, String severity, String advice) {
        List<Finding> findings = new ArrayList<>();
        for (String phrase : phrases) {
            int index = firstAllowedIndex(lexicon, text, phrase);
            if (index >= 0) findings.add(new Finding(
                    category, severity, phrase, index, advice, false));
        }
        return findings;
    }

    private static int firstAllowedIndex(Lexicon lexicon, String text, String phrase) {
        int from = 0;
        while (from < text.length()) {
            int index = indexOfWithBoundary(text, phrase, from);
            if (index < 0) return -1;
            if (!lexicon.isExcepted(text, index, index + phrase.length())) return index;
            from = index + 1;
        }
        return -1;
    }

    private static String resolveIndustry(Lexicon lexicon, String industry) {
        String normalized = normalize(industry);
        if (normalized == null) return null;
        if (lexicon.overlays().industries().containsKey(normalized)) return normalized;
        String direct = lexicon.overlays().industryAliases().get(industry.trim());
        if (direct != null) return direct;
        return lexicon.overlays().industryAliases().get(normalized);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static int indexOfWithBoundary(String text, String phrase, int fromIndex) {
        if (isLatin(phrase)) {
            int from = fromIndex;
            while (true) {
                int index = text.indexOf(phrase, from);
                if (index < 0) return -1;
                if (hasLatinBoundaries(text, index, index + phrase.length())) return index;
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
        return (start <= 0 || !isWordChar(text.charAt(start - 1)))
                && (end >= text.length() || !isWordChar(text.charAt(end)));
    }

    private static boolean isWordChar(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '-';
    }

    public record CheckResult(
            List<Finding> findings, List<String> appliedOverlays, String lexiconVersion) {}
}
