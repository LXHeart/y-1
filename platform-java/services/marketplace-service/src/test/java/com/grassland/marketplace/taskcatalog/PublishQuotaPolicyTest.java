package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * {@link PublishQuotaPolicy} 单测（草场 Epic 4 Slice 4B + D-05 硬限额执行）。
 * <b>锁定三项额度与 identity {@code PermissionQuotaPolicy.quotaFor} 同值防漂移</b>——任一处改值，本测试须同步。
 */
class PublishQuotaPolicyTest {

    @Test
    void draftAllowsNothing() {
        assertThat(PublishQuotaPolicy.maxActiveTasks(MerchantTier.DRAFT)).isEqualTo(0);
    }

    @Test
    void basicPublishAllowsFive() {
        assertThat(PublishQuotaPolicy.maxActiveTasks(MerchantTier.BASIC_PUBLISH)).isEqualTo(5);
    }

    @Test
    void financeTransactionAllowsFifty() {
        assertThat(PublishQuotaPolicy.maxActiveTasks(MerchantTier.FINANCE_TRANSACTION)).isEqualTo(50);
    }

    // ---------- D-05：月度额度 ----------

    @Test
    void monthlyQuotaMatchesIdentityPolicy() {
        assertThat(PublishQuotaPolicy.maxMonthlyTasks(MerchantTier.DRAFT)).isEqualTo(0);
        assertThat(PublishQuotaPolicy.maxMonthlyTasks(MerchantTier.BASIC_PUBLISH)).isEqualTo(20);
        assertThat(PublishQuotaPolicy.maxMonthlyTasks(MerchantTier.FINANCE_TRANSACTION)).isEqualTo(500);
    }

    // ---------- D-05：单笔赏金上限 ----------

    @Test
    void txAmountQuotaMatchesIdentityPolicy() {
        assertThat(PublishQuotaPolicy.maxTxAmountCents(MerchantTier.DRAFT)).isEqualTo(0L);
        assertThat(PublishQuotaPolicy.maxTxAmountCents(MerchantTier.BASIC_PUBLISH)).isEqualTo(0L);
        assertThat(PublishQuotaPolicy.maxTxAmountCents(MerchantTier.FINANCE_TRANSACTION)).isEqualTo(10_000_000L);
    }

    @Test
    void onlyFinanceTierCanPublishBountyTasks() {
        // BASIC_PUBLISH 可发布任务但不可发资金型（maxTx=0 → controller 403）
        assertThat(PublishQuotaPolicy.maxActiveTasks(MerchantTier.BASIC_PUBLISH)).isGreaterThan(0);
        assertThat(PublishQuotaPolicy.maxTxAmountCents(MerchantTier.BASIC_PUBLISH)).isEqualTo(0L);
    }
}
