package com.grassland.identity.identityprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link IdentityAuditAction} 枚举单测（草场身份域 Slice 2I）。 */
class IdentityAuditActionTest {

    @Test
    void dbValueRoundTrip() {
        for (IdentityAuditAction action : IdentityAuditAction.values()) {
            assertThat(IdentityAuditAction.fromDb(action.dbValue())).isEqualTo(action);
        }
    }

    @Test
    void caseInsensitiveAndTrimmed() {
        assertThat(IdentityAuditAction.fromDb("ACTIVATE")).isEqualTo(IdentityAuditAction.ACTIVATE);
        assertThat(IdentityAuditAction.fromDb(" Revoke_Session ")).isEqualTo(IdentityAuditAction.REVOKE_SESSION);
    }

    @Test
    void unknownOrNullThrows() {
        assertThatThrownBy(() -> IdentityAuditAction.fromDb("login")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IdentityAuditAction.fromDb(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
