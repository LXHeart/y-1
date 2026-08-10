package com.grassland.marketplace.taskcatalog;

/** Status and fulfilment summary returned with a task's application list. */
public record TaskApplicationStats(
        int total,
        int pending,
        int reserving,
        int accepted,
        int rejected,
        int withdrawn,
        int refunded,
        int occupiedSlots,
        Integer maxSlots,
        int remainingSlots,
        int submittedDeliverables,
        int confirmedDeliverables,
        int settledEngagements) {}
