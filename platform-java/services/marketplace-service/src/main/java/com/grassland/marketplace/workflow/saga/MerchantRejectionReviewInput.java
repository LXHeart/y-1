package com.grassland.marketplace.workflow.saga;

/** D-03 商家拒绝转客服的 SLA workflow 输入。全部时长由 controller 配置折算后传入，workflow 内不读 env。 */
public record MerchantRejectionReviewInput(
        String applicationId,
        String disputeId,
        String organizationId,
        long csSlaSeconds) {
}
