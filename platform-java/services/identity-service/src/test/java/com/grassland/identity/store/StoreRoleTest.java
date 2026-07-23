package com.grassland.identity.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link StoreRole} 单元测试：DB 映射、非法值、单调权限比较（MANAGER 最高）。 */
class StoreRoleTest {

    @Test
    void fromDbMapsKnownValuesCaseInsensitive() {
        assertThat(StoreRole.fromDb("manager")).isEqualTo(StoreRole.MANAGER);
        assertThat(StoreRole.fromDb("STAFF")).isEqualTo(StoreRole.STAFF);
    }

    @Test
    void fromDbRejectsUnknown() {
        assertThatThrownBy(() -> StoreRole.fromDb("owner")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StoreRole.fromDb(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isAtLeastManagerIsHighest() {
        assertThat(StoreRole.MANAGER.isAtLeast(StoreRole.STAFF)).isTrue();
        assertThat(StoreRole.STAFF.isAtLeast(StoreRole.MANAGER)).isFalse();
        assertThat(StoreRole.MANAGER.isAtLeast(StoreRole.MANAGER)).isTrue();
    }
}
