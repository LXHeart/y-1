package com.grassland.identity.auth;

/**
 * 统一账号注册契约。注册即推荐官（2026-09-04 身份模型改版）：注册事务内一律创建 recommender 身份档案；商家身份由治理台初始化（任务书
 * #71 D1/D2）。 旧客户端多发的初始身份字段为未知 JSON 字段，Jackson 默认忽略不报错。
 */
public record RegisterRequest(String email, String password, String confirmPassword, String displayName,
		String verificationCode) {
}
