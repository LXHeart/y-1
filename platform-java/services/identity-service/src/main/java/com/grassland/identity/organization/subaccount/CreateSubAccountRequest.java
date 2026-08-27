package com.grassland.identity.organization.subaccount;

/**
 * 组织侧代建子账号请求（任务书 #48 D1/D2；#49 D6 改造）。
 *
 * @param role        目标角色：{@code member}（组织普通成员）/ {@code manager}/{@code staff}
 *                    （必须携带 {@code storeId}）
 * @param loginName   登录名（仅小写字母数字，3–24 位）：与主体前缀拼成完整账号
 *                    {@code 前缀-登录名}；#49 起建号不再填邮箱，成员登录后自行绑定
 * @param displayName 显示名
 * @param storeId     manager/staff 必填；member 必须为空
 */
public record CreateSubAccountRequest(
        String role,
        String loginName,
        String displayName,
        String storeId) {
}
