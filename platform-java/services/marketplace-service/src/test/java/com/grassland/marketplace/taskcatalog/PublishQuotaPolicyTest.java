package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link PublishQuotaPolicy#maxActiveTasks} 单测（草场 Epic 4 Slice 4B，锁定与 identity PermissionQuotaPolicy 同值防漂移）。 */
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
}
