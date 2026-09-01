package com.grassland.intelligence.contentsafety;

import com.grassland.intelligence.contentsafety.ContentSafetyFixController.FixRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 内容修复 prompt 拼装（任务书 #63 4.1 定死文本，逐字落地）。纯函数无状态；
 * 三个插槽句（形态/平台特征/文风）缺省时整体省略该行，编号槽位保持固定（2/3/4）。
 */
final class ContentSafetyFixPrompts {

    /** category 显示名（与前端 SafetyFindingsPanel 的 CATEGORY_LABEL 同源）。 */
    private static final Map<String, String> CATEGORY_LABELS = Map.ofEntries(
            Map.entry("absolute_claims", "广告法极限词"),
            Map.entry("false_promises", "违规承诺"),
            Map.entry("diversion", "导流联系"),
            Map.entry("politics", "涉政敏感"),
            Map.entry("porn", "低俗内容"),
            Map.entry("illegal", "涉嫌违法"),
            Map.entry("platform_unwanted", "平台不推荐表达"),
            Map.entry("platform_overlay", "平台规则"),
            Map.entry("industry_overlay", "行业规则"),
            Map.entry("duplicate_content", "内容重复度"),
            Map.entry("low_originality", "低原创度"));

    private ContentSafetyFixPrompts() {}

    static String system(FixRequest request) {
        List<String> lines = new ArrayList<>();
        lines.add("你是商业内容修复助手。基于问题清单改写用户提供的正文,输出修复后的完整正文。");
        lines.add("");
        lines.add("硬性要求:");
        lines.add("1. 只解决问题清单指出的问题,未涉及部分保持原样——逐字保留事实、数字、专有名词、人名地名。");
        String formSentence = formSentence(request.contentForm());
        if (formSentence != null) lines.add("2. " + formSentence);
        String platformSentence = platformSentence(request.platform());
        if (platformSentence != null) lines.add("3. " + platformSentence);
        String styleSentence = styleSentence(request.genre(), request.style());
        if (styleSentence != null) lines.add("4. " + styleSentence);
        lines.add("5. 问题清单含「内容重复度/低原创度」时:重排段落结构、合并或删除复读内容、"
                + "对重复表达做同义改写,并把每段的信息密度提高;不得虚构新的体验、数据或事实。");
        lines.add("6. 广告法极限词/违规承诺类问题:改为可验证的客观描述或删除该表达。");
        lines.add("7. 导流联系类问题:删除联系方式与引导私聊的表达,不加替代联系方式。");
        lines.add("8. 输出纯正文:不带任何解释、前言、markdown 代码块围栏。");
        return String.join("\n", lines);
    }

    static String user(FixRequest request) {
        StringBuilder list = new StringBuilder();
        int index = 1;
        for (FixRequest.FindingInput finding : request.findings()) {
            list.append(index++).append(". ").append(categoryLabel(finding.category()))
                    .append(":「").append(finding.match()).append("」 — ")
                    .append(finding.advice() == null ? "" : finding.advice()).append('\n');
        }
        return "待修复正文:\n" + request.text()
                + "\n\n问题清单(编号. 类别:命中/指标 — 建议):\n" + list + "\n输出修复后的完整正文。";
    }

    /** 形态句：answer/article 各一句；空/其余 → 省略（#62 的 contentForm 值域，平台无关默认文章体）。 */
    private static String formSentence(String contentForm) {
        if ("answer".equals(contentForm)) {
            return "这是知乎回答:保持结论前置、论证体结构,不得改写成文章体。";
        }
        if ("article".equals(contentForm)) {
            return "这是知乎文章:保持文章体结构。";
        }
        return null;
    }

    /**
     * 平台特征句：xiaohongshu（抖音场景 platform 值同为 xiaohongshu，由前端传参区分，不另设分支）
     * 与 zhihu 各一句；其余 → 省略。
     */
    private static String platformSentence(String platform) {
        if ("xiaohongshu".equals(platform)) {
            return "结尾的 # 话题标签行原样保留;小红书图文保持口语种草体。";
        }
        if ("zhihu".equals(platform)) {
            return "保持知乎论证体:结论前置、每句有信息增量。";
        }
        return null;
    }

    /** 文风句：genre/style 任一非空即拼装（非空项join「;」），均空 → 省略。 */
    private static String styleSentence(String genre, String style) {
        if ((genre == null || genre.isBlank()) && (style == null || style.isBlank())) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (genre != null && !genre.isBlank()) parts.add(genre.trim());
        if (style != null && !style.isBlank()) parts.add(style.trim());
        return "保持既有体裁与文风:" + String.join(";", parts) + "。";
    }

    static String categoryLabel(String category) {
        return CATEGORY_LABELS.getOrDefault(category, category);
    }
}
