package com.grassland.identity.auth;

/**
 * 改密请求（任务书 #48 D3）。{@code currentPassword} 在首登强制改密形态下可空。
 * 包装类型缺省即 null（Jackson record primitive 陷阱规避）。
 */
public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
