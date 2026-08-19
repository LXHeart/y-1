package com.grassland.marketplace.analytics;

import java.time.Instant;
import java.util.List;

public final class MarketingAttributionModels {
    private MarketingAttributionModels() {}

    public record CampaignRequest(
            String provider, String externalCampaignId, String organizationId, String storeId,
            String taskId, String recommenderAccountId) {}

    public record Campaign(
            String id, String provider, String externalCampaignId, String organizationId,
            String storeId, String taskId, String recommenderAccountId, String status,
            String createdBy, Instant createdAt, Instant updatedAt) {}

    public record Advice(String code, String severity, String message, String action) {}

    public record Alert(
            String id, String organizationId, String storeId, String ruleCode, String severity,
            String status, String message, Double observedValue, Double thresholdValue,
            Instant lastObservedAt, Instant acknowledgedAt, String acknowledgedBy,
            Instant createdAt, Instant updatedAt) {}

    public record AlertCandidate(
            String ruleCode, String severity, String message, double observedValue,
            double thresholdValue) {}

    public record ProviderEvent(
            String eventId, String eventType, String externalCampaignId, Instant occurredAt,
            long valueCents, String sourceEventId) {}

    public record AdviceAndAlerts(List<Advice> advice, List<AlertCandidate> alerts) {}
}
