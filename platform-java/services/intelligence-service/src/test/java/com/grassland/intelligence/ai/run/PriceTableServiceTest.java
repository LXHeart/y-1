package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.embedding.EmbeddingProviderProperties;
import com.grassland.intelligence.speech.SpeechProviderProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class PriceTableServiceTest {

    @Test
    void sandboxModelsArePricedAtZero() {
        PriceTableService prices = new PriceTableService();

        assertThat(prices.isZeroPricedModel("sandbox-speech-v1")).isTrue();
        assertThat(prices.isZeroPricedModel("sandbox-embedding-v1")).isTrue();
        assertThat(prices.isZeroPricedModel("qwen-plus")).isFalse();
        // 首参是 Run 冻结的价目版本；null = 当前 active
        assertThat(prices.calculateCost(null, "sandbox-speech-v1", 0, 0, 0, 0)).isZero();
        assertThat(prices.calculateCost(null, "sandbox-embedding-v1", 400, 0, 0, 0)).isZero();
    }

    @Test
    void unknownPriceTableVersionIsRejectedRatherThanFallingBackToCurrent() {
        // 无 repository 的构造只有内置 v1；查一个不存在的版本必须抛，而不是悄悄用当前表。
        // 回落当前表正是「运营调价后按新价结算旧 Run」这个 bug 的成因。
        PriceTableService prices = new PriceTableService();

        assertThat(prices.getVersion("v1")).isNotNull();
        assertThat(prices.getVersion("v99")).isNull();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> prices.calculateCost("v99", "qwen-plus", 1000, 1000, 0, 0));
    }

    @Test
    void frozenVersionPricesTheRunEvenWhenItDiffersFromCurrent() {
        PriceTableService prices = new PriceTableService();

        // 内置 v1 的 qwen-plus：输入 3 分/1k、输出 6 分/1k
        assertThat(prices.calculateCost("v1", "qwen-plus", 1000, 1000, 0, 0)).isEqualTo(9);
        // 显式传 v1 与传 null（current）在只有一张表时应一致
        assertThat(prices.calculateCost(null, "qwen-plus", 1000, 1000, 0, 0)).isEqualTo(9);
        assertThat(prices.currentVersionLabel()).isEqualTo("v1");
    }

}
