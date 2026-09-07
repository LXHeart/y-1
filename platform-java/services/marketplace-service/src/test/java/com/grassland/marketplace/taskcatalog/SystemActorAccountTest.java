package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 任务书 #90 C90-04 / D90-05：系统操作者配置校验——缺失或非法 UUID 必须启动失败，禁止回退 null。
 */
class SystemActorAccountTest {

    private static final String VALID = "00000000-0000-0000-0000-000000000901";

    @Test
    void acceptsConfiguredFixedUuid() {
        SystemActorAccount account = new SystemActorAccount(VALID, "");
        assertThat(account.accountId()).isEqualTo(VALID);
        assertThat(account.systemCaller().accountId()).isEqualTo(VALID);
        assertThat(account.systemCaller().isMerchant()).isFalse();  // 不可冒充商家
    }

    @Test
    void blankConfigFailsStartup() {
        assertThatThrownBy(() -> new SystemActorAccount("", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("system-actor-account-id");
    }

    @Test
    void invalidUuidFailsStartup() {
        assertThatThrownBy(() -> new SystemActorAccount("not-a-uuid", ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    void envVarIsFallbackWhenPropertyBlank() {
        SystemActorAccount account = new SystemActorAccount(" ", VALID);
        assertThat(account.accountId()).isEqualTo(VALID);
    }
}
