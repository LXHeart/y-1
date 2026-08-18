package com.grassland.intelligence.ai.run;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PriceTableServiceTest {

    @Test
    void sandboxModelsArePricedAtZero() {
        PriceTableService prices = new PriceTableService();

        assertThat(prices.calculateCost("sandbox-speech-v1", 0, 0, 0, 0)).isZero();
        assertThat(prices.calculateCost("sandbox-embedding-v1", 400, 0, 0, 0)).isZero();
    }
}
