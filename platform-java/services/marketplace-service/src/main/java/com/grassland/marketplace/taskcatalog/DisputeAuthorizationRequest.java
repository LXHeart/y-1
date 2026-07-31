package com.grassland.marketplace.taskcatalog;

/**
 * 争议参与方授权请求（trust→marketplace 服务间；草场 Slice 12 安全收口）。
 *
 * <p>由 trust 在已验签终端断言后构造，<b>非浏览器输入</b>：{@code actorAccountId} 是已验证的发起方账号，
 * {@code actorIdentity} 是其活动身份（merchant/recommender）。marketplace 据此判定其是否为该 application 的当事方。
 */
public record DisputeAuthorizationRequest(String actorAccountId, String actorIdentity) {
    public DisputeAuthorizationRequest {
        if (actorAccountId == null || actorAccountId.isBlank()) {
            throw new IllegalArgumentException("actorAccountId is required");
        }
        if (!"merchant".equals(actorIdentity) && !"recommender".equals(actorIdentity)) {
            throw new IllegalArgumentException("actorIdentity must be merchant or recommender");
        }
    }
}
