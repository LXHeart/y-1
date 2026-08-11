package com.grassland.identity.auth;

/** 统一账号注册契约；initialIdentity 决定注册事务内创建的首个业务身份。 */
public record RegisterRequest(
        String email,
        String password,
        String confirmPassword,
        String displayName,
        String verificationCode,
        String initialIdentity
) {}
