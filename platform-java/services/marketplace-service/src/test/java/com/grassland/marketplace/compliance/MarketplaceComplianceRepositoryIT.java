package com.grassland.marketplace.compliance;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.MarketplaceItSupport;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class MarketplaceComplianceRepositoryIT extends MarketplaceItSupport {

    @Autowired
    private MarketplaceComplianceRepository repository;

    @Test
    void erasesConsumerFreeTextWhileRetainingOrderAndAfterSalesFacts() {
        String consumerId = UUID.randomUUID().toString();
        String ownerId = UUID.randomUUID().toString();
        String organizationId = UUID.randomUUID().toString();
        String packageId = UUID.randomUUID().toString();
        String versionId = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();

        db.sql("INSERT INTO commerce_package(id, organization_id, owner_account_id, status)"
                        + " VALUES (CAST(:id AS uuid), CAST(:org AS uuid), CAST(:owner AS uuid), 'published')")
                .bind("id", packageId).bind("org", organizationId).bind("owner", ownerId).then().block();
        db.sql("""
                        INSERT INTO commerce_package_version(
                          id, package_id, version, title, price_cents, total_stock,
                          valid_days_after_purchase, recommender_share_bps, platform_fee_bps,
                          merchant_share_bps, policy_version, created_by)
                        VALUES (CAST(:id AS uuid), CAST(:package AS uuid), 1, 'Package', 1000, 1,
                                30, 1000, 500, 8500, 'commerce-v1', CAST(:owner AS uuid))
                        """).bind("id", versionId).bind("package", packageId).bind("owner", ownerId).then().block();
        db.sql("""
                        INSERT INTO consumer_order(
                          id, consumer_account_id, organization_id, package_id, package_version_id,
                          package_version, package_title, price_cents, recommender_share_bps,
                          platform_fee_bps, merchant_share_bps, recommender_amount_cents,
                          platform_fee_cents, merchant_amount_cents, policy_version, status,
                          redeem_code_hash, redeem_deadline, payment_operation_id, refund_reason)
                        VALUES (CAST(:id AS uuid), CAST(:consumer AS uuid), CAST(:org AS uuid),
                                CAST(:package AS uuid), CAST(:version AS uuid), 1, 'Package', 1000,
                                1000, 500, 8500, 100, 50, 850, 'commerce-v1', 'after_sales_disputed',
                                :redeemHash, now() + interval '30 days', :paymentOperation, 'private refund reason')
                        """).bind("id", orderId).bind("consumer", consumerId).bind("org", organizationId)
                .bind("package", packageId).bind("version", versionId)
                .bind("redeemHash", UUID.randomUUID().toString().replace("-", ""))
                .bind("paymentOperation", "payment:" + orderId).then().block();
        db.sql("INSERT INTO consumer_review(id, order_id, consumer_account_id, rating, comment)"
                        + " VALUES (gen_random_uuid(), CAST(:order AS uuid), CAST(:consumer AS uuid), 3, 'private review')")
                .bind("order", orderId).bind("consumer", consumerId).then().block();
        db.sql("INSERT INTO consumer_order_attribution(id, order_id, recommender_share_bps, reason, actor_account_id)"
                        + " VALUES (gen_random_uuid(), CAST(:order AS uuid), 0, 'private attribution reason',"
                        + " CAST(:consumer AS uuid))")
                .bind("order", orderId).bind("consumer", consumerId).then().block();
        db.sql("INSERT INTO consumer_order_after_sales_dispute(id, order_id, consumer_account_id, reason,"
                        + " resolution_reason) VALUES (gen_random_uuid(), CAST(:order AS uuid),"
                        + " CAST(:consumer AS uuid), 'private dispute reason', 'private resolution reason')")
                .bind("order", orderId).bind("consumer", consumerId).then().block();

        Map<String, Long> counts = repository.erasePii(consumerId).block();
        Map<String, Object> row = db.sql("""
                        SELECT o.refund_reason, r.comment, a.reason AS attribution_reason,
                               d.reason AS dispute_reason, d.resolution_reason,
                               (SELECT COUNT(*) FROM consumer_order WHERE id = o.id) AS order_count
                        FROM consumer_order o
                        JOIN consumer_review r ON r.order_id = o.id
                        JOIN consumer_order_attribution a ON a.order_id = o.id
                        JOIN consumer_order_after_sales_dispute d ON d.order_id = o.id
                        WHERE o.id = CAST(:id AS uuid)
                        """).bind("id", orderId).fetch().one().block();

        assertThat(counts).containsEntry("reviews", 1L)
                .containsEntry("orderRefundReasons", 1L)
                .containsEntry("afterSalesReasons", 1L)
                .containsEntry("attributionReasons", 1L);
        assertThat(row).isNotNull();
        assertThat(row.get("refund_reason")).isNull();
        assertThat(row.get("comment")).isNull();
        assertThat(row.get("attribution_reason")).isNull();
        assertThat(row.get("dispute_reason")).isEqualTo("[redacted]");
        assertThat(row.get("resolution_reason")).isNull();
        assertThat(((Number) row.get("order_count")).longValue()).isEqualTo(1L);
    }
}
