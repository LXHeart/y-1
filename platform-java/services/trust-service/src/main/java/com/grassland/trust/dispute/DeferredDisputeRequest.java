package com.grassland.trust.dispute;

import java.time.Instant;

/** merchant_rejection 客服案期间持久化的推荐官延后异议。 */
public record DeferredDisputeRequest(
        String id,
        String sourceDisputeId,
        String engagementRef,
        String organizationId,
        String recommenderAccountId,
        String reason,
        String status,
        String promotedDisputeId,
        String adjudicationWorkflowId,
        Instant adjudicationWorkflowStartedAt,
        Instant createdAt,
        Instant updatedAt
) {}
