package com.grassland.marketplace.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

class AnalyticsControllerIT extends MarketplaceItSupport {
    private static final String SECRET = "marketing-test-webhook-secret-value-32";

    @DynamicPropertySource
    static void marketingSecrets(DynamicPropertyRegistry registry) {
        registry.add("marketplace.marketing.attribution.webhook-secrets.meta", () -> SECRET);
    }

    @Test
    void signedProviderWebhookUsesCampaignSnapshotAndIsIdempotent() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String externalCampaignId = "campaign-" + UUID.randomUUID();
        client().post().uri("/api/analytics/campaigns")
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("provider", "meta", "externalCampaignId", externalCampaignId,
                        "organizationId", org))
                .exchange().expectStatus().isCreated();

        String eventId = "event-" + UUID.randomUUID();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"eventType\":\"exposure\",\"campaignId\":\"" + externalCampaignId + "\"}";
        String signature = MarketingAttributionWebhookVerifier.sign(SECRET, timestamp + "." + eventId + "." + body);
        client().post().uri("/api/analytics/webhooks/meta")
                .header("X-Marketing-Event-Id", eventId)
                .header("X-Marketing-Timestamp", timestamp)
                .header("X-Marketing-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.accepted").isEqualTo(true)
                .jsonPath("$.data.duplicate").isEqualTo(false);

        client().post().uri("/api/analytics/webhooks/meta")
                .header("X-Marketing-Event-Id", eventId)
                .header("X-Marketing-Timestamp", timestamp)
                .header("X-Marketing-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.duplicate").isEqualTo(true);

        Map<String, Object> row = db.sql("SELECT source, organization_id::text org, store_id::text store"
                        + " FROM marketing_attribution_event WHERE source_event_id IS NULL"
                        + " AND metadata->>'webhookEventId'=:eventId")
                .bind("eventId", eventId).fetch().one().block();
        assertThat(row).containsEntry("source", "meta").containsEntry("org", org).containsEntry("store", null);
    }

    @Test
    void unboundCampaignIsRejectedBeforeInboxClaim() {
        String eventId = "event-" + UUID.randomUUID();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String body = "{\"eventType\":\"conversion\",\"campaignId\":\"not-bound\",\"valueCents\":100}";
        String signature = MarketingAttributionWebhookVerifier.sign(SECRET, timestamp + "." + eventId + "." + body);
        client().post().uri("/api/analytics/webhooks/meta")
                .header("X-Marketing-Event-Id", eventId)
                .header("X-Marketing-Timestamp", timestamp)
                .header("X-Marketing-Signature", signature)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void businessReportExportSupportsCsvXlsxAndRoleAuthorization() {
        String merchant = UUID.randomUUID().toString();
        String organizationId = UUID.randomUUID().toString();
        String merchantAssertion = sign(merchant, "merchant", organizationId, "basic_publish");

        byte[] csv = client().get().uri(uri -> uri.path("/api/analytics/export")
                        .queryParam("organizationId", organizationId).queryParam("format", "csv").build())
                .header("X-Grassland-Identity", merchantAssertion)
                .exchange().expectStatus().isOk()
                .expectHeader().contentType("text/csv;charset=UTF-8")
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(csv).startsWith((byte) 0xef, (byte) 0xbb, (byte) 0xbf);
        assertThat(new String(csv, StandardCharsets.UTF_8))
                .contains("organization_id", organizationId, "gross_gmv_cents");

        byte[] merchantXlsx = client().get().uri(uri -> uri.path("/api/analytics/export")
                        .queryParam("organizationId", organizationId).queryParam("format", "xlsx").build())
                .header("X-Grassland-Identity", merchantAssertion)
                .exchange().expectStatus().isOk()
                .expectHeader().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(merchantXlsx).startsWith((byte) 'P', (byte) 'K');

        byte[] adminXlsx = client().get().uri(uri -> uri.path("/api/admin/analytics/business/export")
                        .queryParam("organizationId", organizationId).queryParam("format", "excel").build())
                .header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "finance"))
                .exchange().expectStatus().isOk()
                .expectHeader().contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .expectBody(byte[].class).returnResult().getResponseBody();
        assertThat(adminXlsx).startsWith((byte) 'P', (byte) 'K');

        client().get().uri(uri -> uri.path("/api/admin/analytics/business/export")
                        .queryParam("organizationId", organizationId).queryParam("format", "csv").build())
                .header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "user"))
                .exchange().expectStatus().isForbidden();
    }
}
