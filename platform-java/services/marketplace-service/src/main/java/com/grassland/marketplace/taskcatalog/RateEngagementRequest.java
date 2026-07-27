package com.grassland.marketplace.taskcatalog;

/**
 * 评分请求体。{@code score} 必填且须为 1-5 整数（与 DB CHECK 同口径，前置拦在边界给出可读错误，
 * 而不是让约束违例冒成 500）；{@code comment} 可选。
 */
public record RateEngagementRequest(Integer score, String comment) {

    public RateEngagementRequest {
        if (score == null || score < 1 || score > 5) {
            throw new IllegalArgumentException("score must be an integer between 1 and 5");
        }
        if (comment != null) {
            comment = comment.isBlank() ? null : comment.trim();
        }
    }
}
