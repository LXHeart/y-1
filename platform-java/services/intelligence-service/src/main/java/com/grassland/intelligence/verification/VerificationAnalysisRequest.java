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
        String platform) {

    public VerificationAnalysisRequest {
        mediaIds = mediaIds == null ? List.of() : List.copyOf(mediaIds);
    }
}
