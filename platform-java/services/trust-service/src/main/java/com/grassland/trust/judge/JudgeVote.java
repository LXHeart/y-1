package com.grassland.trust.judge;

import java.time.Instant;

/**
 * 一名审判官在一轮中的投票（草场 Epic 6 Slice 6C）。{@code UNIQUE(dispute_id, round, judge_account_id)} 保每官每轮一票幂等。
 */
public record JudgeVote(
        String disputeId,
        int round,
        String judgeAccountId,
        String vote,
        String rationale,
        Instant votedAt) {}
