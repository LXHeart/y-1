package com.grassland.intelligence.creationcontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 门店品牌 prompt 渲染单测（任务书 #24 Stage 4）：品牌语气/必须强调/禁止表达/标签池
 * 必须出现在注入的 prompt 文本里；空快照不改变既有 prompt 形状。
 */
class StoreBrandingPromptTextTest {

    @Test
    void rendersToneEmphasisForbiddenAndTags() {
        String text = StoreBrandingPromptText.render(Map.of(
                "storeName", "旗舰店",
                "brandTone", "温暖亲切",
                "mustEmphasize", List.of("锅底现熬", "现切牛肉"),
                "forbiddenPhrases", List.of("最好吃"),
                "allowedTags", List.of("#探店", "#火锅"),
                "sellingPoints", List.of("免费停车")));

        assertThat(text)
                .contains("门店：旗舰店")
                .contains("品牌语气（风格指令）：温暖亲切")
                .contains("必须强调")
                .contains("- 锅底现熬")
                .contains("- 现切牛肉")
                .contains("禁止表达")
                .contains("- 最好吃")
                .contains("可使用标签")
                .contains("- #探店")
                .contains("推荐卖点")
                .contains("- 免费停车");
    }

    @Test
    void nullOrEmptyBrandingRendersNothing() {
        assertThat(StoreBrandingPromptText.render(null)).isEmpty();
        assertThat(StoreBrandingPromptText.render(Map.of())).isEmpty();
    }

    @Test
    void blankAndNonListFieldsAreSkipped() {
        String text = StoreBrandingPromptText.render(Map.of(
                "storeName", "  ",
                "brandTone", "   ",
                "mustEmphasize", "不是列表",
                "forbiddenPhrases", List.of("  ", "绝对化")));

        assertThat(text).doesNotContain("门店：").doesNotContain("品牌语气").doesNotContain("必须强调");
        assertThat(text).contains("禁止表达").contains("- 绝对化");
    }
}
