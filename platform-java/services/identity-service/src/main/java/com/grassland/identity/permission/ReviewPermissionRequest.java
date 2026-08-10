package com.grassland.identity.permission;

/**
 * 审核商家权限申请的请求体。草场身份域 Slice 2H。
 *
 * <p>{@code decision} 仅允许 {@code approve} 或 {@code reject}（大小写不敏感），compact constructor 内校验。
 * {@code note} 可选（审核备注）。
 */
public record ReviewPermissionRequest(String decision, String note, Integer expectedVersion) {
    public ReviewPermissionRequest {
        if (decision == null || decision.isBlank()) {
            throw new IllegalArgumentException("decision is required");
        }
        String normalized = decision.trim().toLowerCase();
        if (!normalized.equals("approve") && !normalized.equals("reject")) {
            throw new IllegalArgumentException("decision must be approve or reject");
        }
    }
}
