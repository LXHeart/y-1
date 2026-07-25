package com.grassland.trust.dispute;

/**
 * 开争议请求（草场 Epic 6 Slice 6A / HLD 10.5 OpenDispute）。{@code engagementRef} 必填（marketplace applicationId）；
 * {@code reason} 可空。
 */
public record OpenDisputeRequest(String engagementRef, String reason) {
    public OpenDisputeRequest {
        if (engagementRef == null || engagementRef.isBlank()) {
            throw new IllegalArgumentException("engagementRef is required");
        }
    }
}
