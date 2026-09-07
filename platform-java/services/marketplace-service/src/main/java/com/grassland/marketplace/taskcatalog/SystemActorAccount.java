package com.grassland.marketplace.taskcatalog;

import com.grassland.marketplace.security.MarketplaceCallerResolver.Caller;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 系统操作者账号（任务书 #90 C90-04 / D90-05）：dispatcher 自动通过等无人值守动作的固定操作者。
 *
 * <p>部署级固定 UUID，经 {@code marketplace.system-actor-account-id}（或环境变量
 * {@code MARKETPLACE_SYSTEM_ACTOR_ACCOUNT_ID}）注入。配置缺失/非法 UUID → <b>启动失败</b>，
 * 禁止回退 null——null actor 会让接受命令账本（{@code task_acceptance_command.actor_account_id NOT NULL}
 * + {@code UNIQUE(actor_account_id, idempotency_key)}）写库即失败，且审计链断头。
 *
 * <p>命令账本查询/幂等去重/审计 reviewed_by 全部统一使用本账号——双实例 dispatcher 并发扫到同一条报名时，
 * 由 {@code UNIQUE(系统操作者, auto-accept:applicationId)} 收敛为一次接受。
 */
@Component
public class SystemActorAccount {

    private final String accountId;

    public SystemActorAccount(@Value("${marketplace.system-actor-account-id:}") String configured,
                              @Value("${MARKETPLACE_SYSTEM_ACTOR_ACCOUNT_ID:}") String fromEnv) {
        String value = configured == null || configured.isBlank() ? fromEnv : configured;
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "marketplace.system-actor-account-id 未配置：系统操作者必须是部署级固定 UUID（D90-05 禁止 null）");
        }
        try {
            this.accountId = UUID.fromString(value.trim()).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("marketplace.system-actor-account-id 不是合法 UUID: " + value);
        }
    }

    /** 固定系统操作者账号 ID（UUID 字符串）。 */
    public String accountId() {
        return accountId;
    }

    /** 系统操作者 Caller（callerKind=system；不入用户身份体系，不可冒充商家/推荐官）。 */
    public Caller systemCaller() {
        return new Caller(accountId, null, null, null, null, "system", null, null);
    }
}
