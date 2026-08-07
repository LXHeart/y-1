package com.grassland.marketplace.workflow.saga;

import com.grassland.marketplace.taskcatalog.TaskApplication;

/** Converts the acceptance-time day entitlement into a deterministic Temporal timer. */
public final class SettlementWindowPolicy {

    private static final int STANDARD_DELAY_DAYS = 2;

    private SettlementWindowPolicy() {}

    public static long windowSeconds(TaskApplication application, long daySeconds) {
        int days = application.settlementDelayDaysAtAccept() == null
                ? STANDARD_DELAY_DAYS : application.settlementDelayDaysAtAccept();
        return Math.multiplyExact(Math.max(0L, daySeconds), Math.max(0, days));
    }
}
