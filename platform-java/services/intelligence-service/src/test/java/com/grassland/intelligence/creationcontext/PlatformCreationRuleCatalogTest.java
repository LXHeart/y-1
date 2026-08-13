package com.grassland.intelligence.creationcontext;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlatformCreationRuleCatalogTest {
    @Test
    void loadsAllPlatformsFromSharedContract() {
        List<String> platforms = List.of(
                "xiaohongshu", "douyin", "dianping", "kuaishou", "wechat-channels",
                "bilibili", "wechat-official", "zhihu", "moments");

        for (String platform : platforms) {
            Map<String, Object> snapshot = PlatformCreationRuleCatalog.snapshot(platform, "graphic");
            assertThat(snapshot)
                    .containsEntry("version", "2026-08-06")
                    .containsEntry("platform", platform)
                    .containsKey("minChars")
                    .containsKey("maxChars")
                    .containsKey("structureHints");
        }
    }
}
