package com.grassland.identity.permission;

public record PermissionAutoReview(String status, String mode, String riskLevel, String resultJson) {
    public static PermissionAutoReview manual(String status, String riskLevel, String resultJson) {
        return new PermissionAutoReview(status, "manual", riskLevel, resultJson);
    }
}
