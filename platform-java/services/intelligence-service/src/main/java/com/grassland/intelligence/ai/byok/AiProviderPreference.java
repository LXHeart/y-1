package com.grassland.intelligence.ai.byok;

import java.time.Instant;

/**
 * 个人 BYOK 开关（任务书 #47 D11–D14）。按 capability 一行。
 *
 * <p><b>无行即视为 {@code useOwnKey=true}</b>（D14）——与改造前「有个人密钥就用它」逐字节一致，
 * 存量 BYOK 用户零感知。只有用户显式关闭才写行。
 *
 * <p>{@code useOwnKey=false} 不删也不停用密钥（D12）：密文照旧留在 {@code ai_provider_key}，
 * 只是不参与路由。开关的价值在可逆。
 */
public record AiProviderPreference(
        String accountId,
        String capability,
        boolean useOwnKey,
        long version,
        Instant updatedAt) {

    /** 未配置时的默认视图（version=0 供前端区分「未配置」与「显式设为 true」）。 */
    public static AiProviderPreference defaultFor(String accountId, String capability) {
        return new AiProviderPreference(accountId, capability, true, 0L, null);
    }
}
