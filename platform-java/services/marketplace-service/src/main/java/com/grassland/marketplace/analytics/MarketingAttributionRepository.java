package com.grassland.marketplace.analytics;

import static com.grassland.marketplace.analytics.MarketingAttributionModels.Alert;
import static com.grassland.marketplace.analytics.MarketingAttributionModels.AlertCandidate;
import static com.grassland.marketplace.analytics.MarketingAttributionModels.Campaign;
import static com.grassland.marketplace.analytics.MarketingAttributionModels.CampaignRequest;

import io.r2dbc.spi.Readable;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class MarketingAttributionRepository {
    private static final String CAMPAIGN_COLUMNS = "id::text, provider, external_campaign_id, organization_id::text,"
            + " store_id::text, task_id::text, recommender_account_id::text, status, created_by::text, created_at, updated_at";
    private static final String ALERT_COLUMNS = "id::text, organization_id::text, store_id::text, rule_code, severity,"
            + " status, message, observed_value::double precision, threshold_value::double precision, last_observed_at,"
            + " acknowledged_at, acknowledged_by::text, created_at, updated_at";
    private final DatabaseClient db;

    public MarketingAttributionRepository(DatabaseClient db) { this.db = db; }

    public Mono<Campaign> create(CampaignRequest request, String accountId) {
        String id = UUID.randomUUID().toString();
        var spec = db.sql("""
                INSERT INTO marketing_attribution_campaign(
                    id, provider, external_campaign_id, organization_id, store_id, task_id,
                    recommender_account_id, created_by)
                VALUES (CAST(:id AS uuid), :provider, :externalId, CAST(:org AS uuid),
                        CAST(:store AS uuid), CAST(:task AS uuid), CAST(:recommender AS uuid), CAST(:createdBy AS uuid))
                RETURNING %s
                """.formatted(CAMPAIGN_COLUMNS))
                .bind("id", id).bind("provider", request.provider())
                .bind("externalId", request.externalCampaignId()).bind("org", request.organizationId())
                .bind("createdBy", accountId);
        spec = nullable(spec, "store", request.storeId());
        spec = nullable(spec, "task", request.taskId());
        spec = nullable(spec, "recommender", request.recommenderAccountId());
        return spec.map(MarketingAttributionRepository::mapCampaign).one();
    }

    public Mono<Campaign> findActive(String provider, String externalCampaignId) {
        return db.sql("SELECT " + CAMPAIGN_COLUMNS + " FROM marketing_attribution_campaign"
                        + " WHERE provider=:provider AND external_campaign_id=:externalId AND status='active'")
                .bind("provider", provider).bind("externalId", externalCampaignId)
                .map(MarketingAttributionRepository::mapCampaign).one();
    }

    public Mono<WebhookClaim> claimWebhook(String provider, String eventId, String payloadSha256) {
        return db.sql("""
                INSERT INTO marketing_attribution_webhook_inbox(provider, event_id, payload_sha256)
                VALUES (:provider, :eventId, :hash) ON CONFLICT DO NOTHING
                RETURNING payload_sha256, processed_at
                """).bind("provider", provider).bind("eventId", eventId).bind("hash", payloadSha256)
                .map(row -> WebhookClaim.CLAIMED).one()
                .switchIfEmpty(db.sql("""
                        SELECT payload_sha256, processed_at
                        FROM marketing_attribution_webhook_inbox
                        WHERE provider=:provider AND event_id=:eventId
                        """).bind("provider", provider).bind("eventId", eventId)
                        .map(row -> {
                            String storedHash = row.get("payload_sha256", String.class);
                            if (!payloadSha256.equals(storedHash)) return WebhookClaim.PAYLOAD_CONFLICT;
                            return row.get("processed_at", OffsetDateTime.class) == null
                                    ? WebhookClaim.CLAIMED : WebhookClaim.DUPLICATE;
                        }).one());
    }

    public Mono<Void> markWebhookProcessed(String provider, String eventId) {
        return db.sql("UPDATE marketing_attribution_webhook_inbox SET processed_at=now()"
                        + " WHERE provider=:provider AND event_id=:eventId")
                .bind("provider", provider).bind("eventId", eventId).then();
    }

    public Flux<Alert> listAlerts(String organizationId, String storeId, boolean includeResolved) {
        String statusClause = includeResolved ? "" : " AND status <> 'resolved'";
        var spec = db.sql("SELECT " + ALERT_COLUMNS + " FROM marketing_attribution_alert"
                        + " WHERE organization_id=CAST(:org AS uuid)"
                        + " AND ((:store IS NULL AND store_id IS NULL) OR store_id=CAST(:store AS uuid))"
                        + statusClause
                        + " ORDER BY CASE severity WHEN 'critical' THEN 0 WHEN 'warning' THEN 1 ELSE 2 END, updated_at DESC")
                .bind("org", organizationId);
        spec = nullable(spec, "store", storeId);
        return spec.map(MarketingAttributionRepository::mapAlert).all();
    }

    public Mono<Void> syncAlerts(String organizationId, String storeId, List<AlertCandidate> candidates) {
        String scopeKey = organizationId + ":" + (storeId == null ? "org" : storeId);
        List<AlertCandidate> active = candidates == null ? List.of() : candidates;
        String[] ruleCodes = active.isEmpty() ? new String[] {"__none__"}
                : active.stream().map(AlertCandidate::ruleCode).toArray(String[]::new);
        return Flux.fromIterable(active)
                .concatMap(candidate -> {
                    var spec = db.sql("""
                        INSERT INTO marketing_attribution_alert(
                            id, scope_key, organization_id, store_id, rule_code, severity, status,
                            message, observed_value, threshold_value)
                        VALUES (gen_random_uuid(), :scopeKey, CAST(:org AS uuid), CAST(:store AS uuid),
                                :rule, :severity, 'open', :message, :observed, :threshold)
                        ON CONFLICT (scope_key, rule_code) DO UPDATE SET
                            severity=EXCLUDED.severity,
                            status=CASE WHEN marketing_attribution_alert.status='resolved' THEN 'open'
                                        ELSE marketing_attribution_alert.status END,
                            message=EXCLUDED.message,
                            observed_value=EXCLUDED.observed_value, threshold_value=EXCLUDED.threshold_value,
                            last_observed_at=now(), updated_at=now()
                        """).bind("scopeKey", scopeKey).bind("org", organizationId).bind("rule", candidate.ruleCode())
                        .bind("severity", candidate.severity()).bind("message", candidate.message())
                        .bind("observed", candidate.observedValue()).bind("threshold", candidate.thresholdValue());
                    return nullable(spec, "store", storeId).then();
                })
                .then(db.sql("""
                        UPDATE marketing_attribution_alert
                        SET status='resolved', updated_at=now()
                        WHERE scope_key=:scopeKey AND status <> 'resolved'
                          AND rule_code <> ALL(CAST(:rules AS text[]))
                        """).bind("scopeKey", scopeKey)
                        .bind("rules", ruleCodes)
                        .then());
    }

    public Mono<Boolean> acknowledge(String id, String accountId) {
        return db.sql("UPDATE marketing_attribution_alert SET status='acknowledged', acknowledged_at=now(),"
                        + " acknowledged_by=CAST(:account AS uuid), updated_at=now()"
                        + " WHERE id=CAST(:id AS uuid) AND status='open'")
                .bind("id", id).bind("account", accountId).fetch().rowsUpdated().map(rows -> rows == 1);
    }

    private static Campaign mapCampaign(Readable row) {
        return new Campaign(row.get("id", String.class), row.get("provider", String.class),
                row.get("external_campaign_id", String.class), row.get("organization_id", String.class),
                row.get("store_id", String.class), row.get("task_id", String.class),
                row.get("recommender_account_id", String.class), row.get("status", String.class),
                row.get("created_by", String.class), instant(row.get("created_at", OffsetDateTime.class)),
                instant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static Alert mapAlert(Readable row) {
        return new Alert(row.get("id", String.class), row.get("organization_id", String.class),
                row.get("store_id", String.class), row.get("rule_code", String.class),
                row.get("severity", String.class), row.get("status", String.class),
                row.get("message", String.class), row.get("observed_value", Double.class),
                row.get("threshold_value", Double.class), instant(row.get("last_observed_at", OffsetDateTime.class)),
                instant(row.get("acknowledged_at", OffsetDateTime.class)), row.get("acknowledged_by", String.class),
                instant(row.get("created_at", OffsetDateTime.class)), instant(row.get("updated_at", OffsetDateTime.class)));
    }

    private static DatabaseClient.GenericExecuteSpec nullable(DatabaseClient.GenericExecuteSpec spec,
                                                              String name, String value) {
        return value == null || value.isBlank() ? spec.bindNull(name, String.class) : spec.bind(name, value);
    }
    private static Instant instant(OffsetDateTime value) { return value == null ? null : value.toInstant(); }

    public enum WebhookClaim { CLAIMED, DUPLICATE, PAYLOAD_CONFLICT }
}
