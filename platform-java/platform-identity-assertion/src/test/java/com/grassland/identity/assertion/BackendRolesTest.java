package com.grassland.identity.assertion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link BackendRoles} 解析与超集判定单测（GL-P2-ADMIN-001 RBAC 地基）。
 */
class BackendRolesTest {

    @Test
    void fromClaimParsesCommaSeparatedValues() {
        Set<BackendRole> roles = BackendRoles.fromClaim("platform_admin,content_reviewer");
        assertThat(roles).containsExactly(BackendRole.PLATFORM_ADMIN, BackendRole.CONTENT_REVIEWER);
    }

    @Test
    void fromClaimHandlesNullAndBlank() {
        assertThat(BackendRoles.fromClaim(null)).isEmpty();
        assertThat(BackendRoles.fromClaim("")).isEmpty();
        assertThat(BackendRoles.fromClaim("  ")).isEmpty();
    }

    @Test
    void fromClaimIgnoresUnknownValuesForForwardCompat() {
        // 未知角色（未来新增）被忽略，不抛错
        Set<BackendRole> roles = BackendRoles.fromClaim("content_reviewer,future_role,another");
        assertThat(roles).containsExactly(BackendRole.CONTENT_REVIEWER);
    }

    @Test
    void fromClaimIsCaseInsensitiveAndTrimsWhitespace() {
        Set<BackendRole> roles = BackendRoles.fromClaim(" PLATFORM_ADMIN , Customer_Service ");
        assertThat(roles).contains(BackendRole.PLATFORM_ADMIN, BackendRole.CUSTOMER_SERVICE);
    }

    @Test
    void hasAnyReturnsTrueWhenSpecificRoleHeld() {
        assertThat(BackendRoles.hasAny("content_reviewer", BackendRole.CONTENT_REVIEWER)).isTrue();
        assertThat(BackendRoles.hasAny("content_reviewer,finance", BackendRole.FINANCE)).isTrue();
    }

    @Test
    void hasAnyReturnsFalseWhenRoleNotHeld() {
        assertThat(BackendRoles.hasAny("content_reviewer", BackendRole.FINANCE)).isFalse();
        assertThat(BackendRoles.hasAny("", BackendRole.CONTENT_REVIEWER)).isFalse();
        assertThat(BackendRoles.hasAny("content_reviewer")).isFalse();
    }

    @Test
    void hasAnyPlatformAdminIsSupersetAndAlwaysPasses() {
        // PLATFORM_ADMIN 超集：持有它即视为持有所有角色
        assertThat(BackendRoles.hasAny("platform_admin", BackendRole.RISK)).isTrue();
        assertThat(BackendRoles.hasAny("content_reviewer,platform_admin", BackendRole.AI_ADMIN)).isTrue();
    }

    @Test
    void fromDbRoundTripsAllRoles() {
        for (BackendRole role : BackendRole.values()) {
            assertThat(BackendRole.fromDb(role.dbValue())).isEqualTo(role);
        }
    }

    @Test
    void fromDbReturnsNullForUnknown() {
        assertThat(BackendRole.fromDb("admin")).isNull();      // legacy 值不在新枚举
        assertThat(BackendRole.fromDb(null)).isNull();
        assertThat(BackendRole.fromDb("nonexistent")).isNull();
    }
}
