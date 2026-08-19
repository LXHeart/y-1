package com.grassland.marketplace.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.marketplace.analytics.AnalyticsModels.AttributionSummary;
import com.grassland.marketplace.analytics.AnalyticsModels.BusinessReport;
import com.grassland.marketplace.analytics.MarketingAttributionModels.Advice;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class MarketingAttributionCsvTest {
    @Test
    void protectsFormulaLikeAdviceText() {
        BusinessReport report = new BusinessReport("11111111-1111-1111-1111-111111111111", null,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                new AttributionSummary(0, 0, 0, 0, 0, "none", "not_collected", null));
        String csv = new String(MarketingAttributionCsv.render(report,
                List.of(new Advice("x", "warning", "=SUM(A1)", "@do")), null, null),
                StandardCharsets.UTF_8);
        assertThat(csv).contains("'=SUM(A1)：@do");
    }
}
