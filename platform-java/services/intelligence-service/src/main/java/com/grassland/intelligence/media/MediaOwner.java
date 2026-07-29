package com.grassland.intelligence.media;

/**
 * media_reference 归属（草场 Slice 8 第二步）。由断言解析的调用者派生：
 * {@code accountId} 恒非空（媒体写入均经鉴权），{@code organizationId} 可空（推荐官无 org）。
 *
 * @param accountId      归属账号（逻辑引用 app_users，跨服务无 FK）
 * @param organizationId 归属组织，可空
 */
public record MediaOwner(String accountId, String organizationId) {
    public MediaOwner {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("MediaOwner.accountId must not be blank");
        }
    }
}
