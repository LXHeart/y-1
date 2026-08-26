package com.grassland.intelligence.ai.controlplane;

import java.util.UUID;

/**
 * 模型配置 + 其凭据的联表投影（任务书 #47 S2）。
 *
 * <p>为什么是投影而不给 {@link PlatformModelConfig} 加字段：那个 record 有 9 处构造点（含 seeder 与
 * 三个测试），加字段会把改动面扩散到与本切片无关的地方。运行时只有 {@code resolve} 需要凭据，
 * 一个联表投影正好覆盖，且避免每次 Run 多一次往返。
 *
 * <p>{@code credentialId} 可为 null——收口迁移（V50 的 {@code SET NOT NULL}）之前，理论上仍可能存在
 * 未挂凭据的行；凭据被停用时 LEFT JOIN 也会给出 null。两种情况下 {@link #effectiveBaseUrl()} 为 null，
 * 由执行层判定不可用（按 capability 503），而不是拿一个猜测的地址继续跑。
 */
public record PlatformModelWithCredential(
        PlatformModelConfig config,
        UUID credentialId,
        String credentialBaseUrl,
        String credentialEncryptedKey,
        Long credentialVersion) {

    /**
     * 执行时该用的 baseUrl：凭据优先（D2 凭据是目的地真相源），无凭据时回落配置列。
     *
     * <p>回落分支是<b>过渡期必需</b>而非冗余：{@code platform_model_config.base_url} 仍是 NOT NULL，
     * 存量行可能没有配套凭据（V46 之后、写入侧上线之前的窗口）。硬切到凭据会让这些行 baseUrl 变 null
     * 并在运行时 502。V52 DROP COLUMN 时这一分支随 {@code config.baseUrl()} 一起消失。
     */
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
