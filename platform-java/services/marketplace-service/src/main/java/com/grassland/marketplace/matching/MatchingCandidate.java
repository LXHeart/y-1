package com.grassland.marketplace.matching;

/** Marketplace-local candidate facts that are not part of the shared reputation aggregate. */
public record MatchingCandidate(
        String accountId, int platformEngagementCount, TaskRecommenderInvitation invitation) {}
