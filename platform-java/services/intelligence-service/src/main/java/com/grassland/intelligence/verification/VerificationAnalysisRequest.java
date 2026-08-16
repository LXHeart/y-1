package com.grassland.intelligence.verification;

import java.util.List;
import java.util.UUID;

/**
 * 履约 AI 视觉核验请求（草场 Slice 11 Verification Stage 3）。marketplace 以服务断言经
 * {@code POST /api/verification/analyze} 提交待核验附件 media id 列表 + 任务上下文。
 *
 * @param mediaIds        待核验的履约附件 media_reference id（purpose=engagement_attachment + active + 未过期）
 * @param taskTitle       任务标题（必填，核验相关性基准）
 * @param taskDescription 任务要求，可空
 * @param platform        发布平台，可空（如 douyin / xiaohongshu）
 */
public record VerificationAnalysisRequest(
        List<UUID> mediaIds,
        String taskTitle,
        String taskDescription,
        String platform,
        /** 任务书 #23：visual（默认，零改动）| interaction（互动截图核验）。 */
        String mode,
        /** interaction 模式上下文：被互动的目标链接 / 动作类型（like|favorite|follow）/ 推荐官平台账号标识。 */
        String targetUrl,
        String actionType,
        String platformHandle) {

    public VerificationAnalysisRequest {
        mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
        mode = mode == null || mode.isBlank() ? "visual" : mode.trim().toLowerCase();
    }

    /** 兼容任务书 #23 之前的四参构造调用方（既有测试）；visual 模式。 */
    public VerificationAnalysisRequest(List<UUID> mediaIds, String taskTitle,
                                       String taskDescription, String platform) {
        this(mediaIds, taskTitle, taskDescription, platform, null, null, null, null);
    }

    public boolean interactionMode() {
        return "interaction".equals(mode);
    }
}
