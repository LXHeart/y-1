package com.grassland.trust.dispute;

import java.util.UUID;

/**
 * 开争议请求（草场 Epic 6 Slice 6A / HLD 10.5 OpenDispute）。{@code engagementRef} 必填（marketplace applicationId）；
 * {@code reason} 可空。
 *
 * <p>切片 12 安全收口：{@code engagementRef} 在 HTTP 边界即校验为 UUID，避免非法值经 DB CAST 变 500
 * 或经 outbox 进入 Kafka 重试/DLT。canonical 字符串形式由 trust 持久化。
 */
public record OpenDisputeRequest(String engagementRef, String reason) {
    public OpenDisputeRequest {
        if (engagementRef == null || engagementRef.isBlank()) {
            throw new IllegalArgumentException("engagementRef is required");
        }
        try {
            engagementRef = UUID.fromString(engagementRef.trim()).toString();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("engagementRef must be a UUID");
        }
    }
}
