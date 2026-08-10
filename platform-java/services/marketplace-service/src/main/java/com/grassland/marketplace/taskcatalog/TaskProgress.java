package com.grassland.marketplace.taskcatalog;

/** Real-time application and fulfilment facts for one task. */
public record TaskProgress(
        String taskId,
        int totalApplications,
        int pendingApplications,
        int reservingApplications,
        int acceptedApplications,
        int rejectedApplications,
        int withdrawnApplications,
        int refundedApplications,
        int submittedDeliverables,
        int confirmedDeliverables,
        int settledEngagements,
        int occupiedSlots,
        long reservedBountyCents,
        long settledBountyCents) {

    public static TaskProgress empty(String taskId) {
        return new TaskProgress(taskId, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L, 0L);
    }
}
