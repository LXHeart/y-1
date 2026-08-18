package com.grassland.intelligence.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 任务书 #33：Sandbox Embedding 必须确定性、维度固定、单位范数，非法输入拒绝。 */
class SandboxEmbeddingProviderTest {

    private final SandboxEmbeddingProvider provider = new SandboxEmbeddingProvider();

    private static double norm(List<Double> vector) {
        return Math.sqrt(vector.stream().mapToDouble(Double::doubleValue).map(v -> v * v).sum());
    }

    private double similarity(String left, String right) {
        List<Double> a = provider.embed(left).block().vector();
        List<Double> b = provider.embed(right).block().vector();
        return CosineSimilarity.cosine(a, b);
    }

    @Test
    void embeddingsHaveFixedDimensionsAndUnitNorm() {
        List<Double> vector = provider.embed("开业 门店 咖啡").block().vector();
        assertThat(vector).hasSize(256);
        assertThat(norm(vector)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void sameTextProducesIdenticalVectorsAndDifferentTextDoesNot() {
        List<Double> first = provider.embed("开业 门店 咖啡").block().vector();
        List<Double> second = provider.embed("开业 门店 咖啡").block().vector();
        assertThat(first).containsExactlyElementsOf(second);
        assertThat(provider.embed("宠物 医疗 体检").block().vector())
                .isNotEqualTo(provider.embed("开业 门店 咖啡").block().vector());
    }

    @Test
    void allElementsAreFinite() {
        List<Double> vector = provider.embed("开业 门店 咖啡").block().vector();
        assertThat(vector).allSatisfy(value -> assertThat(Double.isFinite(value)).isTrue());
    }

    @Test
    void sharedTokensScoreHigherThanUnrelatedText() {
        assertThat(similarity("开业 门店", "门店 开业 海报"))
                .isGreaterThan(similarity("开业 门店", "宠物 医疗"));
    }

    @Test
    void providerExposesSandboxMetadata() {
        assertThat(provider.provider()).isEqualTo("sandbox");
        assertThat(provider.algorithmVersion()).isNotBlank();
        assertThat(provider.dimensions()).isEqualTo(256);
        assertThat(provider.embed("开业 门店 咖啡").block().sandbox()).isTrue();
        assertThat(provider.embed("开业 门店 咖啡").block().inputTokens()).isPositive();
    }

    @Test
    void rejectsBlankText() {
        assertThatThrownBy(() -> provider.embed("   ").block())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cosineRejectsInvalidVectors() {
        List<Double> a = provider.embed("开业 门店").block().vector();
        assertThatThrownBy(() -> CosineSimilarity.cosine(a, a.subList(0, 128)))
                .isInstanceOf(IllegalArgumentException.class);
        List<Double> nan = new java.util.ArrayList<>(a);
        nan.set(0, Double.NaN);
        assertThatThrownBy(() -> CosineSimilarity.cosine(a, nan))
                .isInstanceOf(IllegalArgumentException.class);
        List<Double> infinite = new java.util.ArrayList<>(a);
        infinite.set(1, Double.POSITIVE_INFINITY);
        assertThatThrownBy(() -> CosineSimilarity.cosine(a, infinite))
                .isInstanceOf(IllegalArgumentException.class);
        List<Double> zero = java.util.Collections.nCopies(256, 0.0);
        assertThatThrownBy(() -> CosineSimilarity.cosine(a, zero))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
