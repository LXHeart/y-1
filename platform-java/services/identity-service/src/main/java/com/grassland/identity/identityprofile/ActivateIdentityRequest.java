package com.grassland.identity.identityprofile;

/**
 * 激活活动身份的请求体。草场身份域 Slice 2G。
 *
 * <p>{@code type} 必须是 {@link IdentityType} 合法值（merchant/recommender）。激活须该身份已开通，否则服务端 409。
 */
public record ActivateIdentityRequest(String type) {
    public ActivateIdentityRequest {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        IdentityType.fromDb(type); // 校验为已知身份类型
    }
}
