package com.grassland.intelligence.contentsafety;

import com.grassland.intelligence.contentsafety.ContentFingerprintRepository.Fingerprint;
import com.grassland.intelligence.contentsafety.SafetyReport.Finding;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** Deterministic SimHash duplicate detection and intra-text repetition advisory. */
@Service
public class OriginalityChecker {

    private static final Logger log = LoggerFactory.getLogger(OriginalityChecker.class);
    private static final int MAX_HAMMING_DISTANCE = 16;
    private final ContentFingerprintRepository repository;

    public OriginalityChecker(ContentFingerprintRepository repository) {
        this.repository = repository;
    }

    public Mono<List<Finding>> checkAndRecord(String text, Context context) {
        FingerprintValue value = fingerprint(text);
        if (context == null || context.ownerAccountId() == null || value.shingleCount() == 0) {
            return Mono.just(lowOriginality(value));
        }
        Mono<List<Fingerprint>> candidates = repository.findCandidates(
                        context.ownerAccountId(), context.taskId(), Instant.now().minus(Duration.ofDays(90)))
                .collectList()
                .onErrorResume(error -> {
                    log.warn("originality candidate lookup failed", error);
                    return Mono.just(List.of());
                });
        return candidates.flatMap(existing -> {
            List<Finding> findings = new ArrayList<>(lowOriginality(value));
            closest(value.simhash(), existing).ifPresent(match -> findings.add(duplicateFinding(match)));
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
        if (normalized.length() < 2) return new FingerprintValue(0L, 0, 0d);
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
        return new FingerprintValue(simhash, total, repetitionRate);
    }

    public static int hammingDistance(long left, long right) {
        return Long.bitCount(left ^ right);
    }

    private static java.util.Optional<Match> closest(long hash, List<Fingerprint> candidates) {
        Match best = null;
        for (Fingerprint candidate : candidates) {
            int distance = hammingDistance(hash, candidate.simhash());
            if (distance <= MAX_HAMMING_DISTANCE && (best == null || distance < best.distance())) {
                best = new Match(candidate, distance);
            }
        }
        return java.util.Optional.ofNullable(best);
    }

    private static List<Finding> lowOriginality(FingerprintValue value) {
        if (value.repetitionRate() <= 0.30d) return List.of();
        int percent = (int) Math.round(value.repetitionRate() * 100d);
        return List.of(new Finding(
                "low_originality", "low", percent + "% 文内重复", -1,
                "文内重复片段较多，建议补充具体体验、事实和差异化细节", false));
    }

    private static Finding duplicateFinding(Match match) {
        int similarity = (int) Math.round((64d - match.distance()) / 64d * 100d);
        Fingerprint row = match.fingerprint();
        String time = row.createdAt() == null ? "未知时间" : row.createdAt().toString();
        String platform = row.platform() == null ? "未知平台" : row.platform();
        return new Finding(
                "duplicate_content", "medium",
                "相似度 " + similarity + "% · " + platform + " · " + time,
                -1, "与既有创作高度相似，建议重写结构并补充原创信息", false);
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
    public record FingerprintValue(long simhash, int shingleCount, double repetitionRate) {}
    private record Match(Fingerprint fingerprint, int distance) {}
}
