package com.grassland.trust.dispute;

/**
 * 手动裁决请求（草场 Epic 6 Slice 6A）。{@code decision} 必填（如 in_merchant_favor / in_recommender_favor）。
 * 本 slice 仅记录裁决、翻争议为 decided，**不**触发钱侧（capture/refund 留 D-06 后续）。
 */
public record DecideDisputeRequest(String decision) {
    public DecideDisputeRequest {
        if (decision == null || decision.isBlank()) {
            throw new IllegalArgumentException("decision is required");
        }
    }
}
