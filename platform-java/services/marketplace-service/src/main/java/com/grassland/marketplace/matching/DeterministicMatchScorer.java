package com.grassland.marketplace.matching;

import com.grassland.marketplace.reputation.ReputationSnapshot;
import com.grassland.marketplace.reputation.ReputationStats;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure, versioned scoring policy. There are deliberately no learned or hidden inputs. */
public final class DeterministicMatchScorer {

    public static final String VERSION = "deterministic-v1";

    public RecommenderMatch score(
            MatchingCandidate candidate, ReputationSnapshot reputation, Instant computedAt) {
        ReputationStats stats = reputation.stats();
        List<MatchDimension> dimensions = List.of(
                platform(candidate.platformEngagementCount()),
                level(reputation.evaluation().effectiveLevel().number()),
                completion(stats),
                rating(stats),
                response(stats),
                activity(stats, computedAt));
        int total = dimensions.stream().mapToInt(MatchDimension::score).sum();
        List<String> reasons = dimensions.stream()
                .filter(dimension -> dimension.score() > 0)
                .sorted(Comparator
                        .comparingDouble((MatchDimension dimension) ->
                                (double) dimension.score() / dimension.maxScore()).reversed()
                        .thenComparing(MatchDimension::key))
                .limit(3).map(MatchDimension::reason).toList();
        if (reasons.isEmpty()) {
            reasons = List.of("有全站报名历史，可继续观察履约表现");
        }
        return new RecommenderMatch(
                candidate.accountId(), total, reputation.evaluation().effectiveLevel().code(),
                reputation.policy().version(), computedAt, dimensions, reasons, candidate.invitation());
    }

    private static MatchDimension platform(int count) {
        int score = count <= 0 ? 0 : count == 1 ? 15 : count == 2 ? 22 : 30;
        return dimension("platformFit", "平台契合度", score, 30,
                evidence("matchingEngagementCount", count),
                count == 0 ? "暂无同平台履约经历" : "同平台有 " + count + " 次履约经历");
    }

    private static MatchDimension level(int number) {
        int normalized = Math.max(1, Math.min(number, 5));
        int score = switch (normalized) {
            case 2 -> 4;
            case 3 -> 8;
            case 4 -> 12;
            case 5 -> 15;
            default -> 0;
        };
        return dimension("level", "等级", score, 15, evidence("level", "Lv" + normalized),
                "当前等级 Lv" + normalized);
    }

    private static MatchDimension completion(ReputationStats stats) {
        double rate = stats.completionRate();
        int score = clamp((int) Math.round(rate * 20), 0, 20);
        Map<String, Object> evidence = evidence("completionRate", round(rate, 4));
        evidence.put("completedCount", stats.completedCount());
        evidence.put("acceptedCount", stats.acceptedCount());
        evidence.put("merchantCancelledCount", stats.merchantCancelledCount());
        return dimension("completionRate", "完成率", score, 20, evidence,
                "历史完成率 " + Math.round(rate * 100) + "%");
    }

    private static MatchDimension rating(ReputationStats stats) {
        Double average = stats.averageScore();
        int score = average == null ? 0 : clamp((int) Math.round(average / 5.0 * 15), 0, 15);
        Map<String, Object> evidence = evidence("averageScore", average == null ? null : round(average, 2));
        evidence.put("ratingCount", stats.ratingCount());
        return dimension("averageRating", "平均评分", score, 15, evidence,
                average == null ? "暂无评分样本" : "平均评分 " + round(average, 2) + " / 5");
    }

    private static MatchDimension response(ReputationStats stats) {
        Double seconds = stats.averageResponseSeconds();
        int score;
        if (seconds == null) score = 0;
        else if (seconds <= Duration.ofHours(24).toSeconds()) score = 10;
        else if (seconds <= Duration.ofHours(48).toSeconds()) score = 8;
        else if (seconds <= Duration.ofHours(72).toSeconds()) score = 6;
        else if (seconds <= Duration.ofDays(7).toSeconds()) score = 3;
        else score = 1;
        return dimension("responseSpeed", "响应速度", score, 10,
                evidence("averageResponseSeconds", seconds == null ? null : Math.round(seconds)),
                seconds == null ? "暂无响应样本" : "平均首次交付响应约 " + humanDuration(seconds));
    }

    private static MatchDimension activity(ReputationStats stats, Instant computedAt) {
        Instant lastActiveAt = stats.lastActiveAt();
        Long ageDays = lastActiveAt == null ? null
                : Math.max(0, Duration.between(lastActiveAt, computedAt).toDays());
        int score = ageDays == null ? 0 : ageDays <= 7 ? 10 : ageDays <= 30 ? 8
                : ageDays <= 90 ? 5 : ageDays <= 180 ? 2 : 0;
        Map<String, Object> evidence = evidence("lastActiveAt", lastActiveAt == null ? null : lastActiveAt.toString());
        evidence.put("ageDays", ageDays);
        return dimension("recentActivity", "近期活跃", score, 10, evidence,
                ageDays == null ? "暂无活跃记录" : ageDays == 0 ? "今天有平台活动" : ageDays + " 天前有平台活动");
    }

    private static MatchDimension dimension(
            String key, String label, int score, int maxScore, Map<String, Object> evidence, String reason) {
        return new MatchDimension(key, label, score, maxScore, evidence, reason);
    }

    private static Map<String, Object> evidence(String key, Object value) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put(key, value);
        return evidence;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    private static String humanDuration(double seconds) {
        long hours = Math.max(1, Math.round(seconds / 3600.0));
        return hours < 48 ? hours + " 小时" : Math.round(hours / 24.0) + " 天";
    }
}
