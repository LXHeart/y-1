package com.grassland.intelligence.bilibili;

import java.util.Map;

/**
 * Bilibili 分析编排结果（草场 Slice 13 Stage 5）。{@link Java} = Java 侧已处理（content 提取或 recreation 场景，
 * 返回归一 data）；{@link Fallback} = 当前用例不归 Java 处理（DASH / 超 {@code maxSingleSegmentSeconds} 需切片 /
 * 非 qwen provider），由 controller 整体回落 legacy（转发 cookie，legacy 扣积分 + FFmpeg/Coze）。
 *
 * <p>sealed 使 controller 可穷举 switch：Java→包 {@code {success:true,data}}；Fallback→转发 legacy 响应。
 */
public sealed interface BilibiliAnalysisOutcome permits BilibiliAnalysisOutcome.Java, BilibiliAnalysisOutcome.Fallback {

    /** Java 侧分析完成；{@code data} 为归一后的内容提取结果（6 字段）或复刻场景结果（scenes）。 */
    record Java(Map<String, Object> data) implements BilibiliAnalysisOutcome {}

    /** 不归 Java 处理 → controller 回落 legacy。 */
    record Fallback() implements BilibiliAnalysisOutcome {}
}
