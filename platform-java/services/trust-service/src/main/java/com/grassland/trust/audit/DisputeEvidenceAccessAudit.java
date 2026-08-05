package com.grassland.trust.audit;

import java.time.Instant;

/**
 * 证据查看审计流水（GL-P2-TRUST-001 T2 / D-10）。
 *
 * <p>证据是受限展示对象（脱敏后才给审判官/客服看）。每次有人读取证据详情，记一条不可变访问审计——
 * 回答「谁、何时、为何查看了哪条证据」，满足 D-10 证据访问审计要求。
 */
public record DisputeEvidenceAccessAudit(
        Long id,
        String evidenceId,
        String disputeId,
        String viewerAccountId,
        String viewerRole,
        String purpose,
        Instant viewedAt) {
}
