package com.grassland.intelligence.homepage;

import java.time.Instant;

/**
 * 首页热点的平台级数据源配置（任务书 #47 S7b / D18①）。单行表 {@code homepage_hot_config}。
 *
 * <p>热点是匿名可访问的平台数据，数据源不该按用户各配一份——{@code HomepageHotService} 的
 * ALAPI 缓存按 token 分键，每用户一份缓存本身就与「同一缓存窗口标签稳定」（任务书 #35）冲突。
 *
 * <p>{@code alapiTokenEncrypted} 是信封加密密文，解密在 {@code HomepageHotService} 按需进行；
 * 对外只回 {@link #alapiTokenMasked}。{@code provider=60s} 时不需要 token。
 */
public record HomepageHotConfig(
        String provider,
        String alapiTokenEncrypted,
        String alapiTokenKeyVersion,
        String alapiTokenMasked,
        long version,
        String updatedBy,
        Instant updatedAt) {

    public static final String PROVIDER_60S = "60s";
    public static final String PROVIDER_ALAPI = "alapi";

    /** 无配置行时的默认视图：沿用改造前的硬编码默认，version=0 供区分「未配置」。 */
    public static HomepageHotConfig platformDefault() {
        return new HomepageHotConfig(PROVIDER_60S, null, null, null, 0L, null, null);
    }

    public boolean hasAlapiToken() {
        return alapiTokenEncrypted != null && !alapiTokenEncrypted.isBlank();
    }
}
