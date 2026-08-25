package com.grassland.intelligence.ai.controlplane;

import java.time.Instant;
import java.util.UUID;

/**
 * 平台通用凭据（任务书 #47 D2）。provider + base_url + 密文密钥同行。
 *
 * <p>{@code platform_model_config} 经 {@code credential_id} 引用本表：「一套通用密钥」= 建一行凭据，
 * 十几行模型配置全部指向它（D3）。key 与 baseUrl 同生死，「换地址忘换密钥」在结构上不可能发生。
 *
 * <p>{@code encryptedKey} 可为 null 且是<b>一等状态</b>：① {@code sandbox} provider 本就不需要密钥；
 * ② 从旧 {@code base_url} 回填出的行先无密钥，执行侧回落 env {@code ai.qwen.api-key}（D1/D8 bootstrap 兜底）。
 * 明文只在写入瞬间存在于进程内，绝不入库/日志/响应——响应只回 {@link #maskedHint}（D5）。
 */
public record PlatformProviderCredential(
        UUID id,
        String name,
        String provider,
        String baseUrl,
        String encryptedKey,
        String keyVersion,
        String maskedHint,
        boolean enabled,
        long version,
        String updatedBy,
        Instant createdAt,
        Instant updatedAt) {

    /** 是否自带密钥；false = sandbox 或走 env 兜底。 */
    public boolean hasKey() {
        return encryptedKey != null && !encryptedKey.isBlank();
    }
}
