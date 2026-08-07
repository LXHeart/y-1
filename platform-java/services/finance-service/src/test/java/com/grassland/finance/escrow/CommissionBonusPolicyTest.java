package com.grassland.finance.escrow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CommissionBonusPolicyTest {

    @Test
    void calculatesFloorRoundedBonusWithoutIntermediateOverflow() {
        assertThat(CommissionBonusPolicy.calculateCents(1_001L, 300)).isEqualTo(30L);
        assertThat(CommissionBonusPolicy.calculateCents(Long.MAX_VALUE, 10_000))
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsInvalidAmountAndBasisPoints() {
        assertThatThrownBy(() -> CommissionBonusPolicy.calculateCents(0, 300))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommissionBonusPolicy.calculateCents(100, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CommissionBonusPolicy.calculateCents(100, 10_001))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
