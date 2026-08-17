package com.grassland.intelligence.contentsafety;

import static org.assertj.core.api.Assertions.assertThat;

import com.grassland.intelligence.contentsafety.SafetyReport.Finding;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 确定性层单测（任务书 #34 B1 / ADR-D16 D3）：各类目命中、例外不误报、index 正确、
 * 空文本零 findings、大文本性能（≤10k 字符毫秒级）。
 */
class ContentSafetyCheckerTest {

    @Test
    @DisplayName("验收 #1：极限词/违规承诺/导流三类各自命中，带位置与建议")
    void findsAbsoluteClaimsFalsePromisesAndDiversion() {
        String text = "这家是全城最好吃的面包店，无效退款，加微信 abc12345 详聊";
        List<Finding> findings = ContentSafetyChecker.check(text);

        assertThat(findings).extracting(Finding::category)
                .contains("absolute_claims", "false_promises", "diversion");
        Finding absolute = findings.stream()
                .filter(f -> f.category().equals("absolute_claims")).findFirst().orElseThrow();
        assertThat(absolute.match()).isEqualTo("最好吃");
        assertThat(absolute.index()).isEqualTo(text.indexOf("最好吃"));
        assertThat(absolute.severity()).isEqualTo("medium");
        assertThat(absolute.advice()).contains("极限词");
        assertThat(absolute.deep()).isFalse();

        Finding diversion = findings.stream()
                .filter(f -> f.category().equals("diversion")).findFirst().orElseThrow();
        assertThat(diversion.severity()).isEqualTo("low");
    }

    @Test
    @DisplayName("验收 #1：「第一时间」「最新」不误报（例外表）")
    void exceptionsSuppressFalsePositives() {
        assertThat(ContentSafetyChecker.check("看到消息第一时间赶去探店，这是我最新的感受")).isEmpty();
        assertThat(ContentSafetyChecker.check("第一次去就被种草了")).isEmpty();
        assertThat(ContentSafetyChecker.check("最后一天营业，赶紧冲")).isEmpty();
        // 例外只豁免词组语境：真极限词仍命中
        assertThat(ContentSafetyChecker.check("全城销量第一的面包店"))
                .anyMatch(f -> f.category().equals("absolute_claims"));
        assertThat(ContentSafetyChecker.check("这就是顶级"))
                .anyMatch(f -> f.match().equals("顶级"));
        assertThat(ContentSafetyChecker.check("讲清顶级食材之源，而不是宣传产品顶级"))
                .filteredOn(f -> f.match().equals("顶级"))
                .singleElement()
                .extracting(Finding::index)
                .isEqualTo("讲清顶级食材之源，而不是宣传产品顶级".lastIndexOf("顶级"));
    }

    @Test
    @DisplayName("类目覆盖：high 三类 + pattern 正则（微信号/保证收益）")
    void coversHighSeverityCategoriesAndPatterns() {
        assertThat(ContentSafetyChecker.check("这是代考替考服务"))
                .anyMatch(f -> f.category().equals("illegal") && f.severity().equals("high"));
        assertThat(ContentSafetyChecker.check("加vx：seller_2026"))
                .anyMatch(f -> f.category().equals("diversion"));
        assertThat(ContentSafetyChecker.check("保证月收益翻倍"))
                .anyMatch(f -> f.category().equals("false_promises"));
    }

    @Test
    @DisplayName("空文本/纯正常文本零 findings")
    void emptyAndCleanTextYieldNothing() {
        assertThat(ContentSafetyChecker.check(null)).isEmpty();
        assertThat(ContentSafetyChecker.check("")).isEmpty();
        assertThat(ContentSafetyChecker.check("   ")).isEmpty();
        assertThat(ContentSafetyChecker.check("一家开在巷子里的咖啡店，手冲很稳定，环境安静适合办公。"))
                .isEmpty();
    }

    @Test
    @DisplayName("index 指向命中起始位置（多命中各自正确）")
    void indexPointsAtMatchStart() {
        String text = "开头正常。顶级的服务体验，结尾正常";
        List<Finding> findings = ContentSafetyChecker.check(text);
        assertThat(findings).isNotEmpty();
        for (Finding finding : findings) {
            assertThat(text.indexOf(finding.match(), finding.index())).isEqualTo(finding.index());
        }
    }

    @Test
    @DisplayName("性能：10k 字符毫秒级（L1 是生成必跑的底线，不能拖慢流式出口）")
    void tenThousandCharsStayMillisecondLevel() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("今天去了一家新开的咖啡店，拿铁奶泡绵密，环境安静适合办公，")
                    .append("店员推荐的桂花拿铁有淡淡花香，性价比不错，推荐给附近上班的朋友。");
        }
        String text = sb.toString();
        assertThat(text.length()).isGreaterThan(10_000);
        long start = System.nanoTime();
        List<Finding> findings = ContentSafetyChecker.check(text);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(findings).isEmpty();
        assertThat(elapsedMs).as("10k 字符 L1 检查应 < 100ms，实测 %dms", elapsedMs).isLessThan(100);
    }

    @Test
    @DisplayName("词库版本可读且非空（随快照冻结的字段）")
    void lexiconVersionExposed() {
        assertThat(ContentSafetyLexicon.version()).isEqualTo("lexicon-v1");
    }
}
