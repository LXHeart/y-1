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

    private static String nonNull(String value) {
        return value == null ? "" : value.trim();
    }
}
