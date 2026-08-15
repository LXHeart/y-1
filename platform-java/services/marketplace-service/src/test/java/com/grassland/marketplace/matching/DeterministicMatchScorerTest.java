package com.grassland.marketplace.matching;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.reputation.Lv5Admission;
import com.grassland.marketplace.reputation.ReputationPolicy;
import com.grassland.marketplace.reputation.ReputationSnapshot;
import com.grassland.marketplace.reputation.ReputationStats;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DeterministicMatchScorerTest {

    private static final Instant NOW = Instant.parse("2026-08-15T12:00:00Z");
    private final DeterministicMatchScorer scorer = new DeterministicMatchScorer();

    @Test
    void scoresAllSixDimensionsAtPublishedBoundaries() {
        ReputationStats stats = new ReputationStats(
                10, 9, 1, 0, 0, 4, 4.5, 48 * 3600.0, NOW.minusSeconds(7 * 86400));
        ReputationSnapshot snapshot = snapshot("00000000-0000-0000-0000-000000000001", stats);

        RecommenderMatch result = scorer.score(
                new MatchingCandidate(snapshot.accountId(), 2, null), snapshot, NOW);

        assertThat(result.dimensions()).extracting(MatchDimension::key).containsExactly(
                "platformFit", "level", "completionRate", "averageRating", "responseSpeed", "recentActivity");
        assertThat(result.dimensionScore("platformFit")).isEqualTo(22);
        assertThat(result.dimensionScore("level")).isEqualTo(4);
        assertThat(result.dimensionScore("completionRate")).isEqualTo(20);
        assertThat(result.dimensionScore("averageRating")).isEqualTo(14);
        assertThat(result.dimensionScore("responseSpeed")).isEqualTo(8);
        assertThat(result.dimensionScore("recentActivity")).isEqualTo(10);
        assertThat(result.totalScore()).isEqualTo(78);
        assertThat(result.reasons()).hasSize(3);
    }

    @Test
    void missingSamplesStayExplicitAndDoNotReceiveNeutralPoints() {
        ReputationStats stats = new ReputationStats(0, 0, 0, 0, 0, 0, null, null, null);
        ReputationSnapshot snapshot = snapshot("00000000-0000-0000-0000-000000000002", stats);

        RecommenderMatch result = scorer.score(
                new MatchingCandidate(snapshot.accountId(), 0, null), snapshot, NOW);

        assertThat(result.totalScore()).isZero();
        assertThat(result.dimensions()).allSatisfy(dimension -> assertThat(dimension.score()).isZero());
        assertThat(result.dimensionScore("averageRating")).isZero();
        assertThat(result.dimensionScore("responseSpeed")).isZero();
        assertThat(result.reasons()).containsExactly("有全站报名历史，可继续观察履约表现");
    }

    @Test
    void platformAndTimeBucketsAreCapped() {
        ReputationStats stats = new ReputationStats(
                1, 0, 0, 0, 0, 1, 5.0, 8 * 86400.0, NOW.minusSeconds(181 * 86400));
        ReputationSnapshot snapshot = snapshot("00000000-0000-0000-0000-000000000003", stats);

        RecommenderMatch result = scorer.score(
                new MatchingCandidate(snapshot.accountId(), 99, null), snapshot, NOW);

        assertThat(result.dimensionScore("platformFit")).isEqualTo(30);
        assertThat(result.dimensionScore("responseSpeed")).isEqualTo(1);
        assertThat(result.dimensionScore("recentActivity")).isZero();
        assertThat(result.totalScore()).isLessThanOrEqualTo(100);
    }

    private static ReputationSnapshot snapshot(String accountId, ReputationStats stats) {
        ReputationPolicy policy = ReputationPolicy.defaults();
        return new ReputationSnapshot(accountId, stats, policy, Lv5Admission.none(accountId),
                policy.evaluate(stats, false, NOW));
    }
}
