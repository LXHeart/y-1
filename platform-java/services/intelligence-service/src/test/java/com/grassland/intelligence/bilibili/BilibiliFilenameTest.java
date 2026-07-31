package com.grassland.intelligence.bilibili;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link BilibiliFilename} 单测（移植 legacy bilibili-filename.ts 行为）。 */
@DisplayName("BilibiliFilename 下载文件名构建")
class BilibiliFilenameTest {

    @Test
    @DisplayName("三者皆空 → 默认名")
    void allEmptyReturnsDefault() {
        assertThat(BilibiliFilename.buildDownloadFilename(null, null, null))
                .isEqualTo("bilibili-video.mp4");
    }

    @Test
    @DisplayName("title-author-videoId 拼接 + .mp4")
    void joinsParts() {
        assertThat(BilibiliFilename.buildDownloadFilename("标题", "作者", "BV1x"))
                .isEqualTo("标题-作者-BV1x.mp4");
    }

    @Test
    @DisplayName("非法字符 / 空白 / 连续短横归一为单个 -")
    void sanitizesInvalidChars() {
        assertThat(BilibiliFilename.buildDownloadFilename("a<b>:\"c /d\\e|f?g*h", null, null))
                .isEqualTo("a-b-c-d-e-f-g-h.mp4");
    }

    @Test
    @DisplayName("base 超过 80 字符截断（去尾部短横）后补 .mp4")
    void truncatesOver80() {
        String longTitle = "a".repeat(90);
        String filename = BilibiliFilename.buildDownloadFilename(longTitle, null, null);
        assertThat(filename).endsWith(".mp4");
        assertThat(filename.length()).isEqualTo(84); // 80 base + ".mp4"
    }

    @Test
    @DisplayName("normalize：清洗 + .mp4；空 → null")
    void normalizes() {
        assertThat(BilibiliFilename.normalizeDownloadFilename("x/y z")).isEqualTo("x-y-z.mp4");
        assertThat(BilibiliFilename.normalizeDownloadFilename("ok.mp4")).isEqualTo("ok.mp4");
        assertThat(BilibiliFilename.normalizeDownloadFilename(null)).isNull();
        assertThat(BilibiliFilename.normalizeDownloadFilename("   ")).isNull();
    }
}
