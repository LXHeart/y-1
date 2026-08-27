package com.grassland.identity.organization.subaccount;

/**
 * 组织侧代建子账号请求（任务书 #48 D1/D2）。
 *
 * @param role                目标角色：{@code member}（组织普通成员）/ {@code manager}/{@code staff}
 *                            （必须携带 {@code storeId}）
 * @param email               登录邮箱（平台是邮箱注册模型）
 * @param displayName         显示名
 * @param storeId             manager/staff 必填；member 必须为空
 * @param confirmBindExisting 邮箱已存在时的显式关联确认（D5）：true 时不再建新号，
 *                            直接把既有账号挂为成员——**绝不触碰既有凭据**
 */
public record CreateSubAccountRequest(
        String role,
        String email,
        String displayName,
        String storeId,
        Boolean confirmBindExisting) {
}
