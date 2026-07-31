package com.grassland.intelligence.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.intelligence.security.IntelligenceException;
import com.grassland.intelligence.verification.VerificationResultNormalizer.VerificationVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link VerificationResultNormalizer} 单元测试（草场 Slice 11 Verification Stage 3）。镜像 ImageAnalysisService.parseResult。 */
class VerificationResultNormalizerTest {

    private final VerificationResultNormalizer normalizer = new VerificationResultNormalizer();

    @Test
    @DisplayName("passed/failed/inconclusive 三态直取并保留 detail")
    void extractsTriStateWithDetail() {
        assertThat(normalizer.normalize("{\"status\":\"passed\",\"detail\":\"真实发布截图\"}"))
                .isEqualTo(new VerificationVerdict("passed", "真实发布截图"));
        assertThat(normalizer.normalize("{\"status\":\"failed\",\"detail\":\"截图与任务无关\"}"))
                .isEqualTo(new VerificationVerdict("failed", "截图与任务无关"));
        assertThat(normalizer.normalize("{\"status\":\"inconclusive\",\"detail\":\"画面模糊\"}"))
                .isEqualTo(new VerificationVerdict("inconclusive", "画面模糊"));
    }

    @Test
    @DisplayName("status 大小写不敏感并兼容常见别名")
    void normalizesAliasesCaseInsensitively() {
        assertThat(normalizer.normalize("{\"status\":\"PASS\"}").status()).isEqualTo("passed");
        assertThat(normalizer.normalize("{\"status\":\"fail\"}").status()).isEqualTo("failed");
        assertThat(normalizer.normalize("{\"status\":\"unknown\"}").status()).isEqualTo("inconclusive");
        assertThat(normalizer.normalize("{\"status\":\"unclear\"}").status()).isEqualTo("inconclusive");
    }

    @Test
    @DisplayName("detail 可空")
    void detailOptional() {
        VerificationVerdict verdict = normalizer.normalize("{\"status\":\"passed\"}");
        assertThat(verdict.status()).isEqualTo("passed");
        assertThat(verdict.detail()).isNull();
    }

    @Test
    @DisplayName("剥 ```json code fence 后解析")
    void stripsCodeFence() {
        String fenced = "```json\n{\"status\":\"failed\",\"detail\":\"造假\"}\n```";
        assertThat(normalizer.normalize(fenced)).isEqualTo(new VerificationVerdict("failed", "造假"));
    }

    @Test
    @DisplayName("非 JSON / 非 object → 502")
    void rejectsMalformedContent() {
        assertThatThrownBy(() -> normalizer.normalize("not json"))
                .isInstanceOf(IntelligenceException.class)
                .satisfies(e -> assertThat(((IntelligenceException) e).status()).isEqualTo(502));
        assertThatThrownBy(() -> normalizer.normalize("[\"passed\"]"))
                .isInstanceOf(IntelligenceException.class)
                .satisfies(e -> assertThat(((IntelligenceException) e).status()).isEqualTo(502));
        assertThatThrownBy(() -> normalizer.normalize(null))
                .isInstanceOf(IntelligenceException.class)
                .satisfies(e -> assertThat(((IntelligenceException) e).status()).isEqualTo(502));
    }

    @Test
    @DisplayName("缺 status 或词表外 status → 502")
    void rejectsMissingOrUnknownStatus() {
        assertThatThrownBy(() -> normalizer.normalize("{\"detail\":\"x\"}"))
                .isInstanceOf(IntelligenceException.class)
                .satisfies(e -> assertThat(((IntelligenceException) e).status()).isEqualTo(502));
        assertThatThrownBy(() -> normalizer.normalize("{\"status\":\"maybe\"}"))
                .isInstanceOf(IntelligenceException.class)
                .satisfies(e -> assertThat(((IntelligenceException) e).status()).isEqualTo(502));
    }
}
