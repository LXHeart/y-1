package com.grassland.marketplace.analytics;

import java.time.Instant;
import java.util.Map;

public final class AnalyticsModels {
    private AnalyticsModels() {}

    public record RecordEventRequest(
            String idempotencyKey, String sourceEventId, String eventType, String organizationId,
            String storeId, String taskId, String recommenderAccountId, Instant occurredAt,
            Long valueCents, Map<String, Object> metadata) {}

    public record Event(
            String id, String idempotencyKey, String sourceEventId, String source, String eventType,
            String organizationId, String storeId, String taskId, String recommenderAccountId,
            Instant occurredAt, long valueCents, String metadataJson, String recordedBy, Instant createdAt) {}

    public record AttributionSummary(
            int exposures, int interactions, int conversions, long attributedRevenueCents,
            long attributedRefundCents, String dataQuality, String status, Double roi) {}

    public record BusinessReport(
            String organizationId, String storeId, int orders, int paidOrders, int redeemedOrders,
            int refundedOrders, long grossGmvCents, long refundedGmvCents, long netGmvCents,
            long merchantRevenueCents, long platformFeeCents, long recommenderRevenueCents,
            long settledBountyCents, AttributionSummary attribution) {}

    public record RecommenderReport(String recommenderAccountId, int conversions,
                                    long attributedRevenueCents, long recommenderRevenueCents) {}
    public record EventRegistration(Event event, boolean created) {}
}
