package com.grassland.marketplace.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import com.grassland.marketplace.security.MarketplaceException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import reactor.core.publisher.Mono;

/** {@code GET /api/analytics/series} 营销看板时间序列（PRD §2.4 按日/周/月）。 */
class AnalyticsSeriesIT extends MarketplaceItSupport {

    private static final Instant FROM = Instant.parse("2026-08-12T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-15T00:00:00Z");
    /** 北京时间 2026-08-12 18:00（周三）。 */
    private static final Instant ORDER_AT = Instant.parse("2026-08-12T10:00:00Z");
    /** 北京时间 2026-08-13 10:00（周四）。 */
    private static final Instant EVENT_AT = Instant.parse("2026-08-13T02:00:00Z");

    @Test
    void dayGranularityBucketsOrdersAndEventsWithZeroFill() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        seedPaidOrder(org, ORDER_AT);
        seedAttributionEvent(org, "exposure", EVENT_AT);

        EntityExchangeResult<byte[]> result = client().get()
                .uri(uri -> uri.path("/api/analytics/series")
                        .queryParam("organizationId", org)
                        .queryParam("from", FROM.toString()).queryParam("to", TO.toString())
                        .queryParam("granularity", "day").build())
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.granularity").isEqualTo("day")
                .jsonPath("$.data.buckets.length()").isEqualTo(4)
                .jsonPath("$.data.buckets[0].bucket").isEqualTo("2026-08-12")
                .jsonPath("$.data.buckets[0].orders").isEqualTo(1)
                .jsonPath("$.data.buckets[0].paidOrders").isEqualTo(1)
                .jsonPath("$.data.buckets[0].grossGmvCents").isEqualTo(1000)
                .jsonPath("$.data.buckets[0].netGmvCents").isEqualTo(1000)
                .jsonPath("$.data.buckets[0].merchantRevenueCents").isEqualTo(850)
                .jsonPath("$.data.buckets[0].recommenderRevenueCents").isEqualTo(100)
                .jsonPath("$.data.buckets[1].bucket").isEqualTo("2026-08-13")
                .jsonPath("$.data.buckets[1].exposures").isEqualTo(1)
                .jsonPath("$.data.buckets[1].orders").isEqualTo(0)
                .jsonPath("$.data.buckets[2].orders").isEqualTo(0)
                .jsonPath("$.data.buckets[3].bucket").isEqualTo("2026-08-15")
                .jsonPath("$.data.buckets[3].conversions").isEqualTo(0)
                .returnResult();

        assertThat(result.getResponseBody()).isNotNull();
    }

    @Test
    void weekGranularityAlignsToMonday() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        seedPaidOrder(org, ORDER_AT);
        seedAttributionEvent(org, "conversion", EVENT_AT);

        client().get()
                .uri(uri -> uri.path("/api/analytics/series")
                        .queryParam("organizationId", org)
                        .queryParam("from", FROM.toString()).queryParam("to", TO.toString())
                        .queryParam("granularity", "week").build())
                .header("X-Grassland-Identity", sign(merchant, "merchant", org, "basic_publish"))
                .exchange().expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.buckets.length()").isEqualTo(1)
                .jsonPath("$.data.buckets[0].bucket").isEqualTo("2026-08-10")
                .jsonPath("$.data.buckets[0].orders").isEqualTo(1)
                .jsonPath("$.data.buckets[0].conversions").isEqualTo(1);
    }

    @Test
    void adminSeriesRequiresFinanceOrRiskRole() {
        String org = UUID.randomUUID().toString();
        String query = "/api/admin/analytics/series?organizationId=" + org
                + "&from=" + FROM + "&to=" + TO + "&granularity=month";

        client().get().uri(query)
                .header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "user"))
                .exchange().expectStatus().isForbidden();

        client().get().uri(query)
                .header("X-Grassland-Identity", signWithRole(UUID.randomUUID().toString(), "finance"))
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.data.buckets.length()").isEqualTo(1);
    }

    @Test
    void validatesGranularityWindowAndScope() {
        String merchant = UUID.randomUUID().toString();
        String org = UUID.randomUUID().toString();
        String assertion = sign(merchant, "merchant", org, "basic_publish");

        client().get().uri(uri -> uri.path("/api/analytics/series")
                        .queryParam("organizationId", org)
                        .queryParam("from", FROM.toString()).queryParam("to", TO.toString())
                        .queryParam("granularity", "hour").build())
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("granularity 仅支持 day/week/month");

        client().get().uri(uri -> uri.path("/api/analytics/series")
                        .queryParam("organizationId", org)
                        .queryParam("from", TO.toString()).queryParam("to", FROM.toString()).build())
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("to 必须晚于 from");

        // 跨度桶数超限（day 粒度 > 400 天）
        client().get().uri(uri -> uri.path("/api/analytics/series")
                        .queryParam("organizationId", org)
                        .queryParam("from", "2020-01-01T00:00:00Z")
                        .queryParam("to", "2026-08-15T00:00:00Z").build())
                .header("X-Grassland-Identity", assertion)
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.error").isEqualTo("时间跨度的桶数超过上限（400）");

        // 别家 org 的商家 → 403（org 级授权服务端化：模拟 identity 判定无管理权）
        String outsider = UUID.randomUUID().toString();
        Mockito.when(storeAuthorization.authorize(outsider, org, null, "manager"))
                .thenReturn(Mono.error(new MarketplaceException(403, "无权管理该组织资源")));
        client().get().uri(uri -> uri.path("/api/analytics/series")
                        .queryParam("organizationId", org)
                        .queryParam("from", FROM.toString()).queryParam("to", TO.toString()).build())
                .header("X-Grassland-Identity", sign(outsider, "merchant", UUID.randomUUID().toString(), "basic_publish"))
                .exchange().expectStatus().isForbidden();
    }

    private void seedPaidOrder(String organizationId, Instant createdAt) {
        String orderId = UUID.randomUUID().toString();
        String packageId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        db.sql("INSERT INTO commerce_package(id, organization_id, owner_account_id, status)"
                        + " VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:owner AS uuid), 'published')")
                .bind("id", packageId).bind("org", organizationId).bind("owner", owner).then().block();
        db.sql("""
                        INSERT INTO commerce_package_version(
                          id, package_id, version, title, price_cents, total_stock,
                          valid_days_after_purchase, recommender_share_bps, platform_fee_bps,
                          merchant_share_bps, policy_version, created_by)
                        VALUES (CAST(:id AS uuid), CAST(:package AS uuid), 1, 'Package', 1000, 1,
                                30, 1000, 500, 8500, 'commerce-v1', CAST(:owner AS uuid))
                        """).bind("id", versionId).bind("package", packageId).bind("owner", owner).then().block();
        db.sql("""
                        INSERT INTO consumer_order(
                          id, consumer_account_id, organization_id, package_id, package_version_id,
                          package_version, package_title, price_cents, recommender_share_bps,
                          platform_fee_bps, merchant_share_bps, recommender_amount_cents,
                          platform_fee_cents, merchant_amount_cents, policy_version, status,
                          redeem_code_hash, redeem_deadline, payment_operation_id, created_at)
                        VALUES (CAST(:id AS uuid), CAST(:consumer AS uuid), CAST(:org AS uuid),
                                CAST(:package AS uuid), CAST(:version AS uuid), 1, 'Package', 1000,
                                1000, 500, 8500, 100, 50, 850, 'commerce-v1', 'paid',
                                :redeemHash, now() + interval '30 days', :paymentOperation, :createdAt)
                        """).bind("id", orderId).bind("consumer", UUID.randomUUID().toString())
                .bind("org", organizationId).bind("package", packageId).bind("version", versionId)
                .bind("redeemHash", UUID.randomUUID().toString().replace("-", ""))
                .bind("paymentOperation", "payment:" + orderId)
                .bind("createdAt", OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC)).then().block();
    }

    private void seedAttributionEvent(String organizationId, String eventType, Instant occurredAt) {
        db.sql("""
                        INSERT INTO marketing_attribution_event(
                          id, idempotency_key, source, event_type, organization_id, occurred_at,
                          value_cents, metadata, recorded_by)
                        VALUES (CAST(:id AS uuid), :key, 'sandbox_manual', :type, CAST(:org AS uuid),
                                :occurred, 0, '{}'::jsonb, CAST(:recordedBy AS uuid))
                        """).bind("id", UUID.randomUUID().toString())
                .bind("key", "series-" + UUID.randomUUID())
                .bind("type", eventType).bind("org", organizationId)
                .bind("occurred", OffsetDateTime.ofInstant(occurredAt, ZoneOffset.UTC))
                .bind("recordedBy", UUID.randomUUID().toString()).then().block();
    }
}
