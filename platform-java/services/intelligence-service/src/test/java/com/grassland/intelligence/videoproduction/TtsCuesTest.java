package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 任务书 #64 卡5：cues 切块与按字数比例分布（§4.4）。 */
@DisplayName("TTS cues algorithm")
class TtsCuesTest {

    @Test
    @DisplayName("按标点切分，每块 ≤20 字，时长按字数比例分布且覆盖全程")
    void splitsByPunctuationAndDistributesByCharShare() {
        String narration = "老王面馆，每天现熬骨汤。二十年的老味道，街坊都爱！";
        List<TtsCues.Cue> cues = TtsCues.build(narration, 10_000);

        assertThat(cues).extracting(TtsCues.Cue::text)
                .containsExactly("老王面馆", "每天现熬骨汤", "二十年的老味道", "街坊都爱");
        assertThat(cues).allSatisfy(cue -> assertThat(cue.text().length()).isLessThanOrEqualTo(20));
        // 首块从 0 开始，末块收在总时长
        assertThat(cues.getFirst().startMs()).isZero();
        assertThat(cues.getLast().endMs()).isEqualTo(10_000);
        // 相邻块首尾相接
        for (int index = 1; index < cues.size(); index++) {
            assertThat(cues.get(index).startMs()).isEqualTo(cues.get(index - 1).endMs());
        }
        // 「老王面馆」4/21 字 → round(4*10000/21)=1905
        assertThat(cues.getFirst().endMs()).isEqualTo(1905);
    }

    @Test
    @DisplayName("无标点长句按 20 字硬切；空旁白与零时长返回空")
    void hardSplitsLongRunsAndHandlesEmpty() {
        List<TtsCues.Cue> cues = TtsCues.build("一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十", 6000);
        assertThat(cues).hasSize(2);
        assertThat(cues.getFirst().text()).hasSize(20);

        assertThat(TtsCues.build("", 5000)).isEmpty();
        assertThat(TtsCues.build("旁白", 0)).isEmpty();
        assertThat(TtsCues.build(null, 5000)).isEmpty();
    }

    @Test
    @DisplayName("JSON 形态与卡8 消费契约一致（text/startMs/endMs）")
    void jsonShapeMatchesContract() {
        String json = TtsCues.toJson(TtsCues.build("第一句。第二句！", 4000));
        assertThat(json).startsWith("[").contains("\"text\":\"第一句\"").contains("\"startMs\":0")
                .contains("\"endMs\":2000").contains("\"text\":\"第二句\"").contains("\"endMs\":4000");
    }

    @Test
    @DisplayName("sandbox 时长 = 字数/4 秒，最少 1 秒")
    void sandboxDurationRule() {
        assertThat(SandboxTtsProvider.durationMsFor("一二三四五六七八")).isEqualTo(2000);
        assertThat(SandboxTtsProvider.durationMsFor("两字")).isEqualTo(1000);
        assertThat(SandboxTtsProvider.durationMsFor(null)).isEqualTo(1000);
        assertThat(SandboxTtsProvider.durationMsFor("")).isEqualTo(1000);
    }

    @Test
    @DisplayName("纯 Java 正弦波是合法 16-bit PCM wav（44 字节头 + 采样数据）")
    void sineWavBytesAreValidPcm() {
        byte[] wav = SandboxTtsProvider.sineWavBytes(1000);
        assertThat(wav.length).isGreaterThan(44);
        assertThat(new String(wav, 0, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        // 1 秒 × 24000 Hz × 2 字节
        assertThat(wav.length).isEqualTo(44 + 24_000 * 2);
    }
}
