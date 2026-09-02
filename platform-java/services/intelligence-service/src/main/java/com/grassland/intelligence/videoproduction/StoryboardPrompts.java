package com.grassland.intelligence.videoproduction;

import com.grassland.intelligence.ai.ChatMessage;
import com.grassland.intelligence.ai.ContentPart;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构化分镜 prompt（任务书 #64 卡3，§4.2 逐字）。system 要求 LLM 逐行输出 NDJSON
 * （每行一个镜头对象）；不要求 LLM 输出 meta 行——meta 来自请求，由后端发首帧。
 *
 * <p>user 消息沿用 {@link VideoScriptPrompts} 的店铺信息文本 + 图片 image_url parts 拼装方式，
 * 追加一行「目标总时长：N 秒」。
 */
final class StoryboardPrompts {

    /** §4.2 运镜词表（卡4 编辑下拉与 LLM 输出共用同一值集）。 */
    static final List<String> CAMERA_MOVES = List.of(
            "固定机位", "缓慢推近", "缓慢拉远", "左右横移", "跟随运镜", "环绕",
            "俯拍下摇", "仰拍上摇", "特写切换", "手持感轻晃", "升降镜头", "旋转");

    private static final String SYSTEM_TEMPLATE = """
            你是短视频分镜导演。根据店铺信息、图片与要求，输出营销短视频的结构化分镜。
            输出格式：逐行输出 JSON（NDJSON），每行一个镜头对象，字段：
            {"seq":整数,"visual":"画面描述","narration":"该镜头旁白","plannedSeconds":4到6的整数,\
            "cameraMove":"运镜","anchorImageIndex":1基图片序号或0,"prompt":"给视频模型的生成提示词,≤100字"}
            硬性约束：
            1. 每镜 prompt 必须主动描述画面人物正在执行的动作（如：端起菜品、看向镜头）。
            2. 每镜 prompt 必须重复关键道具与人物状态（前镜出现的产品、服装、动作要延续，不得凭空消失或出现）。
            3. 连续镜头之间动作与道具保持连续性；不改变叙事结构；保留镜头节奏。
            4. 服装、发型等人物特征在每镜 prompt 持续描述，避免前后外观漂移。
            5. 不过度约束：留出空间让模型自由发挥，避免堆砌限制导致穿帮。
            6. 首镜（narration 与画面）按「开头 2 秒让人停住」设计钩子。
            7. 画面主体（人物+产品）总数 ≤5。
            8. cameraMove 从以下选：固定机位/缓慢推近/缓慢拉远/左右横移/跟随运镜/环绕/俯拍下摇/仰拍上摇/特写切换/手持感轻晃/升降镜头/旋转。
            9. anchorImageIndex 优先把用户图片全部用上（每图至多连续 2 镜复用），确无合适图的镜头填 0。
            10. 总 plannedSeconds 尽量贴近 {targetDurationSeconds} 秒（±10%）；镜头数 3–30。
            只输出 NDJSON 行，不输出任何解释或代码围栏。""";

    private static final String MOMENTS_ADAPTATION = """

            ## 朋友圈适配
            这条视频将发布在微信朋友圈：面向熟人社交关系，语气自然亲近、像顺手分享；
            节奏轻快、信息精简，结尾自然带互动表达（约朋友来店 / 点赞 / 评论），不要广告腔。""";

    private StoryboardPrompts() {}

    static ChatMessage system(int targetDurationSeconds, String targetPlatform) {
        String prompt = SYSTEM_TEMPLATE
                .replace("{targetDurationSeconds}", String.valueOf(targetDurationSeconds));
        if ("moments".equals(targetPlatform == null ? "" : targetPlatform.trim())) {
            prompt = prompt + MOMENTS_ADAPTATION;
        }
        return ChatMessage.system(prompt);
    }

    /** 店铺信息 + 目标时长行 + 1-9 个 image_url parts（与 VideoScriptPrompts.user 同构）。 */
    static ChatMessage user(VideoProductionController.StoryboardRequest request) {
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
        String referenceLine = referenceStructureLine(request.referenceShotStructure());
        if (referenceLine != null) {
            lines.add(referenceLine);
        }
        lines.add("\n请根据以上信息和 " + request.images().size() + " 张素材图片，输出结构化分镜。");
        lines.add("目标总时长：" + request.targetDurationSeconds() + " 秒");

        List<ContentPart> parts = new ArrayList<>();
        parts.add(ContentPart.text(String.join("\n", lines)));
        request.images().stream()
                .map(StoryboardPrompts::imageDataUrl)
                .map(ContentPart::image)
                .forEach(parts::add);
        return ChatMessage.user(parts);
    }

    /**
     * 任务书 #66 E1 §3 文案（逐字）：带参考分析时注入「参考结构」段；无引用返回 null（零变化）。
     * 复刻红线已内嵌文案（「不得复刻其内容与文案」），system 的连续性约束照常生效。
     */
    static String referenceStructureLine(VideoProductionController.StoryboardRequest.ReferenceShotStructure ref) {
        if (ref == null || ref.safeShots().isEmpty()) {
            return null;
        }
        String durations = ref.safeShots().stream()
                .map(shot -> shot.durationSeconds() == null ? "?"
                        : String.valueOf(shot.durationSeconds().intValue()))
                .collect(java.util.stream.Collectors.joining(", "));
        StringBuilder line = new StringBuilder("参考结构（仅参考节奏与结构，不得复刻其内容与文案）：")
                .append("镜头时长序列 [").append(durations).append("] 秒");
        if (ref.hookAtSeconds() != null) {
            line.append("；开场钩子位于第 ").append(ref.hookAtSeconds().intValue()).append(" 秒");
        }
        return line.append("。").toString();
    }

    /** legacy 兼容：已是 data: URI 原样；裸 base64 默认视为 JPEG。 */
    private static String imageDataUrl(String image) {
        return image.startsWith("data:") ? image : "data:image/jpeg;base64," + image;
    }
}
