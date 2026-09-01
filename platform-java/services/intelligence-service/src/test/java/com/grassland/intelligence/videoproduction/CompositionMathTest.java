package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 任务书 #64 卡8：合成纯函数（对齐规划/zoompan/字幕滤镜/SRT 构建）。 */
@DisplayName("Composition math and SRT")
class CompositionMathTest {

    @Test
    @DisplayName("对齐：音频超视频 >8% → atempo（≤1.3）压音频后对齐")
    void audioOverflowAppliesAtempo() {
        // 音频 6s / 视频 5s → 差 20% >8% → atempo=1.2 → 目标 5s
        CompositionMath.Alignment alignment = CompositionMath.planAlignment(5.0, 6.0, 5);
        assertThat(alignment.atempo()).isEqualTo(1.2);
        assertThat(alignment.targetSeconds()).isEqualTo(5.0);
        assertThat(alignment.padSeconds()).isZero();

        // 超 1.3 上限钳制：音频 10s / 视频 5s → atempo=1.3 → 目标 ≈7.692s
        CompositionMath.Alignment clamped = CompositionMath.planAlignment(5.0, 10.0, 5);
        assertThat(clamped.atempo()).isEqualTo(1.3);
        assertThat(clamped.targetSeconds()).isCloseTo(7.692, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @DisplayName("对齐：音频略长（≤8%）与音频更短 → 视频 tpad 克隆末帧补齐到音频时长")
    void slightOverflowPadsVideo() {
        // 差 4% ≤8%：不用 atempo，视频补 0.2s
        CompositionMath.Alignment slight = CompositionMath.planAlignment(5.0, 5.2, 5);
        assertThat(slight.atempo()).isNull();
        assertThat(slight.targetSeconds()).isEqualTo(5.2);
        assertThat(slight.padSeconds()).isEqualTo(0.2);

        // 音频更短 → 视频截断到音频时长（pad=0）
        CompositionMath.Alignment shorter = CompositionMath.planAlignment(6.0, 4.0, 5);
        assertThat(shorter.atempo()).isNull();
        assertThat(shorter.targetSeconds()).isEqualTo(4.0);
        assertThat(shorter.padSeconds()).isZero();
    }

    @Test
    @DisplayName("对齐：无配音 → 取镜头计划时长")
    void noAudioUsesPlannedSeconds() {
        CompositionMath.Alignment alignment = CompositionMath.planAlignment(2.0, null, 5);
        assertThat(alignment.atempo()).isNull();
        assertThat(alignment.targetSeconds()).isEqualTo(5);
        assertThat(alignment.padSeconds()).isEqualTo(3.0);
    }

    @Test
    @DisplayName("zoompan：奇数镜推近、偶数镜拉远")
    void zoompanDirections() {
        assertThat(CompositionMath.zoompanExpression(1)).contains("zoom+");
        assertThat(CompositionMath.zoompanExpression(3)).contains("zoom+");
        assertThat(CompositionMath.zoompanExpression(2)).contains("zoom-");
        assertThat(CompositionMath.zoompanExpression(4)).contains("1.20");
    }

    @Test
    @DisplayName("字幕滤镜：fontsdir + force_style 定死一套默认样式（Inter/13/白字黑边/MarginV60）")
    void subtitleFilterStyle() {
        String filter = CompositionMath.subtitleFilter("subs.srt", "fonts");
        assertThat(filter).startsWith("subs.srt:fontsdir=fonts:force_style=");
        assertThat(filter).contains("FontName=Inter");
        assertThat(filter).contains("FontSize=13");
        assertThat(filter).contains("MarginV=60");
        assertThat(filter).contains("PrimaryColour=&H00FFFFFF");
        assertThat(filter).contains("OutlineColour=&H00000000");
    }

    @Test
    @DisplayName("SRT：时间戳格式、序号、绝对时间轴平移；cues 缺失时按 §4.4 现切")
    void srtBuilding() {
        assertThat(SrtBuilder.timestamp(0)).isEqualTo("00:00:00,000");
        assertThat(SrtBuilder.timestamp(3_723_456)).isEqualTo("01:02:03,456");

        String srt = SrtBuilder.buildSrt(List.of(
                new SrtBuilder.Cue("第一句", 0, 2000),
                new SrtBuilder.Cue("第二句", 2000, 5000)));
        assertThat(srt).contains("1\n00:00:00,000 --> 00:00:02,000\n第一句");
        assertThat(srt).contains("2\n00:00:02,000 --> 00:00:05,000\n第二句");

        // 无 cues JSON → 旁白现切（8 字 → 2 块 cues 覆盖 2000ms）
        List<SrtBuilder.Cue> fallback = SrtBuilder.parseCues(null, "老王面馆，现熬骨汤");
        assertThat(fallback).extracting(SrtBuilder.Cue::text)
                .containsExactly("老王面馆", "现熬骨汤");
        assertThat(fallback.getLast().endMs()).isEqualTo(2000);
    }

    @Test
    @DisplayName("锚定图回落：0=无锚定复用相邻（前向优先），全 0 回落第 1 张")
    void anchorFallback() {
        VideoShot noAnchor = shot(2, 0);
        VideoShot anchored = shot(1, 2);
        List<String> images = List.of("IMG_A", "IMG_B", "IMG_C");
        assertThat(VideoCompositionService.anchorImageFor(anchored, images)).isEqualTo("IMG_B");
        assertThat(VideoCompositionService.anchorImageFor(noAnchor, images)).isEqualTo("IMG_B");
        assertThat(VideoCompositionService.anchorImageFor(shot(1, 0), images)).isEqualTo("IMG_A");
        assertThat(VideoCompositionService.anchorImageFor(shot(9, 0), List.of("ONLY"))).isEqualTo("ONLY");
    }

    private static VideoShot shot(int seq, int anchor) {
        return new VideoShot(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), seq, "画面",
                "旁白", 5, "固定机位", anchor, "提示词", VideoShot.STATUS_DRAFT, null, null);
    }
}
