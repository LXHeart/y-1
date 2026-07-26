package com.grassland.finance.escrow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link FinanceTxQuotaPolicy} 单测（草场 D-05 硬限额执行 finance 侧）。
 * <b>锁定与 identity {@code PermissionQuotaPolicy} / marketplace {@code PublishQuotaPolicy} 同值防漂移</b>。
 */
class FinanceTxQuotaPolicyTest {

    @Test
    void financeTransactionTierHasTenMillionCap() {
        assertThat(FinanceTxQuotaPolicy.maxTxAmountCents("finance_transaction")).isEqualTo(10_000_000L);
    }

    @Test
    void nonTransactionTiersHaveZeroCap() {
        assertThat(FinanceTxQuotaPolicy.maxTxAmountCents("basic_publish")).isEqualTo(0L);
        assertThat(FinanceTxQuotaPolicy.maxTxAmountCents("draft")).isEqualTo(0L);
    }

    @Test
    void nullOrUnknownTierIsConservativelyZero() {
        assertThat(FinanceTxQuotaPolicy.maxTxAmountCents(null)).isEqualTo(0L);
        assertThat(FinanceTxQuotaPolicy.maxTxAmountCents("")).isEqualTo(0L);
        assertThat(FinanceTxQuotaPolicy.maxTxAmountCents("bogus_tier")).isEqualTo(0L);
    }

    @Test
    void tierParsingIsCaseInsensitive() {
        assertThat(FinanceTxQuotaPolicy.maxTxAmountCents("FINANCE_TRANSACTION")).isEqualTo(10_000_000L);
        assertThat(FinanceTxQuotaPolicy.maxTxAmountCents("  Finance_Transaction  ")).isEqualTo(10_000_000L);
    }

    @Test
    void isWithinLimitBoundaries() {
        assertThat(FinanceTxQuotaPolicy.isWithinLimit("finance_transaction", 10_000_000L)).isTrue();   // 等于上限 → 允许
        assertThat(FinanceTxQuotaPolicy.isWithinLimit("finance_transaction", 10_000_001L)).isFalse();  // 超一分 → 拒绝
        assertThat(FinanceTxQuotaPolicy.isWithinLimit("basic_publish", 1L)).isFalse();                 // 无交易权限
    }
}
