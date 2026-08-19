package com.grassland.marketplace.analytics;

import com.grassland.marketplace.analytics.AnalyticsModels.BusinessReport;
import com.grassland.marketplace.analytics.MarketingAttributionModels.Advice;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

public final class MarketingAttributionCsv {
    private MarketingAttributionCsv() {}

    public static byte[] render(BusinessReport report, List<Advice> advice, Instant from, Instant to) {
        var attribution = report.attribution();
        StringBuilder csv = new StringBuilder("\uFEFForganization_id,store_id,from,to,exposures,interactions,")
                .append("conversions,attributed_revenue_cents,attributed_refund_cents,settled_bounty_cents,")
                .append("roi,data_quality,status,advice\r\n");
        csv.append(cell(report.organizationId())).append(',')
                .append(cell(report.storeId())).append(',')
                .append(cell(from)).append(',').append(cell(to)).append(',')
                .append(attribution.exposures()).append(',').append(attribution.interactions()).append(',')
                .append(attribution.conversions()).append(',').append(attribution.attributedRevenueCents()).append(',')
                .append(attribution.attributedRefundCents()).append(',').append(report.settledBountyCents()).append(',')
                .append(cell(attribution.roi())).append(',').append(cell(attribution.dataQuality())).append(',')
                .append(cell(attribution.status())).append(',')
                .append(cell(advice == null ? "" : advice.stream()
                        .map(item -> item.message() + "：" + item.action()).collect(java.util.stream.Collectors.joining("；"))))
                .append("\r\n");
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    static String cell(Object value) {
        if (value == null) return "";
        String text = String.valueOf(value).replace("\r", " ").replace("\n", " ");
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
