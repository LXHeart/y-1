package com.grassland.intelligence.douyin;

import java.util.Map;

/**
 * Douyin 分析编排结果（草场 GL-P3-MEDIA-001）。{@link Java} = Java 侧已处理（短视频单段直发 video_url，
 * 返回归一 data）；{@link Fallback} = 当前用例不归 Java 处理（超 {@code maxSingleSegmentSeconds} 需 FFmpeg
 * 切片 / 非 qwen provider / 未配 PUBLIC_BACKEND_ORIGIN），由 controller 整体回落 legacy
 * （转发 cookie，legacy 扣积分 + FFmpeg 切片/analysis-media + Coze/Qwen）。
 *
 * <p>sealed 使 controller 可穷举 switch：Java→包 {@code {success:true,data}}；Fallback→转发 legacy 响应。
 */
public sealed interface DouyinAnalysisOutcome permits DouyinAnalysisOutcome.Java, DouyinAnalysisOutcome.Fallback {

    /** Java 侧分析完成；{@code data} 为归一后的内容提取结果（6 字段）。 */
    record Java(Map<String, Object> data) implements DouyinAnalysisOutcome {}

    /** 不归 Java 处理 → controller 回落 legacy。 */
    record Fallback() implements DouyinAnalysisOutcome {}
}
