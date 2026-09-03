package com.grassland.intelligence.videoproduction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * StoryboardParser 对 LLM NDJSON 偏离形态的容忍矩阵（2026-09-03 MiniMax-M3 实跑炸在逐行
 * readTree 后补）：每条用例对应一种已观测/高概率的模型偏离，镜头字段校验仍严格。
 */
@DisplayName("StoryboardParser 偏离容忍")
class StoryboardParserTest {

    private static final String SHOT = "{\"seq\":%d,\"visual\":\"画面%d\",\"narration\":\"旁白%d\","
            + "\"plannedSeconds\":5,\"cameraMove\":\"固定机位\",\"anchorImageIndex\":1,\"prompt\":\"p%d\"}";

    private static String shot(int seq) {
        return String.format(SHOT, seq, seq, seq, seq);
    }

    @Test
    @DisplayName("标准三行 NDJSON → 3 镜，seq 重编号（回归）")
    void plainNdjson() {
        List<StoryboardParser.ParsedShot> shots = StoryboardParser.parse(shot(7) + "\n" + shot(2) + "\n" + shot(9), 2);
        assertThat(shots).extracting(StoryboardParser.ParsedShot::seq).containsExactly(1, 2, 3);
        assertThat(shots.getFirst().visual()).isEqualTo("画面7");
    }

    @Test
    @DisplayName("前导寒暄 + 结尾解释散文行 → 跳过，镜头完整")
    void toleratesSurroundingProse() {
        String content = "好的，以下是为您安排的分镜：\n\n" + shot(1) + "\n" + shot(2) + "\n以上就是全部镜头，希望符合预期。";
        List<StoryboardParser.ParsedShot> shots = StoryboardParser.parse(content, 1);
        assertThat(shots).hasSize(2);
    }

    @Test
    @DisplayName("编号列表前缀（1. / 2、）→ 剥前缀解析")
    void stripsNumberedListPrefix() {
        List<StoryboardParser.ParsedShot> shots = StoryboardParser.parse("1. " + shot(1) + "\n2、" + shot(2), 1);
        assertThat(shots).hasSize(2);
    }

    @Test
    @DisplayName("整段 JSON 数组（单行/跨行）→ 逐元素展开")
    void expandsJsonArray() {
        List<StoryboardParser.ParsedShot> single = StoryboardParser.parse("[" + shot(1) + "," + shot(2) + "]", 1);
        assertThat(single).hasSize(2);

        List<StoryboardParser.ParsedShot> pretty = StoryboardParser.parse(
                "[\n  " + shot(1) + ",\n  " + shot(2) + "\n]", 1);
        assertThat(pretty).hasSize(2);
    }

    @Test
    @DisplayName("跨行 pretty-print 对象 → 逐行累积到 JSON 闭合")
    void accumulatesMultilineObject() {
        String content = """
                {
                  "seq": 1,
                  "visual": "招牌特写",
                  "narration": "老王面馆",
                  "plannedSeconds": 5,
                  "cameraMove": "缓慢推近",
                  "anchorImageIndex": 1,
                  "prompt": "p"
                }
                {
                  "seq": 2,
                  "visual": "后厨实拍",
                  "narration": "现切鲜面",
                  "plannedSeconds": 5,
                  "cameraMove": "固定机位",
                  "anchorImageIndex": 0,
                  "prompt": "p"
                }""";
        List<StoryboardParser.ParsedShot> shots = StoryboardParser.parse(content, 1);
        assertThat(shots).extracting(StoryboardParser.ParsedShot::visual)
                .containsExactly("招牌特写", "后厨实拍");
    }

    @Test
    @DisplayName("{\"shots\":[...]} 整体包裹 → 展开")
    void unwrapsShotsContainer() {
        List<StoryboardParser.ParsedShot> shots =
                StoryboardParser.parse("{\"shots\":[" + shot(1) + "," + shot(2) + "]}", 1);
        assertThat(shots).hasSize(2);
    }

    @Test
    @DisplayName("代码围栏 ```json 包裹 → 剥围栏（回归）")
    void stripsCodeFence() {
        String content = "```json\n" + shot(1) + "\n```";
        assertThat(StoryboardParser.parse(content, 1)).hasSize(1);
    }

    @Test
    @DisplayName("残缺对象后跟完整对象 → 残缺判死丢弃，完整保留")
    void dropsDeadBufferBeforeNextObject() {
        String content = "{\"seq\":1,\"visual\":\"残\n" + shot(2);
        List<StoryboardParser.ParsedShot> shots = StoryboardParser.parse(content, 1);
        assertThat(shots).hasSize(1);
        assertThat(shots.getFirst().visual()).isEqualTo("画面2");
    }

    @Test
    @DisplayName("纯散文零镜头 → 抛镜头数越界")
    void rejectsPureProse() {
        assertThatThrownBy(() -> StoryboardParser.parse("我觉得这个视频应该分三个部分来讲。", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("分镜镜头数须在 1-30 之间");
    }

    @Test
    @DisplayName("永远闭不上的残缺输出 → 抛无法解析的行")
    void rejectsUnresolvableBuffer() {
        assertThatThrownBy(() -> StoryboardParser.parse("{\"seq\":1,\"visual\":\"残", 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("分镜输出包含无法解析的行");
    }

    @Test
    @DisplayName("字段校验仍严格：visual 空 / anchor 越界 / 31 镜（回归）")
    void fieldValidationRemainsStrict() {
        assertThatThrownBy(() -> StoryboardParser.parse("{\"seq\":1,\"narration\":\"x\"}", 1))
                .hasMessage("分镜画面描述不能为空");
        assertThatThrownBy(() -> StoryboardParser.parse(
                "{\"seq\":1,\"visual\":\"x\",\"anchorImageIndex\":3}", 1))
                .hasMessage("分镜锚定图序号超出范围");
        StringBuilder overflow = new StringBuilder();
        for (int seq = 1; seq <= 31; seq++) {
            overflow.append(shot(seq)).append('\n');
        }
        assertThatThrownBy(() -> StoryboardParser.parse(overflow.toString(), 1))
                .hasMessage("分镜镜头数须在 1-30 之间");
    }

    @Test
    @DisplayName("plannedSeconds 钳到 4-6、cameraMove 白名单外回落固定机位（回归）")
    void clampsAndDefaults() {
        String content = "{\"seq\":1,\"visual\":\"x\",\"plannedSeconds\":9,\"cameraMove\":\"飞天\",\"anchorImageIndex\":0}";
        StoryboardParser.ParsedShot shot = StoryboardParser.parse(content, 0).getFirst();
        assertThat(shot.plannedSeconds()).isEqualTo(6);
        assertThat(shot.cameraMove()).isEqualTo("固定机位");
    }

    @Test
    @DisplayName("对象后同行尾随散文 → readTree 宽松取首值，不炸")
    void toleratesTrailingProseOnSameLine() {
        List<StoryboardParser.ParsedShot> shots = StoryboardParser.parse(shot(1) + " 以上就是分镜。", 1);
        assertThat(shots).hasSize(1);
    }
}
