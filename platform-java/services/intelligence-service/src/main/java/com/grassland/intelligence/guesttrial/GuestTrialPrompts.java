package com.grassland.intelligence.guesttrial;

import com.grassland.intelligence.ai.ChatMessage;
import java.util.List;

/**
 * 游客试用专用 prompt（任务书 #36 B1）：**复制裁剪**自既有三处（titles 裁自 article-generation、
 * score 裁自 creation-assistant、image-review 裁自 image-analysis），不改既有 prompt 文件本体。
 * 全部要求固定 JSON 输出，便于 result 帧结构化下发（前端直接渲染）。
 */
final class GuestTrialPrompts {

    private GuestTrialPrompts() {}

    /** article-titles：输入主题 → 5 个候选标题（含钩子）。 */
    static List<ChatMessage> titles(String topic) {
        return List.of(ChatMessage.system("""
                你是小红书/抖音风格的种草文案标题助手。根据主题生成 5 个候选标题，每个附一句钩子（hook）。
                仅返回 JSON：{"titles":[{"title":"...","hook":"..."}]}，不要多余解释。"""),
                ChatMessage.user("主题：" + topic));
    }

    /** content-score：粘贴文案 → 5 维评分 + 优化建议（镜像创作助手评分维度）。 */
    static List<ChatMessage> score(String content) {
        return List.of(ChatMessage.system("""
                你是种草内容教练，按 5 个维度给文案打分（1-10 分）：标题吸引力、关键词覆盖、结构清晰度、\
                互动引导、真实感。每个维度给一句改进建议，再给一句总评。
                仅返回 JSON：{"dimensions":[{"key":"title","label":"标题吸引力","score":8,"advice":"..."}],\
                "overall":"..."}，不要多余解释。"""),
                ChatMessage.user("待评分文案：\n" + content));
    }

    /** image-review：一张探店照片 → 一段探店点评草稿（多模态，image dataUri 由调用方拼 ContentPart）。 */
    static String imageReviewInstruction() {
        return """
                你是探店点评作者。根据这张门店/菜品照片写一段 100 字左右的探店点评草稿，口语化、有细节、真实感强。
                仅返回 JSON：{"review":"..."}，不要多余解释。""";
    }
}
