package com.grassland.intelligence.ai.controlplane;

import java.util.UUID;

/**
 * 模型配置 + 其凭据的联表投影（任务书 #47 S2）。
 *
 * <p>为什么是投影而不给 {@link PlatformModelConfig} 加字段：那个 record 有 9 处构造点（含 seeder 与
 * 三个测试），加字段会把改动面扩散到与本切片无关的地方。运行时只有 {@code resolve} 需要凭据，
 * 一个联表投影正好覆盖，且避免每次 Run 多一次往返。
 *
 * <p>{@code credentialId} 可为 null——V47 收 NOT NULL 之前，理论上仍可能存在未挂凭据的行。
 * 此时 {@link #effectiveBaseUrl()} 回落 {@code platform_model_config.base_url}（S2 期两列并存，值相同）。
 */
public record PlatformModelWithCredential(
        PlatformModelConfig config,
        UUID credentialId,
        String credentialBaseUrl,
        String credentialEncryptedKey,
        Long credentialVersion) {

    /** 执行时该用的 baseUrl：凭据优先（D2 凭据是目的地真相源），无凭据回落配置列。 */
    public String effectiveBaseUrl() {
        return credentialBaseUrl != null && !credentialBaseUrl.isBlank()
                ? credentialBaseUrl
                : config.baseUrl();
    }

    /** 凭据是否自带密钥；false 表示 sandbox 或该走 env bootstrap 兜底（D1/D8）。 */
    public boolean hasCredentialKey() {
        return credentialEncryptedKey != null && !credentialEncryptedKey.isBlank();
    }
}
