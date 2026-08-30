package com.grassland.intelligence.article;

import com.grassland.intelligence.ai.ChatMessage;
import java.util.Map;

/**
 * 文章生成 prompt（忠实移植 legacy {@code qwen-provider.ts} 的 ARTICLE_TITLES/OUTLINE/CONTENT_PROMPTS，
 * 3 类 × 3 平台 = 9 段）。迁入草场 intelligence 后单一真相源在此。
 *
 * <p>平台与 legacy 一致：{@code wechat}/{@code zhihu}/{@code xiaohongshu}，默认 wechat。
 * 用户消息格式与 legacy 对齐（titles=主题；outline=主题+标题；content=主题+标题+大纲）。
 */
final class ArticlePrompts {

    /** 文章平台（legacy 字符串小写；default wechat）。 */
    public enum Platform {
        WECHAT("wechat"),
        ZHIHU("zhihu"),
        XIAOHONGSHU("xiaohongshu");

        private final String key;

        Platform(String key) {
            this.key = key;
        }

        /** 按 legacy 字符串解析（小写、未知/缺失→wechat）。 */
        static Platform fromKey(String raw) {
            if (raw == null) {
                return WECHAT;
            }
            String k = raw.trim().toLowerCase();
            for (Platform p : values()) {
                if (p.key.equals(k)) {
                    return p;
                }
            }
            return WECHAT;
        }
    }

    private static final Map<Platform, String> TITLES = Map.of(
            Platform.WECHAT, """
                    你是一位专业的微信公众号爆款标题策划师。根据用户提供的主题，生成 5 个有吸引力的文章标题选项。

                    要求：
                    - 标题要能引起读者好奇心和点击欲望
                    - 风格多样化：疑问句、数字列表、故事感、对比冲突、情感共鸣等
                    - 适合微信公众号阅读场景，标题直接决定打开率
                    - 每个标题附带一行 hook 说明（简短描述为什么这个标题有效）

                    你必须且只能返回以下 JSON 格式，不要返回任何其他文字：
                    {
                      "titles": [
                        {"title": "标题文字", "hook": "这个标题有效的原因"}
                      ]
                    }""",
            Platform.ZHIHU, """
                    你是一位专业的知乎回答标题策划师。根据用户提供的主题，生成 5 个有吸引力的回答标题选项。

                    要求：
                    - 标题要有知乎社区的问题感和讨论性
                    - 风格多样化：提问式、经验分享式、观点输出式、数据支撑式等
                    - 适合知乎的理性讨论氛围，体现专业度和洞察力
                    - 每个标题附带一行 hook 说明（简短描述为什么这个标题有效）

                    你必须且只能返回以下 JSON 格式，不要返回任何其他文字：
                    {
                      "titles": [
                        {"title": "标题文字", "hook": "这个标题有效的原因"}
                      ]
                    }""",
            Platform.XIAOHONGSHU, """
                    你是一位专业的小红书爆款笔记标题策划师。根据用户提供的主题，生成 5 个有吸引力的笔记标题选项。

                    要求：
                    - 标题要有强烈种草感和个人体验感
                    - 风格多样化：种草安利、踩雷避坑、合集盘点、对比测评等
                    - 适合小红书社区，简短有力，直接击中用户需求
                    - 适当使用 emoji 增加视觉吸引力，但不过度
                    - 每个标题附带一行 hook 说明（简短描述为什么这个标题有效）

                    你必须且只能返回以下 JSON 格式，不要返回任何其他文字：
                    {
                      "titles": [
                        {"title": "标题文字", "hook": "这个标题有效的原因"}
                      ]
                    }""");

    private static final Map<Platform, String> OUTLINE = Map.of(
            Platform.WECHAT, """
                    你是一位专业的微信公众号文章结构策划师。请根据用户提供的主题和选定的标题，生成一份详细的文章大纲。

                    要求：
                    - 使用 Markdown 格式
                    - 包含 3-5 个主要章节，每章有 2-4 个要点
                    - 结构清晰，层层递进
                    - 每个要点简明扼要，1-2 句话
                    - 开头要有引人入胜的引入，结尾要有有力的总结

                    直接输出大纲内容，不要输出任何额外说明。""",
            Platform.ZHIHU, """
                    你是一位专业的知乎回答结构策划师。请根据用户提供的主题和选定的标题，生成一份详细的回答大纲。

                    要求：
                    - 使用 Markdown 格式
                    - 采用"先亮观点，再展开论证"的回答体结构
                    - 包含 3-5 个论点或分析角度，每点有数据或案例支撑
                    - 结构严谨，逻辑自洽，体现专业分析能力
                    - 每个要点简明扼要，1-2 句话
                    - 开头给出明确的结论或立场，结尾有总结升华

                    直接输出大纲内容，不要输出任何额外说明。""",
            Platform.XIAOHONGSHU, """
                    你是一位专业的小红书笔记结构策划师。请根据用户提供的主题和选定的标题，生成一份简洁的笔记大纲。

                    要求：
                    - 使用简洁的要点列表格式
                    - 笔记偏短，不需要太复杂的结构
                    - 包含 3-5 个核心要点或推荐理由
                    - 每个要点一句话，口语化、有画面感
                    - 开头要有吸引注意的引入（场景/痛点/惊喜），结尾要有行动号召
                    - 适合在 500-1000 字内展开的内容量

                    直接输出大纲内容，不要输出任何额外说明。""");

    private static final Map<Platform, String> CONTENT = Map.of(
            Platform.WECHAT, """
                    你是一位专业的微信公众号爆款文章写手。请根据用户提供的主题、标题和编辑后的大纲，撰写一篇完整的文章。

                    要求：
                    - 使用 Markdown 格式，章节标题使用 ##
                    - 语言生动有感染力，避免干巴巴的说明文风格
                    - 每个章节内容充实，结合具体案例或数据
                    - 适当使用加粗标记关键信息
                    - 总字数 1500-3000 字
                    - 结尾要有总结和行动号召

                    直接输出文章内容，不要输出任何额外说明。""",
            Platform.ZHIHU, """
                    你是一位专业的知乎高赞回答写手。请根据用户提供的主题、标题和编辑后的大纲，撰写一篇完整的回答。

                    要求：
                    - 使用 Markdown 格式，适当使用加粗标记关键信息
                    - 开头直接亮明观点或给出结论，让读者知道你要说什么
                    - 论证过程中引用具体数据、案例或个人经历，避免空洞说理
                    - 语气理性但不冰冷，可以带个人观点和态度
                    - 适当使用编号列表和引用格式增强可读性
                    - 总字数 1500-3000 字
                    - 结尾要有总结和延伸思考，激发讨论

                    直接输出回答内容，不要输出任何额外说明。""",
            Platform.XIAOHONGSHU, """
                    你是一位专业的小红书种草笔记写手。请根据用户提供的主题、标题和编辑后的大纲，撰写一篇完整的笔记。

                    要求：
                    - 使用口语化的聊天语气，像在跟闺蜜/好朋友分享
                    - 适当使用 emoji 增加氛围感，但不过度（每段 1-2 个即可）
                    - 多用短句和换行，段落之间留白，方便手机阅读
                    - 多写具体的体验细节和使用感受，少写空泛的形容词
                    - 可以用分隔线（---）划分不同部分
                    - 总字数 500-1000 字，不要写太长
                    - 结尾加一行总结推荐和互动引导（如"姐妹们冲！""你们觉得呢？"）

                    直接输出笔记内容，不要输出任何额外说明。""");

    private ArticlePrompts() {}

    static ChatMessage titlesSystem(Platform platform) {
        return titlesSystem(platform, null);
    }

    static ChatMessage outlineSystem(Platform platform) {
        return ChatMessage.system(prompt(OUTLINE, platform));
    }

    static ChatMessage contentSystem(Platform platform) {
        return contentSystem(platform, null, null);
    }

    /**
     * titles system + 标题套路注入段（任务书 #57 决策 D）：追加进同一条 system 消息文本，
     * 不新增 system 消息（BYOK 任意端点对多条 system 兼容性不可假设）。
     * 注入段带优先级句——小红书 base 的「风格多样化」要求与「全候选遵循同一套路」冲突时以本段为准。
     */
    static ChatMessage titlesSystem(Platform platform, com.grassland.intelligence.creationstyle.CreationStyleSkill.SkillPrompt formula) {
        String base = prompt(TITLES, platform);
        if (formula == null) {
            return ChatMessage.system(base);
        }
        return ChatMessage.system(base + "\n\n【标题套路：" + formula.name() + "】\n"
                + "在满足上述输出格式要求的前提下，全部 5 个候选标题都必须遵循以下套路"
                + "（与前文「风格多样化」的要求冲突时，以本段为准）：\n" + formula.promptContent());
    }

    /**
     * content system + 体裁/文风注入段（任务书 #57 决策 D）：体裁在前、文风在后，各一段；
     * 文风段带优先级句——须能覆盖小红书 base 默认的「闺蜜口吻」语气。
     */
    static ChatMessage contentSystem(Platform platform,
            com.grassland.intelligence.creationstyle.CreationStyleSkill.SkillPrompt genre,
            com.grassland.intelligence.creationstyle.CreationStyleSkill.SkillPrompt style) {
        StringBuilder sb = new StringBuilder(prompt(CONTENT, platform));
        if (genre != null) {
            sb.append("\n\n【内容体裁：").append(genre.name()).append("】\n")
                    .append("正文必须遵循以下体裁结构要求：\n").append(genre.promptContent());
        }
        if (style != null) {
            sb.append("\n\n【文风口吻：").append(style.name()).append("】\n")
                    .append("全文语言风格必须遵循以下口吻要求（与前文默认语气冲突时，以本段为准）：\n")
                    .append(style.promptContent());
        }
        return ChatMessage.system(sb.toString());
    }

    /** 取平台 prompt；null/未知平台回退 wechat（{@code Map.of} 不允许 null 键，故先归一）。 */
    private static String prompt(Map<Platform, String> map, Platform platform) {
        Platform p = (platform == null) ? Platform.WECHAT : platform;
        return map.getOrDefault(p, map.get(Platform.WECHAT));
    }

    /** titles 用户消息：{@code 主题：{topic}}。 */
    static ChatMessage titlesUser(String topic) {
        return ChatMessage.user("主题：" + topic);
    }

    /** outline 用户消息：{@code 主题：{topic}\n标题：{title}}。 */
    static ChatMessage outlineUser(String topic, String title) {
        return ChatMessage.user("主题：" + topic + "\n标题：" + title);
    }

    /** content 用户消息：{@code 主题：{topic}\n标题：{title}\n\n大纲：\n{outline}}。 */
    static ChatMessage contentUser(String topic, String title, String outline) {
        return ChatMessage.user("主题：" + topic + "\n标题：" + title + "\n\n大纲：\n" + outline);
    }
}
