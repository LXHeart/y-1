package com.grassland.identity.identityprofile;

/**
 * 重认证请求（MFA，HLD §11.2）。本轮以密码作为第二因子；
 * 真正的多因子（TOTP / 短信码）落地时在此加可选字段，端点契约不变。
 */
public record ReauthenticateRequest(String password) {
    public ReauthenticateRequest {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("password is required");
        }
    }
}
