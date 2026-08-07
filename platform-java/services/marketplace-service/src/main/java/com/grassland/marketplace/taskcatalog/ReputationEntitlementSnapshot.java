package com.grassland.marketplace.taskcatalog;

/** Immutable entitlement values frozen when a merchant accepts an application. */
public record ReputationEntitlementSnapshot(
        int level,
        long policyVersion,
        int settlementDelayDays,
        int commissionBonusBps,
        boolean premiumSupport) {
}
