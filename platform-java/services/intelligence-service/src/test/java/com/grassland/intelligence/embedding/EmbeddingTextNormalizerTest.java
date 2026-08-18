package com.grassland.intelligence.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.contentlibrary.AssetCategory;
import com.grassland.intelligence.contentlibrary.ContentAsset;
import com.grassland.intelligence.contentlibrary.LibraryType;
import com.grassland.intelligence.media.MediaChecksums;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 任务书 #33：索引文本规范化——固定行格式、标签排序、空字段省略、空白折叠与 ASCII 小写。 */
class EmbeddingTextNormalizerTest {

    private static ContentAsset asset(String title, List<String> tags, String source, String license) {
        return new ContentAsset(UUID.randomUUID(), UUID.randomUUID(), LibraryType.PUBLIC, AssetCategory.CAMPAIGN,
                "acct-1", null, title, tags, "image/png", 10L, null, null, 1,
                source, license, null, null, null,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), null);
    }

    @Test
    void buildsCanonicalLinesWithSortedTags() {
        EmbeddingTextNormalizer.NormalizedText result =
                EmbeddingTextNormalizer.forAsset(asset("开业  大促", List.of("咖啡", "开业"), "平台活动", "cc-by"));
        assertThat(result.text()).isEqualTo(
                "title: 开业 大促\n"
                        + "category: campaign\n"
                        + "tags: 咖啡 开业\n"
                        + "source: 平台活动\n"
                        + "license_scope: cc-by");
        assertThat(result.contentHash())
                .isEqualTo(MediaChecksums.sha256(result.text().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void omitsEmptyFields() {
        EmbeddingTextNormalizer.NormalizedText result =
                EmbeddingTextNormalizer.forAsset(asset("门店 环境", List.of(), null, null));
        assertThat(result.text()).isEqualTo("title: 门店 环境\ncategory: campaign");
    }

    @Test
    void collapsesWhitespaceAndLowercasesAscii() {
        assertThat(EmbeddingTextNormalizer.normalize("  Hello   WORLD  ")).isEqualTo("hello world");
        assertThat(EmbeddingTextNormalizer.normalize("开业\t促销\n新店")).isEqualTo("开业 促销 新店");
    }

    @Test
    void sameContentProducesSameHashAndDifferentContentDiffers() {
        EmbeddingTextNormalizer.NormalizedText first =
                EmbeddingTextNormalizer.forAsset(asset("开业 促销", List.of("咖啡"), "平台活动", "cc-by"));
        EmbeddingTextNormalizer.NormalizedText second =
                EmbeddingTextNormalizer.forAsset(asset("开业 促销", List.of("咖啡"), "平台活动", "cc-by"));
        EmbeddingTextNormalizer.NormalizedText third =
                EmbeddingTextNormalizer.forAsset(asset("开业 促销", List.of("宠物"), "平台活动", "cc-by"));
        assertThat(first.contentHash()).isEqualTo(second.contentHash());
        assertThat(first.contentHash()).isNotEqualTo(third.contentHash());
    }
}
