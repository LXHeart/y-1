package com.grassland.trust.adjudication;

/**
 * 审判投票请求（草场 Epic 6 Slice 6C / HLD §5.5 + 任务书 #74 卡 C，拍板 D2）。
 *
 * <p>{@code vote} 必填，取值 {@code for_merchant / for_recommender / abstain}（合法性在 controller 经
 * {@link com.grassland.trust.judge.VoteChoice#fromDb} 校验，非法 → 400）。
 *
 * <p>{@code rationale} 任务书 #74 卡 C 起<b>必填且 ≥20 字</b>（含弃权——弃权也是「实际投出」，
 * 终局后随判例脱敏展示），不足 → IllegalArgumentException → 400。无 DDL 改动（rationale 列已存在，
 * 存量可空行保留原样）。
 */
public record CastVoteRequest(String vote, String rationale) {

    /** 投票理由最小长度（拍板 D2：必填 ≥20 字）。 */
    public static final int RATIONALE_MIN_LENGTH = 20;

    public CastVoteRequest {
        if (vote == null || vote.isBlank()) {
            throw new IllegalArgumentException("vote is required");
        }
        if (rationale == null || rationale.trim().length() < RATIONALE_MIN_LENGTH) {
            throw new IllegalArgumentException("投票理由必填且不少于 20 字");
        }
    }
}
