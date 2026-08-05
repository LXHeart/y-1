package com.grassland.trust.audit;

import java.time.Instant;

/**
 * 争议生命周期审计流水一条（GL-P2-TRUST-001 T2）。不可变：无 setter，仓储层无 update/delete。
 *
 * <p>{@code actorAccountId} 为空 + {@code actorRole=system} 表示系统登记（如自动终局）。
 * 与 trust_outbox 事件互补：outbox 是给下游消费者的事件流，本表是带 actor 归属的不可变审计（D-10/D-06）。
 */
public record DisputeAudit(
        Long id,
        String disputeId,
        String action,
        String actorAccountId,
        String actorRole,
        String note,
        Instant createdAt) {
}
