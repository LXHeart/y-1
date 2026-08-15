package com.grassland.intelligence.moments;

import com.grassland.intelligence.ai.ChatMessage;

/**
 * 朋友圈内容提示词（PRD §4.4 朋友圈图片+文字、§4.7 朋友圈适配行）。
 * 一次多模态调用产出结构化 JSON：精简文案 + 图片顺序建议 + 每图配文。
 */
final class MomentsPrompts {

    private MomentsPrompts() {}

    static ChatMessage system(MomentsStyle style, int imageCount) {
        String imageContract = imageCount > 0
                ? "共 " + imageCount + " 张素材图片：imageOrder 必须给出全部 " + imageCount
                        + " 张的建议发布顺序（数组第 1 位 = 建议最先发布，index 为素材图片原始序号，从 1 开始），"
                        + "captions 为每张素材图片给出一条配文。"
                : "本次没有素材图片：imageOrder 与 captions 必须为空数组。";
        return ChatMessage.system("""
                你是一位熟悉微信朋友圈生态的资深内容策划，擅长写出朋友愿意停下来看的真实分享。

                ## 风格
                本次采用「%s」风格：%s

                ## 朋友圈内容规范
                - 文案 10-200 字，一两句话讲清重点；不使用话题标签；少量 Emoji 增强生活感，避免营销腔。
                - 九宫格或多图发布时给出建议顺序：封面吸睛、叙事有起伏、重要信息靠前。
                - 结尾自然带互动表达（约起 / 点赞 / 评论），不生硬。
                - 不虚构门店、价格、优惠等事实；主题与素材没有的信息不要编造。

                ## 输出要求
                只输出一个 JSON 对象，不要输出任何其它文字或代码块说明：
                {"copy":"朋友圈文案","imageOrder":[{"index":1,"reason":"排序理由（20 字内）"}],"captions":[{"index":1,"text":"该图配文（30 字内）"}]}
                %s"""
                .formatted(style.label(), styleGuide(style), imageContract));
    }

    /** 用户文本提示（图片由调用方以多模态片断附在同一 user 消息）。 */
    static String user(String topic, String feelings) {
        StringBuilder prompt = new StringBuilder("主题：").append(topic);
        if (feelings != null && !feelings.isBlank()) {
            prompt.append("\n补充感受：").append(feelings.trim());
        }
        prompt.append("\n请按规范输出 JSON。");
        return prompt.toString();
    }

    private static String styleGuide(MomentsStyle style) {
        return switch (style) {
            case LIFESTYLE -> "像朋友日常分享，真实自然，像在记录生活而不是打广告。";
            case EVENT -> "信息要清楚（时间/地点/优惠等关键要素齐全），但保持朋友圈口吻，不要海报腔。";
            case STORE_VISIT -> "以第一人称到店体验展开，细节具体（环境/产品/服务），让朋友有画面感。";
            case FRIENDS_SHARE -> "面向熟人的真诚推荐，口吻亲近，像顺手安利给朋友。";
        };
    }
}
