package com.grassland.marketplace.taskcatalog;

/** 商家对系统核实通过履约发起异议（D-03 §2）。拒绝理由必填，供客服裁定。 */
public record ContestEngagementRequest(String reason) {
    public ContestEngagementRequest {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("拒绝理由不能为空");
        }
        reason = reason.trim();
    }
}
