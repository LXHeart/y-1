package com.grassland.marketplace.taskcatalog;

/** Merchant-facing dashboard. Financial values are sourced from marketplace facts; marketing events are explicit gaps. */
public record MerchantDashboard(
        String organizationId,
        String storeId,
        int taskCount,
        int publishedTaskCount,
        int totalApplications,
        int acceptedApplications,
        int confirmedDeliverables,
        int settledEngagements,
        long reservedBountyCents,
        long settledBountyCents,
        double applicationAcceptanceRate,
        Double averageRating,
        boolean exposureCollected,
        boolean interactionCollected,
        boolean conversionCollected,
        String marketingMetricsStatus) {}
