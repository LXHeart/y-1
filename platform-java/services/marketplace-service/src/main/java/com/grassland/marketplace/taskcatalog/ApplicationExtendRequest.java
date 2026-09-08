package com.grassland.marketplace.taskcatalog;

/**
 * 交付延期请求体（任务书 #96 §6 POST /extend）。申请侧（推荐官）：{@code days} 正整数 + 可空 {@code reason}；
 * 决定侧（商家）：{@code decision} = approve | reject。全字段包装类型（Jackson 缺字段不炸，house rule）。
 */
public record ApplicationExtendRequest(Integer days, String reason, String decision) {

    public boolean isApproval() {
        return decision != null && "approve".equalsIgnoreCase(decision.trim());
    }

    /** 决定侧合法性：decision 必须显式为 approve/reject。 */
    public boolean hasValidDecision() {
        if (decision == null || decision.isBlank()) {
            return false;
        }
        String normalized = decision.trim().toLowerCase();
        return "approve".equals(normalized) || "reject".equals(normalized);
    }
}
