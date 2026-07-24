package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link MerchantTier#fromDb} 映射单测（草场 Epic 4 Slice 4B，锁定与 identity PermissionTier 的 dbValue 一致）。 */
class MerchantTierTest {

    @Test
    void parsesKnownDbValues() {
        assertThat(MerchantTier.fromDb("draft")).isEqualTo(MerchantTier.DRAFT);
        assertThat(MerchantTier.fromDb("basic_publish")).isEqualTo(MerchantTier.BASIC_PUBLISH);
        assertThat(MerchantTier.fromDb("finance_transaction")).isEqualTo(MerchantTier.FINANCE_TRANSACTION);
    }

    @Test
    void caseInsensitive() {
        assertThat(MerchantTier.fromDb("BASIC_PUBLISH")).isEqualTo(MerchantTier.BASIC_PUBLISH);
        assertThat(MerchantTier.fromDb("  Finance_Transaction ")).isEqualTo(MerchantTier.FINANCE_TRANSACTION);
    }

    @Test
    void nullOrBlankDefaultsToDraft() {
        assertThat(MerchantTier.fromDb(null)).isEqualTo(MerchantTier.DRAFT);
        assertThat(MerchantTier.fromDb("")).isEqualTo(MerchantTier.DRAFT);
        assertThat(MerchantTier.fromDb("   ")).isEqualTo(MerchantTier.DRAFT);
    }

    @Test
    void unknownDefaultsToDraft() {
        assertThat(MerchantTier.fromDb("platinum")).isEqualTo(MerchantTier.DRAFT);
    }
}
