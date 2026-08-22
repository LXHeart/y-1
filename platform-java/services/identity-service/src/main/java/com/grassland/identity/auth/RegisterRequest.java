package com.grassland.identity.auth;

/**
 * 统一账号注册契约。initialIdentity 可选：不传 = 只建统一账号（裸账号，业务身份
 * 登录后在工作台按引导开通）；传 merchant/recommender 则注册事务内直接创建首个身份档案（旧契约）。
 */
public record RegisterRequest(
        String email,
        String password,
        String confirmPassword,
        String displayName,
        String verificationCode,
        String initialIdentity
) {}
