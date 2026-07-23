package com.grassland.identity.permission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link PermissionRequestStatus} 单元测试：DB 映射、非法值、终态判定。 */
class PermissionRequestStatusTest {

    @Test
    void fromDbMapsKnownValuesCaseInsensitive() {
        assertThat(PermissionRequestStatus.fromDb("pending")).isEqualTo(PermissionRequestStatus.PENDING);
        assertThat(PermissionRequestStatus.fromDb("UNDER_REVIEW")).isEqualTo(PermissionRequestStatus.UNDER_REVIEW);
        assertThat(PermissionRequestStatus.fromDb("Approved")).isEqualTo(PermissionRequestStatus.APPROVED);
        assertThat(PermissionRequestStatus.fromDb("rejected")).isEqualTo(PermissionRequestStatus.REJECTED);
    }

    @Test
    void fromDbRejectsUnknown() {
        assertThatThrownBy(() -> PermissionRequestStatus.fromDb("draft")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PermissionRequestStatus.fromDb(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isTerminalOnlyForApprovedRejected() {
        assertThat(PermissionRequestStatus.APPROVED.isTerminal()).isTrue();
        assertThat(PermissionRequestStatus.REJECTED.isTerminal()).isTrue();
        assertThat(PermissionRequestStatus.PENDING.isTerminal()).isFalse();
        assertThat(PermissionRequestStatus.UNDER_REVIEW.isTerminal()).isFalse();
    }
}
