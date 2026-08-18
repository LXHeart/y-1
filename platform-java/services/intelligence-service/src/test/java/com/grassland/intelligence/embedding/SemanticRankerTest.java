package com.grassland.intelligence.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 任务书 #33：语义得分归一化、60/40 融合、缺向量参与规则与稳定排序键。 */
class SemanticRankerTest {

    @Test
    void combineAppliesSixtyFortyFusion() {
        assertThat(SemanticRanker.combine(90, 70)).isEqualTo(82);
        assertThat(SemanticRanker.combine(100, 0)).isEqualTo(60);
        assertThat(SemanticRanker.combine(0, 100)).isEqualTo(40);
    }

    @Test
    void missingVectorKeepsRuleShareOnly() {
        assertThat(SemanticRanker.rulesOnlyInSemanticRun(70)).isEqualTo(28);
        assertThat(SemanticRanker.rulesOnlyInSemanticRun(0)).isZero();
        assertThat(SemanticRanker.rulesOnlyInSemanticRun(100)).isEqualTo(40);
    }

    @Test
    void cosineMapsToZeroToHundred() {
        assertThat(SemanticRanker.semanticScore(-1.0)).isZero();
        assertThat(SemanticRanker.semanticScore(0.0)).isEqualTo(50);
        assertThat(SemanticRanker.semanticScore(1.0)).isEqualTo(100);
        assertThat(SemanticRanker.semanticScore(-2.0)).isZero();
        assertThat(SemanticRanker.semanticScore(2.0)).isEqualTo(100);
    }

    @Test
    void stableComparatorBreaksTiesDeterministically() {
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Instant older = Instant.parse("2026-01-01T00:00:00Z");
        Instant newer = Instant.parse("2026-02-01T00:00:00Z");
        List<SemanticRanker.Ranked> items = new ArrayList<>(List.of(
                new SemanticRanker.Ranked(second, 82, 70, older),
                new SemanticRanker.Ranked(first, 82, 70, older),
                new SemanticRanker.Ranked(UUID.randomUUID(), 90, 60, older),
                new SemanticRanker.Ranked(UUID.randomUUID(), 82, 80, newer)));
        items.sort(SemanticRanker.order());
        assertThat(items.get(0).finalScore()).isEqualTo(90);
        assertThat(items.get(1).finalScore()).isEqualTo(82);
        assertThat(items.get(1).ruleScore()).isEqualTo(80);
        assertThat(items.get(2).id()).isEqualTo(first);
        assertThat(items.get(3).id()).isEqualTo(second);
    }

    @Test
    void sameUpdatedAtFallsBackToIdAscending() {
        Instant same = Instant.parse("2026-01-01T00:00:00Z");
        UUID first = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000002");
        List<SemanticRanker.Ranked> items = new ArrayList<>(List.of(
                new SemanticRanker.Ranked(second, 82, 70, same),
                new SemanticRanker.Ranked(first, 82, 70, same)));
        items.sort(SemanticRanker.order());
        assertThat(items).extracting(SemanticRanker.Ranked::id).containsExactly(first, second);
    }
}
