package com.grassland.marketplace.analytics;

import com.grassland.marketplace.analytics.AnalyticsModels.AttributionSummary;
import com.grassland.marketplace.analytics.AnalyticsModels.BusinessReport;
import com.grassland.marketplace.analytics.MarketingAttributionModels.Advice;
import com.grassland.marketplace.analytics.MarketingAttributionModels.AdviceAndAlerts;
import com.grassland.marketplace.analytics.MarketingAttributionModels.AlertCandidate;
import java.util.ArrayList;
import java.util.List;

/** Deterministic business guidance derived only from report facts. */
public final class AnalyticsAdvice {
    static final double MIN_INTERACTION_RATE = 0.01d;
    static final double MIN_CONVERSION_RATE = 0.01d;

    private AnalyticsAdvice() {}

    public static AdviceAndAlerts evaluate(BusinessReport report) {
        AttributionSummary attribution = report.attribution();
        List<Advice> advice = new ArrayList<>();
        List<AlertCandidate> alerts = new ArrayList<>();

        if ("none".equals(attribution.dataQuality()) || "sandbox".equals(attribution.dataQuality())) {
            advice.add(new Advice("connect_verified_provider", "warning", "当前缺少可信营销来源数据",
                    "绑定营销平台 Campaign，并启用签名 Webhook"));
        } else if ("mixed".equals(attribution.dataQuality())) {
            advice.add(new Advice("retire_sandbox_events", "info", "报表同时包含 Sandbox 与可信来源数据",
                    "完成 provider 切换后停止写入 Sandbox 事件"));
        }

        if (attribution.exposures() > 0) {
            double rate = (double) attribution.interactions() / attribution.exposures();
            if (rate < MIN_INTERACTION_RATE) {
                advice.add(new Advice("improve_interaction_rate", "warning", "曝光后的互动率偏低",
                        "检查创意、受众定向和落地页一致性"));
                alerts.add(new AlertCandidate("low_interaction_rate", "warning", "营销互动率低于 1%",
                        rate, MIN_INTERACTION_RATE));
            }
        }

        if (attribution.interactions() > 0) {
            double rate = (double) attribution.conversions() / attribution.interactions();
            if (rate < MIN_CONVERSION_RATE) {
                advice.add(new Advice("improve_conversion_rate", "warning", "互动后的转化率偏低",
                        "检查商品承接、权益说明和结算路径"));
                alerts.add(new AlertCandidate("low_conversion_rate", "warning", "营销转化率低于 1%",
                        rate, MIN_CONVERSION_RATE));
            }
        }

        if (attribution.roi() != null && attribution.roi() < 0d) {
            advice.add(new Advice("reduce_negative_roi_spend", "critical", "当前营销归因 ROI 为负",
                    "暂停低效 Campaign，并核对归因收入和赏金成本"));
            alerts.add(new AlertCandidate("negative_roi", "critical", "营销归因 ROI 低于 0",
                    attribution.roi(), 0d));
        }
        return new AdviceAndAlerts(List.copyOf(advice), List.copyOf(alerts));
    }
}
