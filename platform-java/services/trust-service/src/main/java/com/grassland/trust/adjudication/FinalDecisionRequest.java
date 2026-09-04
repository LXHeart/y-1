package com.grassland.trust.adjudication;

import java.util.Set;

/**
 * 客服终审请求（草场 Epic 6 Slice 6C Phase C-2 / HLD §11.2 + 任务书 #74 卡 F，终审三选）。
 *
 * <p>{@code action}：{@code maintain}=维持原判 / {@code overturn}=改判 / {@code retrial}=发回重审。
 * maintain / overturn 须携带 {@code decision}（for_merchant / for_recommender）；retrial 不需要。
 *
 * <p>缺省兼容：老调用只传 {@code decision} 时视 {@code action=overturn}（治理台旧表单不破）。
 * endpoint 另校验断言 {@code reauthenticatedAt} 近期性（MFA）。
 */
public record FinalDecisionRequest(String action, String decision) {

    public static final String ACTION_MAINTAIN = "maintain";
    public static final String ACTION_OVERTURN = "overturn";
    public static final String ACTION_RETRIAL = "retrial";

    /** 既有单参调用方兼容：只传 decision = 改判（overturn）。 */
    public FinalDecisionRequest(String decision) {
        this(decision == null || decision.isBlank() ? null : ACTION_OVERTURN, decision);
    }

    public FinalDecisionRequest {
        if (action == null || action.isBlank()) {
            action = ACTION_OVERTURN;
        }
        action = action.trim().toLowerCase(java.util.Locale.ROOT);
        if (!Set.of(ACTION_MAINTAIN, ACTION_OVERTURN, ACTION_RETRIAL).contains(action)) {
            throw new IllegalArgumentException("action must be maintain, overturn, or retrial");
        }
        boolean needs = !ACTION_RETRIAL.equals(action);
        if (needs && (decision == null || decision.isBlank())) {
            throw new IllegalArgumentException("decision is required");
        }
    }

    public boolean needsDecision() {
        return !ACTION_RETRIAL.equals(action);
    }
}
