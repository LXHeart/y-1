package com.grassland.identity.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link PermissionTier} 单元测试：DB 映射、非法值、单调比较。 */
class PermissionTierTest {

    @Test
    void fromDbMapsKnownValuesCaseInsensitive() {
        assertThat(PermissionTier.fromDb("draft")).isEqualTo(PermissionTier.DRAFT);
        assertThat(PermissionTier.fromDb("BASIC_PUBLISH")).isEqualTo(PermissionTier.BASIC_PUBLISH);
        assertThat(PermissionTier.fromDb("Finance_Transaction")).isEqualTo(PermissionTier.FINANCE_TRANSACTION);
    }

    @Test
    void dbValueIsLowerCase() {
        assertThat(PermissionTier.DRAFT.dbValue()).isEqualTo("draft");
        assertThat(PermissionTier.BASIC_PUBLISH.dbValue()).isEqualTo("basic_publish");
        assertThat(PermissionTier.FINANCE_TRANSACTION.dbValue()).isEqualTo("finance_transaction");
    }

    @Test
    void fromDbRejectsUnknown() {
        assertThatThrownBy(() -> PermissionTier.fromDb("gold")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PermissionTier.fromDb(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isAtLeastIsMonotonicByOrdinal() {
        assertThat(PermissionTier.FINANCE_TRANSACTION.isAtLeast(PermissionTier.DRAFT)).isTrue();
        assertThat(PermissionTier.DRAFT.isAtLeast(PermissionTier.FINANCE_TRANSACTION)).isFalse();
        assertThat(PermissionTier.BASIC_PUBLISH.isAtLeast(PermissionTier.BASIC_PUBLISH)).isTrue();
        assertThat(PermissionTier.FINANCE_TRANSACTION.isAtLeast(PermissionTier.BASIC_PUBLISH)).isTrue();
    }
}
