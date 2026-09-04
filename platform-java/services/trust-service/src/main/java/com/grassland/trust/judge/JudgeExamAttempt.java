package com.grassland.trust.judge;

import java.time.Instant;

/**
 * 准入考试留痕（任务书 #74 卡 E）。出题/交卷各记一条（{@code answers} 为空数组=出题）；
 * 及格 → judge.exam_passed_at 落值 + admission_level=probation（Lv4）/ full 保持（Lv5）。
 */
public record JudgeExamAttempt(
        String id,
        String accountId,
        int score,
        boolean passed,
        String answersJson,
        Instant createdAt) {
}
