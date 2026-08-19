package com.grassland.marketplace.analytics;

import com.grassland.marketplace.analytics.AnalyticsModels.BusinessReport;
import com.grassland.marketplace.analytics.MarketingAttributionModels.Advice;
import com.grassland.reporting.ReportFormat;
import com.grassland.reporting.ReportRenderer;
import com.grassland.reporting.TabularReport;
import java.time.Instant;
import java.util.List;

public final class MarketingAttributionCsv {
    private MarketingAttributionCsv() {}

    public static byte[] render(BusinessReport report, List<Advice> advice, Instant from, Instant to) {
        return ReportRenderer.render(report(report, advice, from, to), ReportFormat.CSV);
    }

    public static TabularReport report(BusinessReport report, List<Advice> advice, Instant from, Instant to) {
        var attribution = report.attribution();
        String adviceText = advice == null ? "" : advice.stream()
                .map(item -> item.message() + "：" + item.action())
                .collect(java.util.stream.Collectors.joining("；"));
        return new TabularReport("Business Analytics", List.of(
                "organization_id", "store_id", "from", "to", "orders", "paid_orders", "redeemed_orders",
                "refunded_orders", "gross_gmv_cents", "refunded_gmv_cents", "net_gmv_cents",
                "merchant_revenue_cents", "platform_fee_cents", "recommender_revenue_cents",
                "settled_bounty_cents", "exposures", "interactions", "conversions",
                "attributed_revenue_cents", "attributed_refund_cents", "roi", "data_quality", "status", "advice"),
                List.<List<?>>of(List.of(value(report.organizationId()), value(report.storeId()), value(from), value(to),
                        report.orders(), report.paidOrders(), report.redeemedOrders(), report.refundedOrders(),
                        report.grossGmvCents(), report.refundedGmvCents(), report.netGmvCents(),
                        report.merchantRevenueCents(), report.platformFeeCents(), report.recommenderRevenueCents(),
                        report.settledBountyCents(), attribution.exposures(), attribution.interactions(),
                        attribution.conversions(), attribution.attributedRevenueCents(),
                        attribution.attributedRefundCents(), value(attribution.roi()), value(attribution.dataQuality()),
                        value(attribution.status()), adviceText)));
    }

    private static Object value(Object value) {
        return value == null ? "" : value;
    }
}
