package com.grassland.intelligence.cardseries;

/**
 * 系列图卡 prompt 契约（任务书 #54 S1.2）。
 *
 * <p>文字渲染策略（实现期定死）：图像模型只出**无文字插画底图**（diffusion 中文文字渲染不可靠），
 * 卡片标题/要点由前端 canvas 叠加排版（#43 封面工作台文字排版合成同款先例）；因此 layout 描述词
 * 引导的是「画面分区与文字留白」，prompt 明确禁止画面出现文字/水印。
 */
final class CardSeriesPrompts {

    private CardSeriesPrompts() {
    }

    static String systemPlan(CardSeriesService.PlanInput input) {
        String palette = input.paletteText() == null ? "" : "配色基调：" + input.paletteText() + "。";
        return """
                你是面向「%s」平台的系列图文卡片策划，把用户提供的长图文内容拆解为 %d 张风格统一的卡片计划。
                视觉风格：%s。画面布局：%s。%s
                规则：
                1. 第 1 张是封面卡：从内容中提炼最强钩子（悬念/数字/利益点）作主标题。
                2. 其余卡片按内容的自然段落/要点顺序拆分，每张承载一个独立要点，不互相重复、不遗漏关键信息。
                3. 每张卡 bullets 不超过 5 条，每条不超过 20 字，尽量沿用原文表述。
                4. illustration 是给图像生成模型的一句话画面描述（构图、元素、氛围），必须遵守视觉风格与画面布局，且不得包含文字排版要求。
                5. caption 是发布时该卡的配文，从原文对应段落提炼，口语化，可带 emoji。
                只输出 JSON（可包 ```json 代码块）：
                {"cards":[{"title":"...","bullets":["..."],"illustration":"...","caption":"..."}]}
                cards 数量必须等于 %d。""".formatted(
                platformLabel(input.platform()), input.cardCount(), input.styleText(), input.layoutText(),
                palette, input.cardCount());
    }

    static String userPlan(CardSeriesService.PlanInput input) {
        return "以下是已生成的长图文内容，请拆解为卡片计划：\n\n" + input.content();
    }

    /** 单卡生图 prompt：风格锁定 + 画面布局 + 首卡 revised_prompt 风格锚（第 2 卡起）。 */
    static String cardPrompt(CardSeriesService.GenerateInput input, CardSeriesService.CardPlan card,
            int index, String styleAnchor) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("生成一张竖版社交媒体卡片的插画底图。");
        if (index == 0) {
            prompt.append("这是系列封面卡，画面需有最强视觉冲击力。");
        } else {
            prompt.append("这是系列第 ").append(index + 1).append(" 张内容卡。");
            if (styleAnchor != null && !styleAnchor.isBlank()) {
                prompt.append("为保持系列视觉一致，参考首图的风格基调：").append(truncate(styleAnchor, 400)).append("。");
            }
        }
        prompt.append("视觉风格：").append(input.styleText())
                .append("。画面布局：").append(input.layoutText());
        if (input.paletteText() != null && !input.paletteText().isBlank()) {
            prompt.append("。配色基调：").append(input.paletteText());
        }
        prompt.append("。画面内容：").append(truncate(card.illustration(), 500))
                .append("。画面中不得出现任何文字、字母、数字和水印；按布局要求为标题与要点留出文字区域。");
        return prompt.toString();
    }

    /** 平台标签（仅 prompt 语义用，不做能力校验——合法性由前端矩阵保证）。 */
    private static String platformLabel(String platform) {
        return switch (platform == null ? "" : platform) {
            case "xiaohongshu" -> "小红书";
            case "douyin" -> "抖音";
            case "dianping" -> "大众点评";
            case "moments" -> "微信朋友圈";
            case "wechat-official" -> "微信公众号";
            case "zhihu" -> "知乎";
            default -> "社交媒体";
        };
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
