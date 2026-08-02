package com.grassland.marketplace.ops;

import java.time.Instant;

/**
 * 一次受限处置动作的幂等台账行（GL-P1-OPS-001 Stage 2）。
 *
 * <p>{@code operationId} 是幂等键（唯一索引）：重复提交同一 operationId 直接回放既有行，
 * 不再调下游。{@code status} 从 {@code pending} 起，下游返回后落 {@code succeeded}/{@code failed}。
 */
public record OpsCaseAction(
        String id,
        String caseId,
        String operationId,
        String action,
        String status,
        String requestedBy,
        String outcome,
        String error,
        Instant createdAt,
        Instant completedAt) {

    /** 动作：重试对账（finance reconcile，captured 态下由 finance 内部走 reverse）。 */
    public static final String RETRY_RECONCILIATION = "retry_reconciliation";

    /** 动作：释放预留（finance release，reserved 态下退回商家可用余额）。 */
    public static final String RELEASE_FUNDS = "release_funds";

    /** 动作：死信重投原 topic。 */
    public static final String DLT_REPLAY = "dlt_replay";

    /** 动作：死信弃置（只标记，不删消息）。 */
    public static final String DLT_DISCARD = "dlt_discard";

    public boolean isPending() {
        return "pending".equals(status);
    }
}
