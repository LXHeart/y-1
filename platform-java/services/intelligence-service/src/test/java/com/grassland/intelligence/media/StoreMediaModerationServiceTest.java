package com.grassland.intelligence.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link StoreMediaModerationService#parseVerdict} 结论解析单测（缺口清偿之五）。 */
@DisplayName("StoreMediaModerationService verdict 解析")
class StoreMediaModerationServiceTest {

    @Test
    void parsesPassVerdictWithEmptyFindings() {
        StoreMediaModerationService.Verdict verdict =
                StoreMediaModerationService.parseVerdict("{\"verdict\":\"pass\",\"findings\":[]}", "run-1");

        assertThat(verdict.status()).isEqualTo("pass");
        assertThat(verdict.findings()).isEmpty();
        assertThat(verdict.runId()).isEqualTo("run-1");
    }

    @Test
    void parsesBlockedVerdictWithFindingsAndStripsCodeFence() {
        String content = """
                ```json
                {"verdict":"blocked","findings":[
                  {"category":"pornographic","severity":"high","advice":"画面含违规内容"},
                  {"category":"","severity":"high","advice":"无类目项被丢弃"}]}
                ```
                """;

        StoreMediaModerationService.Verdict verdict = StoreMediaModerationService.parseVerdict(content, null);

        assertThat(verdict.status()).isEqualTo("blocked");
        assertThat(verdict.findings()).hasSize(1);
        assertThat(verdict.findings().getFirst().category()).isEqualTo("pornographic");
        assertThat(verdict.findings().getFirst().severity()).isEqualTo("high");
    }

    @Test
    void unparseableOutputDegradesToReviewInsteadOfFakePass() {
        StoreMediaModerationService.Verdict verdict =
                StoreMediaModerationService.parseVerdict("模型闲聊不是 JSON", "run-2");

        assertThat(verdict.status()).isEqualTo("review");
        assertThat(verdict.findings()).isEqualTo(List.of(
                new StoreMediaModerationService.Verdict.Finding("unparseable", "medium", "审核模型输出不可解析，转人工复核")));
    }

    @Test
    void unknownVerdictValueDegradesToReview() {
        StoreMediaModerationService.Verdict verdict =
                StoreMediaModerationService.parseVerdict("{\"verdict\":\"excellent\"}", null);

        assertThat(verdict.status()).isEqualTo("review");
    }
}
