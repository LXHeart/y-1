package com.grassland.trust.adjudication;

/**
 * 审判投票请求（草场 Epic 6 Slice 6C / HLD §5.5）。{@code vote} 必填，取值 {@code for_merchant / for_recommender / abstain}
 * （合法性在 controller 经 {@link com.grassland.trust.judge.VoteChoice#fromDb} 校验，非法 → 400）；{@code rationale} 可空。
 */
public record CastVoteRequest(String vote, String rationale) {
    public CastVoteRequest {
        if (vote == null || vote.isBlank()) {
            throw new IllegalArgumentException("vote is required");
        }
    }
}
