package com.grassland.marketplace.workflow.saga;

/**
 * 接受报名资金预留 Saga 的输入（草场 Epic 4 Slice 4F / HLD 10.2）。全可序列化——Temporal workflow 参数。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code applicationId} = 报名 id；同时作 finance 的 {@code engagement_ref}（决策④：复用报名 id 作履约引用）。</li>
 *   <li>{@code merchantAccountId} = 任务 owner——activity 重验资源归属用。</li>
 *   <li>{@code operatorAccountId} = 实际执行接受操作的账号；门店任务可为另一位当前 MANAGER。</li>
 *   <li>{@code organizationId} = 任务所属 org——现签服务断言带 org 上下文，finance org 级授权用。</li>
 *   <li>{@code amountCents} = {@code task.bounty_cents}——reserve 金额。</li>
 * </ul>
 */
public record AcceptanceInput(
        String applicationId,
        String taskId,
        String merchantAccountId,
        String organizationId,
        long amountCents,
        String operatorAccountId,
        String commandId) {

    /** Backward-compatible constructor for the pre-command-ledger request path. */
    public AcceptanceInput(String applicationId, String taskId, String merchantAccountId,
                           String organizationId, long amountCents, String operatorAccountId) {
        this(applicationId, taskId, merchantAccountId, organizationId, amountCents, operatorAccountId, null);
    }

    /** Backward-compatible constructor for existing tests and serialized workflow callers. */
    public AcceptanceInput(String applicationId, String taskId, String merchantAccountId,
                           String organizationId, long amountCents) {
        this(applicationId, taskId, merchantAccountId, organizationId, amountCents, merchantAccountId, null);
    }

    public String effectiveOperatorAccountId() {
        return operatorAccountId == null || operatorAccountId.isBlank() ? merchantAccountId : operatorAccountId;
    }
}
