package com.grassland.intelligence.verification;

import java.util.List;

/**
 * 履约 AI 视觉核验聚合结果（草场 Slice 11 Verification Stage 3）。
 *
 * <p>{@code status} 为本次 AI 视觉核验的聚合态，按 {@code failed > inconclusive > passed}
 * 聚合（任一附件 failed→failed；否则任一 inconclusive→inconclusive；全 passed→passed）。
 * marketplace 在 Stage 4 据此合并 link_reachability 后再做整体履约核验聚合。{@code results}
 * 保留每张附件的明细，供商家面板展示。
 *
 * @param status  聚合 tri-state（passed / failed / inconclusive）
 * @param results 每张附件的核验明细（顺序与请求 mediaIds 一致）
 */
public record VerificationAnalysis(String status, List<MediaVerificationResult> results) {

    public VerificationAnalysis {
        results = List.copyOf(results);
    }
}
