package com.grassland.identity.identityprofile;

/**
 * 开通身份的请求体。草场身份域 Slice 2G。
 *
 * <p>{@code type} 必须是 {@link IdentityType} 合法值（merchant/recommender），compact constructor 内校验。
 * {@code organizationId} 可选：商家身份可关联 org（给则服务端校验为该 org owner），推荐官为空。
 */
public record OpenIdentityRequest(String type, String organizationId) {
    public OpenIdentityRequest {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type is required");
        }
        IdentityType.fromDb(type); // 校验为已知身份类型，非法抛 IllegalArgumentException
    }
}
