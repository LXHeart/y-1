package com.grassland.identity.membership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link MembershipRole} 单元测试：DB 映射、非法值、单调权限比较（OWNER 最高）。 */
class MembershipRoleTest {

    @Test
    void fromDbMapsKnownValuesCaseInsensitive() {
        assertThat(MembershipRole.fromDb("owner")).isEqualTo(MembershipRole.OWNER);
        assertThat(MembershipRole.fromDb("ADMIN")).isEqualTo(MembershipRole.ADMIN);
        assertThat(MembershipRole.fromDb("Member")).isEqualTo(MembershipRole.MEMBER);
    }

    @Test
    void fromDbRejectsUnknown() {
        assertThatThrownBy(() -> MembershipRole.fromDb("superadmin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MembershipRole.fromDb(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isAtLeastOwnerIsHighest() {
        assertThat(MembershipRole.OWNER.isAtLeast(MembershipRole.MEMBER)).isTrue();
        assertThat(MembershipRole.OWNER.isAtLeast(MembershipRole.ADMIN)).isTrue();
        assertThat(MembershipRole.MEMBER.isAtLeast(MembershipRole.OWNER)).isFalse();
        assertThat(MembershipRole.ADMIN.isAtLeast(MembershipRole.OWNER)).isFalse();
        assertThat(MembershipRole.ADMIN.isAtLeast(MembershipRole.MEMBER)).isTrue();
        assertThat(MembershipRole.ADMIN.isAtLeast(MembershipRole.ADMIN)).isTrue();
    }
}
