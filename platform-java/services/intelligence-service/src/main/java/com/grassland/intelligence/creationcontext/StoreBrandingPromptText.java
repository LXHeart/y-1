package com.grassland.intelligence.creationcontext;

import java.util.List;
import java.util.Map;

/**
 * 门店品牌上下文 prompt 渲染（任务书 #24 Stage 4）。
 *
 * <p>把冻结的 storeBranding 快照翻成明确的创作指令，拼进各工作流的冻结上下文系统消息：
 * 必须强调 → 要求覆盖；禁止表达 → 明确禁止；品牌语气 → 风格指令；可使用标签 → 建议标签池。
 * 空快照返回空串（组织级任务/门店无品牌资料），不改变既有 prompt 形状。
 */
public final class StoreBrandingPromptText {

    private StoreBrandingPromptText() {
    }

    /** 渲染品牌约束指令文本；null/空快照返回空串。 */
    public static String render(Map<String, Object> storeBranding) {
        if (storeBranding == null || storeBranding.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n【门店品牌约束（创作开始时冻结，必须严格遵守）】");
        String storeName = text(storeBranding.get("storeName"));
        if (storeName != null) {
            sb.append("\n门店：").append(storeName);
        }
        String brandTone = text(storeBranding.get("brandTone"));
        if (brandTone != null) {
            sb.append("\n品牌语气（风格指令）：").append(brandTone)
                    .append("——全文语气与措辞必须符合该品牌语气。");
        }
        List<String> mustEmphasize = strings(storeBranding.get("mustEmphasize"));
        if (!mustEmphasize.isEmpty()) {
            sb.append("\n必须强调（以下内容必须逐条在创作中体现）：");
            mustEmphasize.forEach(item -> sb.append("\n- ").append(item));
        }
        List<String> forbidden = strings(storeBranding.get("forbiddenPhrases"));
        if (!forbidden.isEmpty()) {
            sb.append("\n禁止表达（以下内容及其近义变体严禁出现在创作中）：");
            forbidden.forEach(item -> sb.append("\n- ").append(item));
        }
        List<String> allowedTags = strings(storeBranding.get("allowedTags"));
        if (!allowedTags.isEmpty()) {
            sb.append("\n可使用标签（建议标签池，仅可从中选用）：");
            allowedTags.forEach(item -> sb.append("\n- ").append(item));
        }
        List<String> sellingPoints = strings(storeBranding.get("sellingPoints"));
        if (!sellingPoints.isEmpty()) {
            sb.append("\n推荐卖点（优先围绕以下卖点创作）：");
            sellingPoints.forEach(item -> sb.append("\n- ").append(item));
        }
        return sb.toString();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isBlank() ? null : s;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(item -> String.valueOf(item).trim())
                .toList();
    }
}
