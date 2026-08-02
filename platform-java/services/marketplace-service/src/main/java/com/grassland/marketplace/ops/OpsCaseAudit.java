package com.grassland.marketplace.ops;

import java.time.Instant;

/**
 * 运营处置审计流水一条（GL-P1-OPS-001 Stage 1）。不可变：无 setter，仓储层无 update/delete。
 *
 * <p>{@code actorAccountId} 为空 + {@code actorRole=system} 表示系统登记（{@code registered}）。
 */
public record OpsCaseAudit(
        Long id,
        String caseId,
        String action,
        String actorAccountId,
        String actorRole,
        String fromStatus,
        String toStatus,
        String note,
        Instant createdAt) {
}
