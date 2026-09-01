package com.grassland.intelligence.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.contentsafety.ContentSafetyFixController.FixRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 修复 prompt 拼装（任务书 #63 4.1 定死文本）：插槽句缺省省略整行、槽位号固定、显示名问题清单。 */
class ContentSafetyFixPromptsTest {

    private static FixRequest request(String contentForm, String platform, String genre, String style) {
        return new FixRequest(
                "正文内容",
                List.of(new FixRequest.FindingInput("absolute_claims", "全网第一", "改为可验证描述")),
                platform, contentForm, genre, style);
    }

    @Test
    void systemIncludesAllSlotSentencesWithFixedNumbers() {
        String prompt = ContentSafetyFixPrompts.system(request("answer", "zhihu", "探店日记", "真诚口吻"));
        assertThat(prompt)
                .contains("2. 这是知乎回答:保持结论前置、论证体结构,不得改写成文章体。")
                .contains("3. 保持知乎论证体:结论前置、每句有信息增量。")
                .contains("4. 保持既有体裁与文风:探店日记;真诚口吻。")
                .contains("5. 问题清单含「内容重复度/低原创度」时")
                .contains("8. 输出纯正文:不带任何解释、前言、markdown 代码块围栏。");
    }

    @Test
    void systemOmitsAbsentSlotsEntirely() {
        String prompt = ContentSafetyFixPrompts.system(request(null, "xiaohongshu", null, null));
        assertThat(prompt)
                .doesNotContain("这是知乎")
                .doesNotContain("保持既有体裁与文风")
                .contains("3. 结尾的 # 话题标签行原样保留;小红书图文保持口语种草体。");
        // 槽位 2/4 缺省 → 不出现以「2. / 4. 」开头的插槽行（5-8 行编号不变）
        assertThat(prompt.lines().noneMatch(line -> line.startsWith("2. ") || line.startsWith("4. ")))
                .isTrue();
    }

    @Test
    void userBuildsNumberedListWithCategoryLabels() {
        FixRequest multi = new FixRequest(
                "待修复正文全文",
                List.of(
                        new FixRequest.FindingInput("absolute_claims", "全网第一", "改为客观描述"),
                        new FixRequest.FindingInput("low_originality", "38% 文内重复", "补充细节"),
                        new FixRequest.FindingInput("custom_category", "未知类别", "")),
                null, null, null, null);
        String prompt = ContentSafetyFixPrompts.user(multi);
        assertThat(prompt)
                .startsWith("待修复正文:\n待修复正文全文")
                .contains("问题清单(编号. 类别:命中/指标 — 建议):")
                .contains("1. 广告法极限词:「全网第一」 — 改为客观描述")
                .contains("2. 低原创度:「38% 文内重复」 — 补充细节")
                .contains("3. custom_category:「未知类别」 — ")
                .endsWith("输出修复后的完整正文。");
    }
}
