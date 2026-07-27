package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频制作脚本 prompt（忠实移植 legacy {@code video-production.service.ts} 的 {@code SCRIPT_SYSTEM_PROMPT}）。
 * system 注入视频风格/行业；user 由店铺信息文本 + 1-9 个 image_url content parts 组成。
 */
final class VideoScriptPrompts {

    private static final String SYSTEM_TEMPLATE = """
            你是一位专业的短视频脚本策划师，专门为实体店铺制作推广视频脚本。

            ## 任务
            根据用户提供的店铺信息和素材图片，生成一段适合短视频平台（15秒）的推广视频脚本。

            ## 输出要求
            1. 脚本必须分为 3-5 个镜头
            2. 每个镜头包含：画面描述、旁白/字幕文字、预估时长
            3. 总时长控制在 15 秒以内
            4. 语言简洁有力，突出店铺特色和吸引力
            5. 适合 {videoStyle} 风格
            6. 行业类型：{industryType}

            ## 输出格式
            直接输出脚本内容，包含镜头描述和旁白文字。不需要 JSON 格式，纯文本即可。

            示例格式：
            【镜头1】(3秒) 画面：[描述画面内容]
            旁白：[旁白文字]

            【镜头2】(4秒) 画面：[描述画面内容]
            旁白：[旁白文字]

            …""";

    private VideoScriptPrompts() {}

    static ChatMessage system(String videoStyle, String industryType) {
        return ChatMessage.system(SYSTEM_TEMPLATE
                .replace("{videoStyle}", videoStyle)
                .replace("{industryType}", industryType));
    }

    static ChatMessage user(VideoProductionController.ScriptRequest request) {
        List<String> lines = new ArrayList<>();
        lines.add("店铺名称：" + request.shopName());
        if (request.shopAddress() != null && !request.shopAddress().isEmpty()) {
            lines.add("店铺地址：" + request.shopAddress());
        }
        if (request.shopDescription() != null && !request.shopDescription().isEmpty()) {
            lines.add("店铺描述：" + request.shopDescription());
        }
        if (request.customPrompt() != null && !request.customPrompt().isEmpty()) {
            lines.add("用户要求：" + request.customPrompt());
        }
        lines.add("\n请根据以上信息和 " + request.images().size() + " 张素材图片，生成推广视频脚本。");

        List<ContentPart> parts = new ArrayList<>();
        parts.add(ContentPart.text(String.join("\n", lines)));
        request.images().stream()
                .map(VideoScriptPrompts::imageDataUrl)
                .map(ContentPart::image)
                .forEach(parts::add);
        return ChatMessage.user(parts);
    }

    /** legacy 兼容：已是 data: URI 原样；裸 base64 默认视为 JPEG。 */
    private static String imageDataUrl(String image) {
        return image.startsWith("data:") ? image : "data:image/jpeg;base64," + image;
    }
}
