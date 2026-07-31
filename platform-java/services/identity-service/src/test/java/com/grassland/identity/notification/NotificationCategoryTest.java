package com.grassland.identity.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** {@link NotificationCategory} db 编解码。草场 Slice 12 Stage 1。 */
class NotificationCategoryTest {

    @Test
    void dbValueIsLowerCase() {
        assertThat(NotificationCategory.INVITATION.dbValue()).isEqualTo("invitation");
        assertThat(NotificationCategory.ENGAGEMENT.dbValue()).isEqualTo("engagement");
    }

    @Test
    void fromDbIsCaseInsensitive() {
        assertThat(NotificationCategory.fromDb("INVITATION")).isEqualTo(NotificationCategory.INVITATION);
        assertThat(NotificationCategory.fromDb("Wallet")).isEqualTo(NotificationCategory.WALLET);
    }

    @Test
    void fromDbRejectsNullAndUnknown() {
        assertThatThrownBy(() -> NotificationCategory.fromDb(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationCategory.fromDb("bogus"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus");
    }
}
