package com.grassland.identity.identityprofile;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 活动身份的多设备并发策略。0 表示不限制，保持既有多设备并存语义。 */
@ConfigurationProperties(prefix = "identity.active-identity.session-policy")
public class IdentitySessionPolicyProperties {

    private int maxActivePerAccount;

    public int getMaxActivePerAccount() {
        return maxActivePerAccount;
    }

    public void setMaxActivePerAccount(int maxActivePerAccount) {
        if (maxActivePerAccount < 0) {
            throw new IllegalArgumentException("max-active-per-account must be >= 0");
        }
        this.maxActivePerAccount = maxActivePerAccount;
    }

    public boolean limited() {
        return maxActivePerAccount > 0;
    }
}
