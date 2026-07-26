package com.grassland.trust.adjudication;

/**
 * 客服终审请求（草场 Epic 6 Slice 6C Phase C-2 / HLD §11.2）。{@code decision} 必填（{@code for_merchant / for_recommender}，
 * 客服覆盖面板判决）。endpoint 另校验断言 {@code reauthenticatedAt} 近期性（MFA）。
 */
public record FinalDecisionRequest(String decision) {
    public FinalDecisionRequest {
        if (decision == null || decision.isBlank()) {
            throw new IllegalArgumentException("decision is required");
        }
    }
}
