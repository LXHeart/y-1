package com.grassland.marketplace.taskcatalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskRequirementsTest {

    @Test
    void normalizesWhitespaceDuplicatesAndNullCollections() {
        TaskRequirements requirements = new TaskRequirements(
                "  双人招牌套餐  ",
                List.of(" 门店名 ", "招牌菜", "门店名"),
                null, null, null,
                List.of("播放量截图", "播放量截图"),
                List.of("发布链接"));

        assertThat(requirements.productServiceInfo()).isEqualTo("双人招牌套餐");
        assertThat(requirements.mustInclude()).containsExactly("门店名", "招牌菜");
        assertThat(requirements.forbiddenContent()).isEmpty();
        assertThat(requirements.metricRequirements()).containsExactly("播放量截图");
    }

    @Test
    void rejectsInvertedPublishWindow() {
        assertThatThrownBy(() -> new TaskRequirements(
                null, List.of(), List.of(),
                Instant.parse("2026-08-20T12:00:00Z"),
                Instant.parse("2026-08-19T12:00:00Z"),
                List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("发布时间结束不能早于开始时间");
    }
}
