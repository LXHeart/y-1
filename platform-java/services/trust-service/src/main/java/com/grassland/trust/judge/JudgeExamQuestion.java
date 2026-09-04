package com.grassland.trust.judge;

import java.time.Instant;

/**
 * 准入考试题（任务书 #74 卡 E）。治理台维护，UPDATE 即 version+1（乐观锁）。
 * {@code answerIndex} 不进用户端出题响应（只进判分）。
 */
public record JudgeExamQuestion(
        String id,
        String category,
        String question,
        String optionsJson,
        int answerIndex,
        boolean active,
        long version,
        Instant createdAt) {
}
