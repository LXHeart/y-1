package com.grassland.intelligence.contentsafety;

import com.grassland.intelligence.contentsafety.ContentFingerprintRepository.Fingerprint;
import com.grassland.intelligence.contentsafety.SafetyReport.Finding;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Deterministic SimHash duplicate detection and intra-text repetition advisory. */
@Service
public class OriginalityChecker {

    private static final Logger log = LoggerFactory.getLogger(OriginalityChecker.class);
    /** 跨任务撞文（分支4）的时间展示格式（任务书 #63 4.2：UTC yyyy-MM-dd HH:mm）。 */
    private static final DateTimeFormatter BRANCH_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    /** 同源判定（分支3）：距本次检查 ≤2 小时的指纹视为本文早期检查版本。 */
    private static final Duration SAME_SOURCE_WINDOW = Duration.ofHours(2);

    private final ContentFingerprintRepository repository;
    private final ContentSafetyProperties properties;

    public OriginalityChecker(ContentFingerprintRepository repository, ContentSafetyProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public Mono<List<Finding>> checkAndRecord(String text, Context context) {
        FingerprintValue value = fingerprint(text);
        if (context == null || context.ownerAccountId() == null || value.shingleCount() == 0) {
            return Mono.just(lowOriginality(value));
        }
        Instant now = Instant.now();
        Mono<List<Fingerprint>> candidates = repository.findCandidates(
                        context.ownerAccountId(), context.taskId(), now.minus(Duration.ofDays(90)))
                .collectList()
                .onErrorResume(error -> {
                    log.warn("originality candidate lookup failed", error);
                    return Mono.just(List.of());
                });
        return candidates.flatMap(existing -> {
            List<Finding> findings = new ArrayList<>(lowOriginality(value));
            closest(value.simhash(), existing).ifPresent(match -> findings.add(duplicateFinding(match, context, now)));
            Fingerprint row = new Fingerprint(
                    null, context.ownerAccountId(), context.taskId(), context.applicationId(),
                    context.platform(), context.contentForm(), value.simhash(), value.shingleCount(),
                    context.sourceKind(), null);
            return repository.insert(row)
                    .onErrorResume(error -> {
                        log.warn("originality fingerprint insert failed", error);
                        return Mono.empty();
                    })
                    .thenReturn(List.copyOf(findings));
        });
    }

    public static FingerprintValue fingerprint(String text) {
        String normalized = normalize(text);
        if (normalized.length() < 2) return new FingerprintValue(0L, 0, 0d, List.of());
        Map<String, Integer> frequency = new LinkedHashMap<>();
        for (int i = 0; i < normalized.length() - 1; i++) {
            String shingle = normalized.substring(i, i + 2);
            frequency.merge(shingle, 1, Integer::sum);
        }
        int[] vector = new int[64];
        frequency.forEach((shingle, weight) -> {
            long hash = fnv1a64(shingle);
            for (int bit = 0; bit < 64; bit++) {
                vector[bit] += ((hash >>> bit) & 1L) == 1L ? weight : -weight;
            }
        });
        long simhash = 0L;
        for (int bit = 0; bit < 64; bit++) if (vector[bit] >= 0) simhash |= 1L << bit;
        int total = normalized.length() - 1;
        double repetitionRate = total == 0 ? 0d : 1d - ((double) frequency.size() / total);
        return new FingerprintValue(simhash, total, repetitionRate, repeatedFragments(normalized, frequency));
    }

    /**
     * 文内重复片段 top5（任务书 #63 4.3 定死算法）：count≥2 的 shingle 按首次出现顺序标记原
     * （规范化后）文本下标，相邻下标连续则合并为片段；最短 4 字不足丢弃；按 (count-1)×片段长度
     * 降序取 top5。仅展示用途，不参与判定（重复率口径不变）。
     */
    private static List<String> repeatedFragments(String normalized, Map<String, Integer> frequency) {
        int shingleCount = normalized.length() - 1;
        boolean[] selected = new boolean[shingleCount];
        for (int i = 0; i < shingleCount; i++) {
            selected[i] = frequency.getOrDefault(normalized.substring(i, i + 2), 0) >= 2;
        }
        record Candidate(String text, int weight) {}
        List<Candidate> candidates = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= shingleCount; i++) {
            boolean active = i < shingleCount && selected[i];
            if (active && start < 0) {
                start = i;
            }
            if (!active && start >= 0) {
                String fragment = normalized.substring(start, i + 1);
                int count = 0;
                for (int j = start; j < i; j++) {
                    count = Math.max(count, frequency.get(normalized.substring(j, j + 2)));
                }
                if (fragment.length() >= 4) {
                    candidates.add(new Candidate(fragment, (count - 1) * fragment.length()));
                }
                start = -1;
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingInt(Candidate::weight).reversed())
                .limit(5)
                .map(Candidate::text)
                .toList();
    }

    public static int hammingDistance(long left, long right) {
        return Long.bitCount(left ^ right);
    }

    private Optional<Match> closest(long hash, List<Fingerprint> candidates) {
        Match best = null;
        int maxDistance = properties.getOriginality().getMaxHammingDistance();
        for (Fingerprint candidate : candidates) {
            int distance = hammingDistance(hash, candidate.simhash());
            if (distance <= maxDistance && (best == null || distance < best.distance())) {
                best = new Match(candidate, distance);
            }
        }
        return Optional.ofNullable(best);
    }

    private List<Finding> lowOriginality(FingerprintValue value) {
        if (value.repetitionRate() <= properties.getOriginality().getMaxRepetitionRate()) return List.of();
        int percent = (int) Math.round(value.repetitionRate() * 100d);
        return List.of(new Finding(
                "low_originality", "low", percent + "% 文内重复", -1,
                "文内重复片段较多，建议补充具体体验、事实和差异化细节", false,
                value.repeatedFragments()));
    }

    /**
     * duplicate_content 文案四分支（任务书 #63 4.2 定死文案，取首个命中）：同 application → 同 task
     * → 2 小时内 → 跨任务撞文。前三支为同源降噪（本文/同任务迭代属正常），跨任务才展示元信息；
     * 全程不回原文（指纹表刻意不存原文，方案 C）。
     */
    private static Finding duplicateFinding(Match match, Context context, Instant now) {
        int similarity = (int) Math.round((64d - match.distance()) / 64d * 100d);
        Fingerprint row = match.fingerprint();
        String matchText;
        String advice;
        if (row.applicationId() != null && row.applicationId().equals(context.applicationId())) {
            matchText = "与本文早期版本相似(相似度 " + similarity + "%)";
            advice = "本文迭代产生的相似属正常;若需进一步差异化,补充新的素材与细节后再生成";
        } else if (row.taskId() != null && row.taskId().equals(context.taskId())) {
            matchText = "与同任务早期版本相似(相似度 " + similarity + "%)";
            advice = "本文迭代产生的相似属正常;若需进一步差异化,补充新的素材与细节后再生成";
        } else if (row.createdAt() != null && Duration.between(row.createdAt(), now).compareTo(SAME_SOURCE_WINDOW) <= 0) {
            matchText = "疑似本文早期检查版本(相似度 " + similarity + "%)";
            advice = "本文迭代产生的相似属正常;若需进一步差异化,补充新的素材与细节后再生成";
        } else {
            String time = row.createdAt() == null ? "未知时间" : BRANCH_TIME_FORMAT.format(row.createdAt());
            String platform = row.platform() == null ? "未知平台" : row.platform();
            matchText = "与 " + time + " 的" + platform + "创作相似(相似度 " + similarity + "%)";
            advice = "与既有创作高度相似,建议重写结构并补充原创信息";
        }
        return new Finding("duplicate_content", "medium", matchText, -1, advice, false);
    }

    private static String normalize(String text) {
        if (text == null) return "";
        StringBuilder result = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints()
                .filter(Character::isLetterOrDigit)
                .forEach(result::appendCodePoint);
        return result.toString();
    }

    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= item & 0xffL;
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    public record Context(
            String ownerAccountId, String taskId, String applicationId,
            String platform, String contentForm, String sourceKind) {
        public Context {
            sourceKind = "manual".equals(sourceKind) ? "manual" : "generation";
        }
    }
    public record FingerprintValue(
            long simhash, int shingleCount, double repetitionRate, List<String> repeatedFragments) {}
    private record Match(Fingerprint fingerprint, int distance) {}
}
