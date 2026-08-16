package com.grassland.identity.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.grassland.identity.auth.IdentityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 门店营销字段归一化单测（任务书 #24）。帽镜像 marketplace {@code TaskRequirements.items()}：
 * trim/去空白/去重，≤20 项、单项 ≤300 字；文本 blank → null。
 */
class StoreMarketingFieldsTest {

    @Test
    void itemsTrimsDeduplicatesAndSkipsBlanks() {
        List<String> result = StoreMarketingFields.items(
                Arrays.asList(" 火锅 ", "火锅", "", "  ", null, "烤肉"), "主营品类");
        assertThat(result).containsExactly("火锅", "烤肉");
    }

    @Test
    void nullOrEmptyItemsMeanClearSemantics() {
        assertThat(StoreMarketingFields.items(null, "主营品类")).isEmpty();
        assertThat(StoreMarketingFields.items(List.of(), "主营品类")).isEmpty();
        assertThat(StoreMarketingFields.items(new ArrayList<>(List.of("  ", "")), "主营品类")).isEmpty();
    }

    @Test
    void itemsOverTwentyRejected() {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            values.add("品类" + i);
        }
        assertThatThrownBy(() -> StoreMarketingFields.items(values, "主营品类"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("最多 20 项");
    }

    @Test
    void singleItemOverThreeHundredCharsRejected() {
        String tooLong = "字".repeat(301);
        assertThatThrownBy(() -> StoreMarketingFields.items(List.of(tooLong), "推荐卖点"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("单项最多 300 字");
    }

    @Test
    void optionalTextBlankToNullAndTrimmed() {
        assertThat(StoreMarketingFields.optional(null, 500, "品牌语气")).isNull();
        assertThat(StoreMarketingFields.optional("   ", 500, "品牌语气")).isNull();
        assertThat(StoreMarketingFields.optional(" 温暖亲切 ", 500, "品牌语气")).isEqualTo("温暖亲切");
    }

    @Test
    void optionalTextCapsEnforced() {
        assertThatThrownBy(() -> StoreMarketingFields.optional(
                "字".repeat(501), StoreMarketingFields.MAX_BRAND_TONE_LENGTH, "品牌语气"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("最多 500 字");
        assertThatThrownBy(() -> StoreMarketingFields.optional(
                "字".repeat(51), StoreMarketingFields.MAX_PRICE_RANGE_LENGTH, "价格区间"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("最多 50 字");
        assertThatThrownBy(() -> StoreMarketingFields.optional(
                "字".repeat(1001), StoreMarketingFields.MAX_VISIT_NOTES_LENGTH, "到店提示"))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("最多 1000 字");
    }

    @Test
    void averageSpendRejectsNegative() {
        assertThat(StoreMarketingFields.averageSpend(null)).isNull();
        assertThat(StoreMarketingFields.averageSpend(0)).isZero();
        assertThat(StoreMarketingFields.averageSpend(8800)).isEqualTo(8800);
        assertThatThrownBy(() -> StoreMarketingFields.averageSpend(-1))
                .isInstanceOf(IdentityException.class)
                .hasMessageContaining("人均消费不能为负数");
    }
}
