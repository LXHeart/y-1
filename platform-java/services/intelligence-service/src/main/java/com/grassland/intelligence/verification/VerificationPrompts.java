package com.grassland.intelligence.verification;

/**
 * 履约 AI 视觉核验 prompt 构造（草场 Slice 11 Verification Stage 3）。
 *
 * <p>给视觉模型一张推荐官上传的附件截图 + 任务上下文，要求其判断截图是否像真实、相关的平台内容证据，
 * 仅回 {@code {status,detail}} JSON。status 词表与 {@link MediaVerificationResult} 一致。
 */
final class VerificationPrompts {

    private VerificationPrompts() {
    }

    static String build(String taskTitle, String taskDescription, String platform) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是履约核验助手，负责判断一张截图是否为真实、相关的履约证据。\n\n");
        sb.append("任务标题：").append(nonNull(taskTitle)).append('\n');
        if (taskDescription != null && !taskDescription.isBlank()) {
            sb.append("任务要求：").append(taskDescription.trim()).append('\n');
        }
        if (platform != null && !platform.isBlank()) {
            sb.append("发布平台：").append(platform.trim()).append('\n');
        }
        sb.append('\n');
        sb.append("下面附一张推荐官上传的截图。请判断该截图是否像真实的、与上述任务相关的")
                .append(platform != null && !platform.isBlank() ? platform.trim() : "目标平台")
                .append("内容证据（例如真实的发布截图、互动数据、平台界面）。\n\n");
        sb.append("判定标准：\n");
        sb.append("- passed：截图明显是真实且与任务相关的平台内容证据。\n");
        sb.append("- failed：截图明显造假、与任务无关，或张冠李戴（非本任务/非目标平台的内容）。\n");
        sb.append("- inconclusive：信息不足、画面模糊，或无法判定真伪。\n\n");
        sb.append("仅返回 JSON，不要多余解释：\n");
        sb.append("{\"status\": \"passed|failed|inconclusive\", \"detail\": \"20 字以内中文理由\"}");
        return sb.toString();
    }


    /**
     * 互动截图核验 prompt（任务书 #23 R4 / ADR-D13）：判定推荐官上传的截图是否构成有效的互动证据——
     * ① 截图内容与目标帖子匹配；② 动作状态可见（已赞/已藏/已关注标记）；③ 截图账号与申报的账号标识一致。
     */
    static String buildInteraction(String taskTitle, String taskDescription, String platform,
                                   String targetUrl, String actionType, String platformHandle,
                                   String commentText) {
        String action = switch (actionType == null ? "" : actionType) {
            case "like" -> "点赞";
            case "favorite" -> "收藏";
            case "follow" -> "关注";
            case "comment" -> "评论";
            default -> "互动";
        };
        StringBuilder sb = new StringBuilder();
        sb.append("你是履约核验助手，负责判断一张截图是否为真实、有效的互动履约证据。\n\n");
        sb.append("任务标题：").append(nonNull(taskTitle)).append('\n');
        if (taskDescription != null && !taskDescription.isBlank()) {
            sb.append("任务要求：").append(taskDescription.trim()).append('\n');
        }
        if (platform != null && !platform.isBlank()) {
            sb.append("目标平台：").append(platform.trim()).append('\n');
        }
        sb.append("互动目标链接：").append(nonNull(targetUrl)).append('\n');
        sb.append("要求的动作：已").append(action).append('\n');
        sb.append("推荐官申报的平台账号标识：").append(nonNull(platformHandle)).append('\n');
        if (commentText != null && !commentText.isBlank()) {
            sb.append("推荐官申报的评论内容：").append(commentText.trim()).append('\n');
        }
        sb.append("\n下面附一张推荐官上传的截图。请逐项判断：\n");
        sb.append("1. 截图内容是否与上述互动目标（帖子/账号）匹配；\n");
        sb.append("2. 「已").append(action).append("」的动作状态是否在截图中可见（如已点亮的高亮标记、评论已发出）；\n");
        sb.append("3. 执行动作的账号是否与申报的账号标识一致。\n");
        if (commentText != null && !commentText.isBlank()) {
            sb.append("4. 截图中可见的评论内容是否与申报的评论内容一致（语义一致即可，不要求逐字）。\n");
        }
        sb.append('\n');
        sb.append("判定标准：\n");
        sb.append("- passed：三项均成立，截图是真实的已").append(action).append("证据。\n");
        sb.append("- failed：任一项明显不成立（目标不符、未").append(action).append("、账号不一致，或截图明显造假）。\n");
        sb.append("- inconclusive：截图模糊、信息不足，或无法确认任一项。\n\n");
        sb.append("仅返回 JSON，不要多余解释：\n");
        sb.append("{\"status\": \"passed|failed|inconclusive\", \"detail\": \"20 字以内中文理由\"}");
        return sb.toString();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value.trim();
    }
}
