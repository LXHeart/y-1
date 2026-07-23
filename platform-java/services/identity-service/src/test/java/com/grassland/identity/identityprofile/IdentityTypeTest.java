package com.grassland.identity.identityprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link IdentityType} 单元测试：DB 映射、非法值。消费者不在枚举内（隐式 null）。 */
class IdentityTypeTest {

    @Test
    void fromDbMapsKnownValuesCaseInsensitive() {
        assertThat(IdentityType.fromDb("merchant")).isEqualTo(IdentityType.MERCHANT);
        assertThat(IdentityType.fromDb("RECOMMENDER")).isEqualTo(IdentityType.RECOMMENDER);
    }

    @Test
    void dbValueIsLowerCase() {
        assertThat(IdentityType.MERCHANT.dbValue()).isEqualTo("merchant");
        assertThat(IdentityType.RECOMMENDER.dbValue()).isEqualTo("recommender");
    }

    @Test
    void fromDbRejectsUnknown() {
        assertThatThrownBy(() -> IdentityType.fromDb("consumer")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityType.fromDb(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
