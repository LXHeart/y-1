package com.grassland.intelligence.creationassistant;

import com.grassland.intelligence.ai.ChatMessage;
import java.util.List;

/**
 * 智能创作助手 prompt（草场 PRD §4.9.4/§4.9.6 / Slice 15 Stage 2）。
 *
 * <p>两个场景：
 * <ul>
 *   <li><b>评分</b>（{@link #scoreMessages}）：对用户已有内容按 5 个维度（标题吸引力/关键词/结构/
 *       互动引导/平台规范）打分（1-10）+ 每维度一句话建议。要求 LLM 输出结构化 JSON，
 *       controller 解析后逐维度发 SSE 帧（§4.9.6「评分」）。</li>
 *   <li><b>优化建议</b>（{@link #suggestMessages}）：对内容提结构化优化建议（哪些保留/哪些改），
 *       流式输出（§4.9.4「建议」）。</li>
 * </ul>
 *
 * <p>平台规范按平台差异化（小红书重 emoji/标签、知乎重深度/引用、公众号重标题/开头）。
 * 消息格式与 {@code ArticlePrompts} 对齐（system + user 两条 {@link ChatMessage}）。
 */
final class CreationAssistantPrompts {

    /** 评分维度（PRD §4.9.4 五要素）。 */
    static final String[] SCORE_DIMENSIONS = {
            "title_appeal", "keywords", "structure", "engagement", "platform_fit"
    };

    private CreationAssistantPrompts() {}

    /** 评分 prompt：要求 LLM 返回固定 JSON 结构，controller 解析后逐维度发 SSE 帧。 */
    static List<ChatMessage> scoreMessages(String content, String platform, String title) {
        String platformHint = platformHint(platform);
        String titlePart = title == null || title.isBlank() ? "（无标题，请一并评估标题缺失）" : title;
        return List.of(
                ChatMessage.system("""
                        你是一位资深内容运营专家，擅长诊断社交媒体内容质量。你的任务是对用户提交的内容按 5 个维度评分并给出建议。

                        评分维度（每项 1-10 分，10 分最高）：
                        - title_appeal：标题吸引力（是否引发点击欲、符合平台调性）
                        - keywords：关键词覆盖（是否命中目标受众搜索词、话题标签）
                        - structure：结构清晰度（开头是否抓人、段落是否分明、是否有收束）
                        - engagement：互动引导（是否诱发评论/点赞/收藏/转发）
                        - platform_fit：%s 平台规范契合度

                        **必须**只输出一个 JSON 对象，不要输出 markdown 代码块标记、不要多余文字。格式：
                        {"dimensions":[{"dimension":"title_appeal","score":8,"advice":"一句话建议"},...],"overall":7}
                        """.formatted(platformHint)),
                ChatMessage.user("""
                        请评估以下内容：
                        标题：%s
                        平台：%s

                        内容正文：
                        %s""".formatted(titlePart, platformHint, content)));
    }

    /** 优化建议 prompt：流式输出结构化建议（保留哪些/改哪些/怎么改）。 */
    static List<ChatMessage> suggestMessages(String content, String platform, String title) {
        String platformHint = platformHint(platform);
        String titlePart = title == null || title.isBlank() ? "" : "标题：" + title + "\n";
        return List.of(
                ChatMessage.system("""
                        你是一位内容优化顾问。根据用户内容给出具体、可执行的优化建议，而非泛泛而谈。

                        要求：
                        - 先指出内容的 1-2 个亮点（值得保留）
                        - 再按优先级列出 3-5 条改进建议，每条含「问题→具体改法」
                        - 结合 %s 平台的调性和算法偏好
                        - 语气鼓励、直接、不说套话
                        """.formatted(platformHint)),
                ChatMessage.user("""
                        请为以下内容提供优化建议：
                        %s平台：%s

                        %s""".formatted(titlePart, platformHint, content)));
    }

    /** 平台调性提示（未知平台给通用提示）。 */
    private static String platformHint(String platform) {
        if (platform == null || platform.isBlank()) {
            return "通用社交媒体";
        }
        String p = platform.trim().toLowerCase();
        return switch (p) {
            case "xiaohongshu", "xhs" -> "小红书（重 emoji、话题标签、种草口吻、首图标题）";
            case "zhihu" -> "知乎（重专业深度、引用来源、长文逻辑）";
            case "wechat", "wechat-official", "wechat_official" -> "微信公众号（重标题打开率、开头钩子、排版）";
            case "douyin" -> "抖音（重前 3 秒钩子、口语化、字幕节奏）";
            case "bilibili", "b站" -> "B站（重弹幕梗、知识增量、人格化）";
            case "dianping" -> "大众点评（重真实体验、菜品/环境细节、客观评分）";
            case "kuaishou" -> "快手（重接地气、老铁口吻、生活感）";
            case "moments", "朋友圈" -> "朋友圈（重简短、个人色彩、互动感）";
            default -> platform;
        };
    }
}
